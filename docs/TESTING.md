# Testing iptvdler

## Automated
```sh
./gradlew allTests                   # commonTest + JVM tests on desktop and Android host
./gradlew :androidApp:assembleDebug  # debug APK
```

What runs where:
- `shared/src/commonTest` — M3U parser, content classifier, header parsing (runs on desktop JVM and Android host).
  Fixture playlists live in `shared/src/commonTest/resources/fixtures/` (kept byte-exact via `.gitattributes`,
  `messy.m3u` has a BOM and CRLF line endings on purpose).
- `shared/src/desktopTest` — playlist refresh against an in-memory SQLite DB with a mocked HTTP server
  (`PlaylistRefresherTest`), and a Compose UI flow test (`AppFlowTest`) that drives the real app:
  disclaimer → playlists → add dialog → groups → channels → favorite → search → details → recents.
  It saves screenshots to `shared/build/screenshots/*.png` for a quick visual check.

## Manual testing on Windows (desktop app)
```sh
./gradlew :desktopApp:run                 # run from source
./gradlew :desktopApp:createDistributable # standalone app: desktopApp/build/compose/binaries/main/app/iptvdler/iptvdler.exe
```
App data (database) lives in `%APPDATA%\iptvdler\`; settings in the registry under
`HKCU\Software\JavaSoft\Prefs\io\github\aalpersen\iptvdler`. Delete both to start fresh
(the disclaimer shows again).

### Playlists (M1)
`docs/samples/sample.m3u` contains only public test streams (Mux, Apple).
- **File:** Add playlist → File → pick `docs/samples/sample.m3u`.
- **URL:** serve the folder locally and add `http://localhost:8000/sample.m3u`:
  ```sh
  cd docs/samples
  python -m http.server 8000
  ```
- Check: groups "Test streams" / "Header tests", search, favorites (heart), "Recently watched" after opening a
  channel, and the channel details screen showing parsed headers (User-Agent / Referer).
- Error handling: add `http://localhost:8000/does-not-exist.m3u` → "Refresh failed: Server returned HTTP 404";
  the previous channel list (if any) is kept.
- Large lists: generate a 100k-entry playlist and add it as a file:
  ```sh
  python -c "import sys; print('#EXTM3U'); [print(f'#EXTINF:-1 group-title=\"Group {i%200}\",Channel {i}\nhttp://example.invalid/{i}.ts') for i in range(100000)]" > big.m3u
  ```

### Playback (M2)
- Desktop needs **VLC 3.x, 64-bit** installed (found automatically in `C:\Program Files\VideoLAN\VLC`).
  Without it the player shows an "install VLC" message instead of crashing.
- Open any channel from `docs/samples/sample.m3u`: play/pause (Space), seek bar and ←/→ (±10 s) for VOD,
  "LIVE" badge for live streams, Quality & tracks dialog, aspect ratio (Fit/Fill/Stretch), fullscreen
  (button, double-click/double-tap, F, Esc to leave). Android also has picture-in-picture (button, or press
  Home while playing).
- VLC smoke test (plays a public Mux stream through the installed VLC, needs network):
  ```sh
  ./gradlew :shared:desktopTest --tests "*VlcSmokeTest*" -Piptvdler.vlcSmoke=true
  ```
  It saves a decoded frame to `shared/build/screenshots/vlc-frame.png`.
- Android emulator: `./gradlew :androidApp:installDebug`, then serve `docs/samples` and forward the port so
  the app can use `http://127.0.0.1:8000/sample.m3u`:
  ```sh
  adb reverse tcp:8000 tcp:8000
  ```

Known platform differences:
- VLC chooses HLS variants itself, so the Quality list only appears on Android (ExoPlayer).
- VLC only supports the `User-Agent` and `Referer` headers; ExoPlayer sends all playlist headers.

## HLS parsing (M3)
- Unit tests: `shared/src/commonTest/kotlin/.../core/hls/` with fixtures in
  `shared/src/commonTest/resources/fixtures/hls/` (master with audio/subtitle/CC groups, VOD, live with
  discontinuities, AES-128 key rotation, byte ranges, fMP4 maps, Widevine/FairPlay/PlayReady/SAMPLE-AES).
  URL resolution is checked against all RFC 3986 §5.4 examples.
- `HlsClientTest` (mock HTTP): redirects, header forwarding, master → variant/audio DRM checks, probing.
- Real public streams (network):
  ```sh
  ./gradlew :shared:desktopTest --tests "*PublicStreamsSmokeTest*" -Piptvdler.networkTests=true
  ```

## Downloads (M4)
Automated (no network): `DownloadEngineTest` (mock HTTP + in-memory DB + fake file system) covers AES-128
decryption (derived and explicit IVs), TS and fMP4 assembly, byte ranges (also when a server ignores `Range`),
separate audio, retries/backoff, pause/resume, resume after a crash or a failed segment, missing temp files,
automatic network retries, DRM/live refusal, free-space checks, unique file names, and direct-file downloads
with HTTP Range resume. `AppFlowTest.downloadMovieAndPlayItOffline` drives the UI end to end.

### Local HLS streams (incl. AES-128) with ffmpeg
```sh
mkdir -p hls-local/aes hls-local/fmp4 && cd hls-local
python -c "import os; open('aes/enc.key','wb').write(os.urandom(16))"
# key info file: key URI (as written into the playlist), key file path, [optional IV]
printf "enc.key
$(pwd)/aes/enc.key
" > aes/keyinfo

# MPEG-TS, AES-128, IV derived from the media sequence number
ffmpeg -f lavfi -i testsrc=duration=20:size=640x360:rate=25 -f lavfi -i sine=frequency=440:duration=20   -c:v libx264 -g 50 -c:a aac -shortest -hls_time 4 -hls_playlist_type vod   -hls_key_info_file aes/keyinfo -hls_segment_filename aes/seg%03d.ts aes/index.m3u8

# fMP4 (clear); then an AES-128 copy (ffmpeg can't encrypt fMP4 itself)
ffmpeg -f lavfi -i testsrc=duration=20:size=640x360:rate=25 -f lavfi -i sine=frequency=440:duration=20   -c:v libx264 -g 50 -c:a aac -shortest -hls_time 4 -hls_playlist_type vod   -hls_segment_type fmp4 -hls_fmp4_init_filename init.mp4 -hls_segment_filename fmp4/seg%03d.m4s fmp4/index.m3u8
python ../docs/tools/encrypt_fmp4_hls.py .

python -m http.server 8000 --bind 127.0.0.1
```
Download them with the real engine (real HTTP, real files in `shared/build/smoke-downloads/`):
```sh
./gradlew :shared:desktopTest --tests "*RealDownloadSmokeTest*" -Piptvdler.localHls=http://127.0.0.1:8000
./gradlew :shared:desktopTest --tests "*RealDownloadSmokeTest*" -Piptvdler.networkTests=true   # Mux, lowest quality
ffmpeg -v error -i shared/build/smoke-downloads/local-aes-ts.ts -f null -   # no output = decodes cleanly
```
The decrypted `local-fmp4-aes.mp4` must be byte-identical to `local-fmp4.mp4`.

## Subtitles (M5)
Automated: `SubtitlesTest` (WebVTT/SRT parsing and writing, `X-TIMESTAMP-MAP` offsets against the video's
first PTS, 33-bit rollover, de-duplication, PTS extraction from TS and fMP4), `SubtitleDownloadTest`
(renditions saved as sidecars, a broken subtitle track doesn't fail the video, standalone URLs, deletion),
`PlayerViewModelTest` (offline playback passes sidecars to the player).

Real stream with segmented WebVTT (Apple's example; its cues say "Bip!"/"Bop!" in sync with the picture):
```sh
./gradlew :shared:desktopTest --tests "*RealDownloadSmokeTest.appleFmp4*" -Piptvdler.networkTests=true
./gradlew :shared:desktopTest --tests "*VlcSmokeTest.playsDownloaded*" -Piptvdler.vlcSmoke=true   # VLC loads the .srt
```
In the app: add a playlist with
`https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8`,
tick English in the download dialog, then play it from Downloads: the subtitle shows by default.
The subtitle file format (SRT, VTT or both) is in Settings; "Add subtitles from URL…" on a finished
download adds any .vtt/.srt file.

## Live recording (M6)
Automated: `LiveRecordingTest` drives a simulated live window with injected time: start at the live edge,
no duplicate segments, stop at the end time, playlist resets, segments that left the window, a failing
segment, `#EXT-X-ENDLIST`, AES-128 key rotation, fMP4 init sections, continuous-stream reconnects, manual
stop, recordings bypassing the download limit, refusals, local-time helpers. `AppFlowTest.recordLiveChannelThenStop`
covers the UI.

Local live stream (real time, rolling 5-segment window, AES-128), then record 20 s of it:
```sh
cd hls-local && mkdir -p live && cp aes/enc.key live/ && printf "enc.key\n$(pwd)/live/enc.key\n" > live/keyinfo
ffmpeg -re -f lavfi -i testsrc=duration=90:size=640x360:rate=25 -f lavfi -i sine=frequency=440:duration=90 \
  -c:v libx264 -g 50 -c:a aac -shortest -f hls -hls_time 2 -hls_list_size 5 -hls_flags delete_segments \
  -hls_key_info_file live/keyinfo -hls_segment_filename live/seg%03d.ts live/index.m3u8 &
python -m http.server 8000 --bind 127.0.0.1 &
./gradlew :shared:desktopTest --tests "*RealDownloadSmokeTest.recordsLiveStream*" -Piptvdler.liveUrl=http://127.0.0.1:8000/live/index.m3u8
```
Public live stream (clear, 2 s segments):
`-Piptvdler.liveUrl=https://demo.unified-streaming.com/k8s/live/stable/live.isml/.m3u8`.
In the app, live channels have a red record button (channel list and player); recordings show in
Downloads → Recording with a Stop button and are saved as `Channel 2026-09-25 20.30.ts`.

## Desktop app and installer (M7)
```sh
./gradlew :desktopApp:packageDistributionForCurrentOS   # Windows: desktopApp/build/compose/binaries/main/msi/iptvdler-<version>.msi
```
The MSI installs per user (no admin), adds Start-menu and desktop shortcuts and upgrades in place
(fixed upgrade UUID in `desktopApp/build.gradle.kts`). To inspect it without installing:
`msiexec /a iptvdler-0.1.0.msi /qn TARGETDIR=C:\some\folder`.

Desktop behaviour worth checking by hand: only one iptvdler runs at a time (a second start says so);
closing the window during downloads/recordings asks first (downloads resume next start, recordings are
finalized); "Show in folder" selects the file in Explorer; ↑/↓ and M change volume / mute in the player.

fMP4 downloads are shifted to start at 0 and get a `sidx` index (seekable in ExoPlayer, correct duration,
subtitles in sync). To check subtitle sync precisely, replace a download's `.srt` with one that says
"clock N" every second and compare it with the clock burned into Apple's example video.

To use them in the app, add a playlist such as:
```
#EXTM3U
#EXTINF:-1 group-title="Movies",Local AES Movie
http://127.0.0.1:8000/aes/index.m3u8
```
On the Android emulator run `adb reverse tcp:8000 tcp:8000` first. Downloads are stored in
`Android/data/io.github.aalpersen.iptvdler/files/Movies/iptvdler/` (Export… copies them elsewhere);
on desktop in the folder from Settings (default `~/Downloads/iptvdler`).

## Polish and release (M8)
- **Release APK:** `./gradlew :androidApp:assembleRelease` gives `androidApp/build/outputs/apk/release/androidApp-release.apk`
  (R8 minified, about 3.5 MB). It was checked on the emulator: disclaimer, adding a playlist by URL, playback,
  a download through the foreground service, offline playback and PiP. Signing uses `keystore.properties`
  (see [README](README.md)); without it the build uses the debug key and warns.
- **Error messages:** `UserMessagesTest` covers HTTP statuses (401/403, 404, 429, 5xx), unknown host,
  connection refused, timeouts, and a full or unwritable disk. Storage errors fail a download immediately
  instead of being retried as network errors. A queued download that is retrying shows the reason.
- **Crash logs:** an uncaught exception writes `crash-<millis>.txt` (the newest 10 are kept). On desktop a
  dialog shows the path, and Settings → About → Crash logs opens the folder.
- **About / disclaimer:** Settings → About shows the version (from `gradle.properties`) and the disclaimer again.
- **Icons:**
  - Sources: `desktopApp/icons/icon.{ico,icns,png}` and the Android mipmaps (adaptive icon, API 26+).
  - They're generated from a 512×512 transparent PNG with ImageMagick: a circle with a light-blue radial
    gradient and a blue ring.

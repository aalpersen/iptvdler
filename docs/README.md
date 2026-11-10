# iptvdler

An IPTV player for Android and desktop (Windows, macOS, Linux) that can also **download VOD streams,
record live streams and download subtitles** from playlists you add yourself.

> [!IMPORTANT]
> **No content is included.** iptvdler ships with no channels, playlists, provider links or any other content.
> You add your own M3U playlist or Xtream Codes account.
>
> **You are responsible** for having the rights to everything you stream, download or record with it, and for
> following the laws where you live.
>
> **DRM-protected streams can't be downloaded.** iptvdler doesn't bypass copy protection. Streams that use
> `SAMPLE-AES`, `SAMPLE-AES-CTR`, or a Widevine / PlayReady / FairPlay key format are refused with
> "This stream is DRM-protected and can't be downloaded." Plain HLS AES-128 is part of the HLS spec and is supported.

## Install

Download the files from the [latest release](https://github.com/aalpersen/iptvdler/releases/latest).
Every release lists SHA-256 checksums in `SHA256SUMS.txt`.

### Android (7.0 or newer)
1. Download `iptvdler-<version>.apk`. The same file works on every phone and tablet.
2. Open the file on the phone. Android asks you to allow **Install unknown apps** for your browser or file manager
   the first time. Allow it, then go back and tap **Install**.
3. Updates: install the newer APK over the old one. Your playlists and downloads stay. iptvdler can tell you
   when a new version is out (Settings → About → Check for updates); it only opens the release page and never
   installs anything by itself.

### Desktop
Desktop playback uses **VLC 3.x (64-bit)**, which must be installed separately from
[videolan.org](https://www.videolan.org/vlc/). Downloads work without it.

| OS | File | Notes |
|----|------|-------|
| Windows 10/11 | `.msi` | Installs for the current user, without admin rights. The installer isn't code-signed, so SmartScreen may warn: choose **More info → Run anyway**. |
| macOS | `.dmg` | Not notarized. The first time, right-click the app and choose **Open**. |
| Debian / Ubuntu | `.deb` | `sudo apt install ./iptvdler_*.deb`, plus `sudo apt install vlc`. |

Check a download against the checksums:

```sh
sha256sum -c SHA256SUMS.txt --ignore-missing          # Linux / Git Bash
shasum -a 256 -c SHA256SUMS.txt --ignore-missing      # macOS
```
```powershell
Get-FileHash .\iptvdler-0.1.0.msi -Algorithm SHA256    # Windows: compare with SHA256SUMS.txt
```

## Features
- **Playlists:** M3U/M3U8 by URL or file, with manual and scheduled refresh. Groups, search (accent-insensitive,
  Turkish-aware), favorites, recently watched, and Live / Movie / Series classification. Per-channel HTTP
  headers (`#EXTVLCOPT`, `#KODIPROP`, `url|User-Agent=…`) are used for playback *and* downloads.
- **Xtream Codes:** log in with server + username + password (or paste an Xtream link); live, movies and
  series by category, series episodes playable and downloadable. See [features/02-xtream](features/02-xtream/README.md).
- **Player:**
  - Android uses Media3 ExoPlayer; desktop uses VLC through VLCJ.
  - Seeking, quality, audio and subtitle tracks, aspect ratio, fullscreen, volume.
  - Picture-in-picture on Android.
- **HLS downloads:**
  - Pick the quality, audio language and subtitles.
  - Segments download in parallel with retries and are fully resumable (per-segment state in SQLite).
  - AES-128 is decrypted.
  - Output is assembled into one file: TS segments become `.ts`; fMP4 becomes a seekable `.mp4` that starts at 0.
  - A queue with a limit on simultaneous downloads.
  - Wi-Fi-only on Android, and a free-space check before starting.
- **Live recording:** HLS live playlists and continuous streams; stop manually, after a set duration, or at an
  end time.
- **Subtitles:**
  - Segmented WebVTT renditions are merged, with `X-TIMESTAMP-MAP` and 33-bit rollover handled.
  - You can also add standalone `.vtt`/`.srt` files.
  - They're saved as `Movie.en.srt` and/or `.vtt` and load in the built-in offline player.
- **Offline:** the Downloads screen shows active, queued, paused, completed and failed items. Android runs
  downloads in a foreground service and has "Export…"; desktop has "Show in folder".
- **Languages:** English and Türkçe (follows the system, or choose in Settings). See
  [features/01-multi-language](features/01-multi-language/README.md).
- **Update check:** optional, once a day, links to the release page. See
  [features/03-update-check](features/03-update-check/README.md).

## Building from source
Requirements:
- JDK 21.
- Android SDK with platform 37 (set in `local.properties`: `sdk.dir=…`).
- VLC 3.x 64-bit, for desktop playback.

```sh
./gradlew allTests                                      # unit + UI tests
./gradlew :desktopApp:run                               # run the desktop app
./gradlew :desktopApp:packageDistributionForCurrentOS   # MSI (Windows) / DMG (macOS) / DEB (Linux)
./gradlew :androidApp:assembleDebug                     # debug APK
./gradlew :androidApp:assembleRelease                   # minified release APK (see signing below)
```

On Windows PowerShell use `.\gradlew` instead of `./gradlew`. A first-time machine setup is in
[SETUP.md](SETUP.md).

The version lives in `gradle.properties` (`iptvdler.versionName`, `iptvdler.versionCode`) and is used by the
APK, the installers and Settings → About. Releases are built by GitHub Actions; see [RELEASING.md](RELEASING.md).

### Release signing (Android, local builds)
1. Create your own keystore. Keep it and its passwords safe: every update must be signed with the same key.
   ```sh
   keytool -genkeypair -v -keystore iptvdler-release.jks -keyalg RSA -keysize 4096 -validity 10000 -alias iptvdler
   ```
2. Fill in `keystore.properties` in the project root, starting from `keystore.properties.example`. Both that file
   and `*.jks` are git-ignored.

Without a keystore, `assembleRelease` still works but signs with the debug key and prints a warning.
An APK you sign yourself can't update one installed from the GitHub releases (different signature).

## Project layout
- `shared` holds all logic and UI in Kotlin Multiplatform / Compose Multiplatform:
  - M3U and HLS parsers, the download engine, subtitles, SQLDelight, Koin and the screens.
  - `androidMain` and `desktopMain` contain only the players and platform services.
- `androidApp` is the thin Android application.
- `desktopApp` is the thin desktop application: window, installers and icons in `desktopApp/icons`.

Manual testing with public and locally generated streams (including AES-128 and live) is described in
[TESTING.md](TESTING.md).

## Crash logs
- Desktop: `%APPDATA%\iptvdler\logs` (macOS `~/Library/Application Support/iptvdler/logs`,
  Linux `~/.local/share/iptvdler/logs`). Open it from Settings → About → Crash logs.
- Android: `files/logs` in the app's private storage.

Crash logs stay on your device. iptvdler has no analytics or telemetry. Apart from the playlists, streams and
logos you add, its only network request is the optional daily update check to `api.github.com`.

## Contributing and security
- Bugs and ideas: [GitHub issues](https://github.com/aalpersen/iptvdler/issues). Please never post real playlist URLs,
  Xtream credentials or provider names. See [CONTRIBUTING.md](CONTRIBUTING.md).
- Vulnerabilities: report them privately as described in [SECURITY.md](SECURITY.md).
- Changes per version: [CHANGELOG.md](CHANGELOG.md).

## License
iptvdler is free software, licensed under the **GNU General Public License v3.0 or later**
(`SPDX-License-Identifier: GPL-3.0-or-later`). See [LICENSE](../LICENSE).

It uses, among others, [vlcj](https://github.com/caprica/vlcj) (GPL-3.0), AndroidX Media3, Ktor, Koin,
SQLDelight, Okio, Coil and Compose Multiplatform (Apache-2.0). It comes with **no warranty**.

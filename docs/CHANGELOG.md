# Changelog

All notable changes per release. The release workflow copies the section for the tagged version into the
GitHub release notes, so keep the `## [x.y.z]` headings exactly in this form.

## [Unreleased]

## [0.1.0] - 2026-11-10
First public release.

- Playlists: M3U/M3U8 by URL or file, scheduled refresh, groups, search, favorites, recently watched,
  Live / Movie / Series classification, per-channel HTTP headers for playback and downloads.
- Xtream Codes accounts: live, movies and series, with downloadable episodes.
- Player: Media3 ExoPlayer on Android (with picture-in-picture), VLC on desktop; quality, audio and subtitle tracks.
- HLS downloads: parallel, resumable, AES-128, TS and fMP4 assembly, queue, Wi-Fi-only and free-space checks.
  DRM-protected streams are refused.
- Live recording with manual, duration or end-time stop.
- Subtitles: segmented WebVTT merge, SRT/VTT export, standalone subtitle files, offline playback with subtitles.
- English and Turkish UI.
- Optional daily update check that links to the GitHub release page.
- Desktop installers: MSI (Windows), DMG (macOS), DEB (Linux).

# Contributing

Thanks for helping! Bug reports, translations and pull requests are welcome.

## Ground rules
- **No content.** Never add channels, playlists, provider links, logos of real providers, or EPG URLs to the code,
  tests, docs or screenshots. Use the public test streams (Apple, Mux), `example.com` / `*.example.test`,
  `localhost`, and the fake Xtream server in `docs/tools/fake_xtream_server.py`.
- **No DRM circumvention.** Pull requests that decrypt or bypass Widevine, PlayReady, FairPlay, SAMPLE-AES or
  similar won't be accepted. Plain HLS AES-128 is supported.
- **Privacy in issues.** Don't paste real playlist URLs, Xtream usernames/passwords or URL tokens. Replace them with
  `example.com`, `user` and `pass`. The app strips Xtream credentials from its refresh logs, but check anything before posting.
- By contributing you agree that your contribution is licensed under GPL-3.0-or-later, like the rest of the project.

## Development
- Setup: [SETUP.md](SETUP.md). Build commands: [README.md](README.md#building-from-source).
- All parsing, download and subtitle logic lives in `shared/src/commonMain` and is unit-tested without a device.
  Platform code (`androidMain`, `desktopMain`) stays thin: add an interface in common code plus implementations.
- Before opening a pull request, run:
  ```sh
  ./gradlew allTests
  ./gradlew :androidApp:assembleDebug
  ```
- Add or update tests for parser and download changes. Fixtures go in `shared/src/commonTest/resources/fixtures`.
- Every user-visible string goes into `Strings` with English **and** Turkish text (`EnStrings`, `TrStrings`);
  the compiler enforces it. See [features/01-multi-language](features/01-multi-language/README.md) to add a language.
- Documentation (Markdown) lives under `docs/`. A larger feature gets its own folder
  `docs/features/<nn>-<name>/README.md` with what was built, how to test it and known gaps.
- Manual testing with public and locally generated streams: [TESTING.md](TESTING.md).

## Translations
New UI languages are very welcome. Follow "Adding a language" in
[features/01-multi-language](features/01-multi-language/README.md) and open a pull request.

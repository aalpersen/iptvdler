# Releasing iptvdler

Releases are built and published by GitHub Actions (`.github/workflows/release.yml`) when a tag `v*` is pushed.
Nothing is built or signed on a personal machine.

## 1. Version
The version lives in **`gradle.properties`** and is the single source for the APK, the desktop installers and
Settings → About:

```properties
iptvdler.versionName=0.2.0
iptvdler.versionCode=2
```

- `versionName` must be `MAJOR.MINOR.PATCH` with numbers only. The Windows MSI requires MAJOR ≤ 255, MINOR ≤ 255,
  PATCH ≤ 65535, and only upgrades an existing install when the version is higher.
- `versionCode` must go up by at least 1 for every release. Android refuses to install an APK with a lower
  `versionCode` over a newer one.

For a one-off local build with another version: `./gradlew :androidApp:assembleRelease -Piptvdler.versionName=0.2.0 -Piptvdler.versionCode=2`.

## 2. Release checklist
1. Bump `iptvdler.versionName` and `iptvdler.versionCode` in `gradle.properties`.
2. In `docs/CHANGELOG.md`, move the `[Unreleased]` notes into a new section `## [0.2.0] - YYYY-MM-DD`.
   The workflow copies that section into the release notes.
3. Run the tests locally: `./gradlew allTests`.
4. Commit, then tag and push:
   ```sh
   git commit -am "Release 0.2.0"
   git tag v0.2.0
   git push origin main v0.2.0
   ```
5. Watch the run under **Actions → Release**. When it's green, open **Releases** and check:
   - `iptvdler-0.2.0.apk`, the `.msi`, `.dmg` and `.deb`, and `SHA256SUMS.txt` are attached;
   - the notes match the changelog;
   - the APK installs over the previous version on a test phone (same signature, higher `versionCode`).

The app's update check (`api.github.com/repos/aalpersen/iptvdler/releases/latest`) only sees full releases, so users are
notified as soon as the release is published.

## 3. What the workflow does
| Job | Runner | Output |
|-----|--------|--------|
| `verify` | ubuntu | Fails unless the tag is `v<versionName>` or `v<versionName>-<suffix>`; runs `./gradlew allTests`. |
| `android` | ubuntu | Signed release APK (`assembleRelease`), verified with `apksigner`, renamed `iptvdler-<version>.apk`. |
| `desktop` | windows, macos, ubuntu | `packageDistributionForCurrentOS` → `.msi`, `.dmg`, `.deb` (jpackage only builds for the runner's own OS). |
| `release` | ubuntu | `SHA256SUMS.txt` over all files, notes from `docs/CHANGELOG.md`, GitHub Release. |

- Only the `release` job has `contents: write`; everything else is read-only.
- A tag with a suffix, like `v0.2.0-rc1`, becomes a GitHub **pre-release**. The in-app update check ignores those.
- The keystore is decoded into the runner's temp folder, used once and deleted. It's never uploaded, cached or
  logged.

## 4. Signing key and repository secrets (one-time)
The APK is signed with a **release keystore that exists only for iptvdler**. Don't reuse a key from any other app.
Keep the `.jks` file and both passwords in a password manager: if the key is lost, existing installs can't be
updated and every user has to uninstall and reinstall.

1. Create the keystore (`keytool` comes with any JDK, e.g. Android Studio's `jbr\bin`). It asks for the passwords
   interactively, so they don't end up in your shell history:
   ```powershell
   keytool -genkeypair -v -keystore iptvdler-release.jks -keyalg RSA -keysize 4096 -validity 10000 -alias iptvdler
   ```
   For the name questions, a neutral answer like `iptvdler` is enough; the certificate is visible to anyone who
   inspects the APK.
2. Base64-encode it, in PowerShell:
   ```powershell
   [Convert]::ToBase64String([IO.File]::ReadAllBytes("$PWD\iptvdler-release.jks")) | Set-Content -NoNewline iptvdler-release.b64
   ```
   or with `certutil -encode iptvdler-release.jks iptvdler-release.b64` (then delete the `-----BEGIN/END
   CERTIFICATE-----` lines from the file).
3. On GitHub: **Settings → Secrets and variables → Actions → New repository secret**, add:

   | Secret | Value |
   |--------|-------|
   | `RELEASE_KEYSTORE_BASE64` | contents of `iptvdler-release.b64` |
   | `RELEASE_KEYSTORE_PASSWORD` | the keystore password |
   | `RELEASE_KEY_ALIAS` | `iptvdler` |
   | `RELEASE_KEY_PASSWORD` | the key password (same as the keystore password if you pressed Enter) |

4. Delete `iptvdler-release.b64`. Keep the `.jks` only in the password manager or another safe place outside the repo.

If the secrets are missing, the `android` job fails instead of publishing a debug-signed APK.

## 5. Local builds
| What | Command |
|------|---------|
| Tests | `./gradlew allTests` |
| Debug APK | `./gradlew :androidApp:assembleDebug` |
| Release APK | `./gradlew :androidApp:assembleRelease` (signing: `keystore.properties`, see the README) |
| Install debug build on a device/emulator | `./gradlew :androidApp:installDebug` |
| Run the desktop app | `./gradlew :desktopApp:run` |
| Installer for this OS | `./gradlew :desktopApp:packageDistributionForCurrentOS` |
| Clean | `./gradlew clean` |

On Windows PowerShell use `.\gradlew`. Outputs:

| Build | File |
|-------|------|
| Release APK | `androidApp/build/outputs/apk/release/androidApp-release.apk` |
| Windows installer | `desktopApp/build/compose/binaries/main/msi/iptvdler-<version>.msi` |
| R8 mapping file | `androidApp/build/outputs/mapping/release/mapping.txt` |

The mapping file turns obfuscated stack traces from release builds back into readable ones. The release workflow
keeps it as a workflow artifact (not attached to the release).

### Running on an emulator
From PowerShell in the project root:
```powershell
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
& "$sdk\emulator\emulator.exe" -list-avds                   # e.g. Medium_Phone
Start-Process "$sdk\emulator\emulator.exe" "-avd Medium_Phone"
.\gradlew :androidApp:installDebug
& "$sdk\platform-tools\adb.exe" shell am start -n io.github.aalpersen.iptvdler/.MainActivity
& "$sdk\platform-tools\adb.exe" logcat -s iptvdler           # the app's own log lines
```
- With a phone and an emulator connected, pick one with `$env:ANDROID_SERIAL = "emulator-5554"` (see `adb devices`).
- `installDebug` only replaces an installed app with the same signature. A release APK from GitHub is signed
  differently, so uninstall it first (`adb uninstall io.github.aalpersen.iptvdler`).

## 6. Not set up
- The Windows MSI isn't code-signed and the macOS DMG isn't notarized; both need paid certificates.
- No app store builds.

# iptvdler — Local Setup (Kotlin Multiplatform + Compose Multiplatform)

Stack: **Kotlin Multiplatform (KMP) + Compose Multiplatform**.
Targets now: **Android**. Targets later: **Desktop (Windows / macOS / Linux, via JVM)**, optionally iOS.
Business logic (M3U parser, HLS parser, download engine, subtitle tools) lives in shared Kotlin code; only the video player and a few platform services are per-platform.

> There is no official "create KMP project" CLI. Scaffolding is done by the JetBrains web wizard or Android Studio's wizard. Everything after scaffolding is pure command line (Gradle).

---

## 1. Prerequisites (one-time)

### JDK 17+
Android Studio ships its own JDK (JBR). Point `JAVA_HOME` at it, or install any JDK 17/21.

macOS / Linux (bash/zsh, add to `~/.zshrc` or `~/.bashrc`):
```bash
# macOS default Android Studio location:
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
# Linux (adjust to your install):
# export JAVA_HOME="$HOME/android-studio/jbr"

export ANDROID_HOME="$HOME/Library/Android/sdk"   # macOS
# export ANDROID_HOME="$HOME/Android/Sdk"          # Linux
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
```

Windows (PowerShell, persistent for the user):
```powershell
[Environment]::SetEnvironmentVariable("JAVA_HOME", "C:\Program Files\Android\Android Studio\jbr", "User")
[Environment]::SetEnvironmentVariable("ANDROID_HOME", "$env:LOCALAPPDATA\Android\Sdk", "User")
# Then add %JAVA_HOME%\bin, %ANDROID_HOME%\platform-tools and
# %ANDROID_HOME%\cmdline-tools\latest\bin to PATH (System Properties > Environment Variables), reopen terminal.
```

### Android SDK command-line tools
Android Studio > Settings > Languages & Frameworks > Android SDK > **SDK Tools** tab > tick **Android SDK Command-line Tools (latest)** > Apply.

Then verify / install packages from the terminal:
```bash
java -version
sdkmanager --list_installed
sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"   # use the latest stable API level shown by `sdkmanager --list`
sdkmanager --licenses
adb devices          # phone with USB debugging, or an emulator, should show here
```

Optional — create and start an emulator from CLI:
```bash
sdkmanager "system-images;android-36;google_apis;x86_64"     # arm64-v8a on Apple Silicon
avdmanager create avd -n iptvdler_test -k "system-images;android-36;google_apis;x86_64"
emulator -avd iptvdler_test &
```

### Android Studio plugin
Settings > Plugins > install **Kotlin Multiplatform** (JetBrains). Gives you the KMP project wizard and run configurations.

### Claude Code (to run the spec)
```bash
npm install -g @anthropic-ai/claude-code
claude --version
```

### For the desktop player later
Install **VLC** (the desktop target will use VLCJ, which needs libVLC on the machine).

---

## 2. Create the project

### Option A — Web wizard (closest to a CLI flow)
1. Open **https://kmp.jetbrains.com**
2. Project name: `iptvdler` — Project ID: `com.iptvdler.app` (change if you own a domain)
3. Tick **Android** and **Desktop** (leave iOS/Web/Server off for now; iOS can be added later)
4. UI: **Share UI (Compose Multiplatform)**
5. Download the zip, then:

```bash
cd ~/Projects
unzip ~/Downloads/iptvdler.zip -d .
cd iptvdler
chmod +x gradlew                  # macOS/Linux only
git init && git add . && git commit -m "Scaffold iptvdler from KMP wizard"
```

### Option B — Android Studio
File > New > New Project > **Kotlin Multiplatform** > same choices as above.

> Module names vary by wizard version (older: `composeApp`; newer templates may split into `shared` + `androidApp` + `desktopApp`). Check `settings.gradle.kts` and adjust the module name in the commands below.

---

## 3. Everyday Gradle commands

Modules: `:shared`, `:androidApp`, `:desktopApp`. On Windows use `gradlew.bat` (or `.\gradlew`).

```bash
./gradlew tasks                                   # list all tasks
./gradlew :androidApp:assembleDebug               # build debug APK
./gradlew :androidApp:installDebug                # build + install on connected device/emulator
adb shell am start -n io.github.aalpersen.iptvdler/.MainActivity  # launch
adb logcat --pid=$(adb shell pidof -s io.github.aalpersen.iptvdler)   # app logs

./gradlew allTests                                # run common + platform unit tests
./gradlew :androidApp:assembleRelease             # release APK (needs signing config)
./gradlew :androidApp:assembleRelease             # signed release APK

# Desktop (when you get there)
./gradlew :desktopApp:run                         # run desktop app on current OS
./gradlew :desktopApp:packageDistributionForCurrentOS   # .msi (Windows) / .dmg (macOS) / .deb (Linux)
```

APK output: `androidApp/build/outputs/apk/debug/`.

Note: desktop installers are built **on the OS they target** (build the .msi on Windows, .dmg on macOS).

---

## 4. Hand over to Claude Code

```bash
cd ~/Projects/iptvdler
cp ~/Downloads/CLAUDE.md .        # the spec file; Claude Code reads CLAUDE.md automatically every session
git add CLAUDE.md && git commit -m "Add project spec"
claude
```

Then type:
```
Read CLAUDE.md and start with Milestone 0. Stop and show me the result at the end of each milestone.
```

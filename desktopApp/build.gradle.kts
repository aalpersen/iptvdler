import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(projects.shared)
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)
}

compose.desktop {
    application {
        mainClass = "io.github.aalpersen.iptvdler.MainKt"
        // libVLC strings are UTF-8; JNA would otherwise use the Windows code page (mojibake in track names).
        jvmArgs += "-Djna.encoding=UTF-8"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "iptvdler"
            packageVersion = providers.gradleProperty("iptvdler.versionName").get()
            description = "IPTV player with downloads, live recording and subtitles"
            vendor = "iptvdler contributors"
            copyright = "© 2026 iptvdler contributors. GPL-3.0-or-later"

            windows {
                iconFile.set(project.file("icons/icon.ico"))
                menuGroup = "iptvdler"
                shortcut = true
                dirChooser = true
                // Installs without admin rights into the user's profile.
                perUserInstall = true
                // Never change: lets new MSIs upgrade existing installs instead of installing side by side.
                upgradeUuid = "123f3800-285c-4ef8-9e89-4565ea090d6b"
            }
            macOS {
                iconFile.set(project.file("icons/icon.icns"))
                bundleID = "io.github.aalpersen.iptvdler"
            }
            linux {
                iconFile.set(project.file("icons/icon.png"))
                packageName = "iptvdler"
                shortcut = true
            }
            // From `./gradlew :desktopApp:suggestRuntimeModules`; re-run it when adding dependencies.
            modules("java.instrument", "java.management", "java.prefs", "java.sql", "jdk.unsupported")
        }
    }
}

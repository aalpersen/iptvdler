import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/** Writes `BuildInfo.kt` (app version for the About screen) from gradle.properties. */
abstract class GenerateBuildInfo : DefaultTask() {
    @get:Input abstract val versionName: Property<String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val file = outputDir.file("io/github/aalpersen/iptvdler/BuildInfo.kt").get().asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |package io.github.aalpersen.iptvdler
            |
            |/** Generated from gradle.properties by :shared:generateBuildInfo. */
            |object BuildInfo {
            |    const val VERSION_NAME = "${versionName.get()}"
            |}
            |""".trimMargin(),
        )
    }
}

val generateBuildInfo = tasks.register<GenerateBuildInfo>("generateBuildInfo") {
    versionName.set(providers.gradleProperty("iptvdler.versionName"))
    outputDir.set(layout.buildDirectory.dir("generated/buildInfo/kotlin"))
}

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.sqldelight)
}

kotlin {
    jvmToolchain(21)

    android {
        namespace = "io.github.aalpersen.iptvdler.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        withHostTest {}
    }

    jvm("desktop") {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain.configure { kotlin.srcDir(generateBuildInfo) }
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.material3)
            implementation(libs.compose.material.icons.core)
            implementation(libs.compose.ui.tooling.preview)
            implementation(libs.androidx.navigation.compose)
            implementation(libs.androidx.lifecycle.viewmodel.compose)
            implementation(libs.androidx.lifecycle.runtime.compose)

            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.ktor.client.core)
            implementation(libs.okio)
            implementation(libs.sqldelight.coroutines)
            implementation(libs.multiplatform.settings)

            api(project.dependencies.platform(libs.koin.bom))
            api(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)

            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor3)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.okio.fakefilesystem)
            implementation(libs.multiplatform.settings.test)
        }
        androidMain.dependencies {
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.sqldelight.android.driver)
            implementation(libs.koin.android)
            implementation(libs.androidx.activity.compose)
            implementation(libs.media3.exoplayer)
            implementation(libs.media3.exoplayer.hls)
            implementation(libs.media3.ui)
        }
        getByName("desktopMain").dependencies {
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.sqldelight.sqlite.driver)
            implementation(libs.vlcj)
        }
        getByName("desktopTest").dependencies {
            implementation(libs.ktor.client.mock)
            implementation(libs.compose.ui.test)
            implementation(compose.desktop.currentOs)
        }
    }
}

sqldelight {
    databases {
        create("IptvdlerDatabase") {
            packageName.set("io.github.aalpersen.iptvdler.data.db")
        }
    }
}

// Opt-in tests that need network (and VLC for the VLC one):
// ./gradlew :shared:desktopTest --tests "*VlcSmokeTest*" -Piptvdler.vlcSmoke=true
// ./gradlew :shared:desktopTest --tests "*PublicStreamsSmokeTest*" -Piptvdler.networkTests=true
// ./gradlew :shared:desktopTest --tests "*RealDownloadSmokeTest*" -Piptvdler.localHls=http://127.0.0.1:8000
tasks.named<Test>("desktopTest") {
    systemProperty("iptvdler.vlcSmoke", providers.gradleProperty("iptvdler.vlcSmoke").getOrElse("false"))
    systemProperty("iptvdler.networkTests", providers.gradleProperty("iptvdler.networkTests").getOrElse("false"))
    systemProperty("iptvdler.localHls", providers.gradleProperty("iptvdler.localHls").getOrElse(""))
    systemProperty("iptvdler.liveUrl", providers.gradleProperty("iptvdler.liveUrl").getOrElse(""))
}

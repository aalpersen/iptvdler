package io.github.aalpersen.iptvdler.platform

import java.io.File

/** Per-user application data directory following each desktop OS's convention. */
object AppDirs {
    private const val APP_NAME = "iptvdler"

    val dataDir: File by lazy {
        val os = System.getProperty("os.name").lowercase()
        val home = System.getProperty("user.home")
        val base = when {
            os.contains("win") -> System.getenv("APPDATA")?.let(::File) ?: File(home, "AppData/Roaming")
            os.contains("mac") -> File(home, "Library/Application Support")
            else -> System.getenv("XDG_DATA_HOME")?.let(::File) ?: File(home, ".local/share")
        }
        File(base, APP_NAME).apply { mkdirs() }
    }
}

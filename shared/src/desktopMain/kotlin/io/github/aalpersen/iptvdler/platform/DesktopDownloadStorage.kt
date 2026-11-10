package io.github.aalpersen.iptvdler.platform

import io.github.aalpersen.iptvdler.core.storage.DownloadStorage
import io.github.aalpersen.iptvdler.data.settings.AppSettings
import java.io.File
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/** Downloads go to the folder chosen in settings, or ~/Downloads/iptvdler. */
class DesktopDownloadStorage(private val settings: AppSettings) : DownloadStorage {
    override val fileSystem: FileSystem = FileSystem.SYSTEM

    override fun downloadsDirectory(): Path {
        val dir = settings.downloadFolder.value?.toPath() ?: defaultDirectory()
        fileSystem.createDirectories(dir)
        return dir
    }

    override fun freeBytes(path: Path): Long? =
        generateSequence(path.toFile()) { it.parentFile }.firstOrNull(File::exists)?.usableSpace?.takeIf { it > 0 }

    companion object {
        fun defaultDirectory(): Path = (File(System.getProperty("user.home"), "Downloads").path.toPath()) / "iptvdler"
    }
}

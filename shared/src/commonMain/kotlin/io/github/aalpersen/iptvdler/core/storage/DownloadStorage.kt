package io.github.aalpersen.iptvdler.core.storage

import okio.FileSystem
import okio.Path

/** Where downloads go, and how much room is left there. */
interface DownloadStorage {
    val fileSystem: FileSystem

    /** Folder for finished files; created if missing. */
    fun downloadsDirectory(): Path

    /** Folder for in-progress segments of one task (same volume as [downloadsDirectory], so moves are cheap). */
    fun tempDirectory(taskId: Long): Path = downloadsDirectory() / ".iptvdler-tmp" / taskId.toString()

    /** Usable bytes on the volume holding [path], or null if unknown. */
    fun freeBytes(path: Path): Long?
}

/** Is the device on a network where downloads may run (for the Wi-Fi-only setting)? */
fun interface NetworkMonitor {
    /** True on Wi-Fi/Ethernet or any other unmetered connection. */
    fun isOnUnmeteredNetwork(): Boolean
}

/** File names that are safe on Windows, macOS, Linux and Android. */
object FileNames {
    private val illegal = Regex("""[\\/:*?"<>|\u0000-\u001F]""")

    fun sanitize(name: String, maxLength: Int = 120): String =
        name.replace(illegal, "_").trim().trimEnd('.', ' ').take(maxLength).ifBlank { "download" }

    /** "Title.ts", or "Title (2).ts" ... when taken. */
    fun unique(directory: Path, baseName: String, extension: String, exists: (Path) -> Boolean): Path {
        var candidate = directory / "$baseName.$extension"
        var n = 2
        while (exists(candidate)) {
            candidate = directory / "$baseName ($n).$extension"
            n++
        }
        return candidate
    }
}

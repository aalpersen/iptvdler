package io.github.aalpersen.iptvdler.platform

import io.github.aalpersen.iptvdler.BuildInfo
import kotlin.time.Clock
import okio.FileSystem
import okio.Path

/** Writes uncaught exceptions to `crash-<time>.txt` files in [directory], keeping the newest [keep]. */
class CrashLog(
    private val fileSystem: FileSystem,
    val directory: Path,
    private val platform: String,
    private val keep: Int = 10,
) {
    /** Returns the written file, or null if even that failed (never throws: it runs while crashing). */
    fun write(threadName: String, error: Throwable): Path? = runCatching {
        fileSystem.createDirectories(directory)
        val now = Clock.System.now()
        val file = directory / "crash-${now.toEpochMilliseconds()}.txt" // sorts by time
        fileSystem.write(file) {
            writeUtf8("iptvdler ${BuildInfo.VERSION_NAME} ($platform)\n")
            writeUtf8("Time: $now\nThread: $threadName\n\n")
            writeUtf8(error.stackTraceToString())
        }
        fileSystem.list(directory).filter { it.name.startsWith("crash-") }.sortedDescending().drop(keep).forEach(fileSystem::delete)
        file
    }.getOrNull()
}

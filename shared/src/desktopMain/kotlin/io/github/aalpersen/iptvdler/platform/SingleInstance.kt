package io.github.aalpersen.iptvdler.platform

import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.StandardOpenOption

/**
 * Keeps a second iptvdler process from running: both would share the database and run the same
 * downloads. The OS releases the lock when the process exits, even after a crash.
 */
object SingleInstance {
    private var lock: FileLock? = null

    /** True if this process now owns the app; false if another iptvdler is already running. */
    fun acquire(dataDir: File = AppDirs.dataDir): Boolean {
        if (lock != null) return true
        val channel = FileChannel.open(
            File(dataDir, "iptvdler.lock").toPath(),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
        )
        lock = runCatching { channel.tryLock() }.getOrNull()
        if (lock == null) channel.close()
        return lock != null
    }
}

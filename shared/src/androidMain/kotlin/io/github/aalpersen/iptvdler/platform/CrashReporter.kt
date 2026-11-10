package io.github.aalpersen.iptvdler.platform

import android.content.Context
import android.os.Build
import okio.FileSystem
import okio.Path.Companion.toOkioPath

/** Logs uncaught exceptions to `files/logs` in app storage, then lets the system handle the crash as usual. */
object CrashReporter {
    fun install(context: Context) {
        val log = CrashLog(
            FileSystem.SYSTEM,
            context.filesDir.resolve("logs").toOkioPath(),
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}",
        )
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            log.write(thread.name, error)
            previous?.uncaughtException(thread, error)
        }
    }
}

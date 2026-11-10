package io.github.aalpersen.iptvdler.platform

import io.github.aalpersen.iptvdler.i18n.strings
import java.io.File
import javax.swing.JOptionPane
import kotlin.system.exitProcess
import okio.FileSystem
import okio.Path.Companion.toOkioPath

/** Logs uncaught exceptions to `<app data>/logs` and tells the user before the app exits. */
object CrashReporter {
    val logDir: File by lazy { File(AppDirs.dataDir, "logs") }
    private val log by lazy { CrashLog(FileSystem.SYSTEM, logDir.toOkioPath(), "${System.getProperty("os.name")}, Java ${System.getProperty("java.version")}") }

    fun install() {
        Thread.setDefaultUncaughtExceptionHandler { thread, error -> report(thread.name, error) }
    }

    /** Writes the log, shows a dialog and exits. Used for background threads and the Compose window. */
    fun report(threadName: String, error: Throwable): Nothing {
        val file = log.write(threadName, error)
        runCatching {
            JOptionPane.showMessageDialog(
                null,
                strings.crashMessage + "\n" + (file?.let { strings.crashSavedTo(it.toString()) } ?: error.toString()),
                "iptvdler",
                JOptionPane.ERROR_MESSAGE,
            )
        }
        exitProcess(1)
    }
}

package io.github.aalpersen.iptvdler.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import io.github.aalpersen.iptvdler.i18n.strings
import io.github.aalpersen.iptvdler.platform.CrashReporter
import java.awt.Desktop
import java.io.File
import javax.swing.JFileChooser
import javax.swing.UIManager

@Composable
actual fun rememberDownloadFileAction(): DownloadFileAction = remember(strings) {
    DownloadFileAction(strings.showInFolder) { path, _ -> showInFolder(File(path)) }
}

/** Opens the file manager with [file] selected (Windows Explorer, macOS Finder), else just its folder. */
internal fun showInFolder(file: File) {
    val os = System.getProperty("os.name").lowercase()
    runCatching {
        when {
            os.contains("win") -> ProcessBuilder("explorer.exe", "/select,", file.absolutePath).start()
            Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE_FILE_DIR) ->
                Desktop.getDesktop().browseFileDirectory(file)
            else -> Desktop.getDesktop().open(file.parentFile)
        }
    }
}

@Composable
actual fun rememberNotificationPermissionRequest(): () -> Unit = remember { {} }

@Composable
actual fun rememberFolderPicker(onPicked: (String) -> Unit): (() -> Unit)? {
    val current by rememberUpdatedState(onPicked)
    return remember {
        {
            // Native look for the chooser (Compose itself doesn't use Swing's look and feel).
            runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
            val chooser = JFileChooser().apply {
                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                dialogTitle = strings.chooseDownloadFolder
            }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                chooser.selectedFile?.absolutePath?.let(current)
            }
        }
    }
}

actual val supportsWifiOnlySetting: Boolean = false

@Composable
actual fun rememberOpenLogFolder(): (() -> Unit)? = remember {
    {
        runCatching {
            CrashReporter.logDir.mkdirs()
            Desktop.getDesktop().open(CrashReporter.logDir)
        }
    }
}

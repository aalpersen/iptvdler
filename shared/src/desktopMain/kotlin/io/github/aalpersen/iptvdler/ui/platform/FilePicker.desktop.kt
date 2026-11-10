package io.github.aalpersen.iptvdler.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import io.github.aalpersen.iptvdler.i18n.strings
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

@Composable
actual fun rememberPlaylistFilePicker(onResult: (PickedFile?) -> Unit): () -> Unit {
    val currentOnResult by rememberUpdatedState(onResult)
    return remember {
        {
            val dialog = FileDialog(null as Frame?, strings.chooseM3uFile, FileDialog.LOAD).apply {
                isMultipleMode = false
                // Windows filters by this pattern; other platforms use the filename filter.
                file = "*.m3u;*.m3u8;*.txt"
                setFilenameFilter { _, name ->
                    name.endsWith(".m3u", true) || name.endsWith(".m3u8", true) || name.endsWith(".txt", true)
                }
                isVisible = true
            }
            val fileName = dialog.file
            val directory = dialog.directory
            currentOnResult(
                if (fileName != null && directory != null) {
                    PickedFile(File(directory, fileName).absolutePath, fileName)
                } else {
                    null
                },
            )
        }
    }
}

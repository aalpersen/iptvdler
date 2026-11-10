package io.github.aalpersen.iptvdler.ui.platform

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import io.github.aalpersen.iptvdler.core.download.DownloadManager
import io.github.aalpersen.iptvdler.core.download.DownloadStatus
import io.github.aalpersen.iptvdler.i18n.strings
import io.github.aalpersen.iptvdler.ui.theme.IptvdlerTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Downloads and recordings that quitting the desktop app would interrupt. */
data class ActiveWork(val downloads: Int, val recordings: Int) {
    val isEmpty: Boolean get() = downloads == 0 && recordings == 0
}

/** Lets the desktop window ask before closing while work is running, and stop recordings cleanly. */
class QuitGuard(private val manager: DownloadManager, scope: CoroutineScope) {

    val activeWork: StateFlow<ActiveWork> = manager.items
        .map { items ->
            val active = items.filter { it.task.status == DownloadStatus.RUNNING || it.task.status == DownloadStatus.QUEUED }
            ActiveWork(downloads = active.count { !it.task.isRecording }, recordings = active.count { it.task.isRecording })
        }
        .stateIn(scope, SharingStarted.Eagerly, ActiveWork(0, 0))

    /** Finalizes running recordings so they're complete files, not "resume on next start". */
    suspend fun prepareToQuit() {
        manager.items.first()
            .filter { it.task.isRecording && (it.task.status == DownloadStatus.RUNNING || it.task.status == DownloadStatus.QUEUED) }
            .forEach { manager.stopRecording(it.task.id) }
    }
}

@Composable
fun QuitConfirmationDialog(work: ActiveWork, onQuit: () -> Unit, onCancel: () -> Unit) {
    IptvdlerTheme {
        AlertDialog(
            onDismissRequest = onCancel,
            title = { Text(strings.quitTitle) },
            text = {
                Text(
                    buildString {
                        if (work.downloads > 0) append(strings.quitDownloadsInProgress(work.downloads))
                        if (work.recordings > 0) {
                            if (isNotEmpty()) append("\n\n")
                            append(strings.quitRecordingsRunning(work.recordings))
                        }
                    },
                )
            },
            confirmButton = { TextButton(onClick = onQuit) { Text(strings.quit) } },
            dismissButton = { TextButton(onClick = onCancel) { Text(strings.keepRunning) } },
        )
    }
}

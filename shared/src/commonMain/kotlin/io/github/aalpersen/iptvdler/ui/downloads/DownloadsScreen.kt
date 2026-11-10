package io.github.aalpersen.iptvdler.ui.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.aalpersen.iptvdler.core.download.DownloadItem
import io.github.aalpersen.iptvdler.core.download.DownloadManager
import io.github.aalpersen.iptvdler.core.download.DownloadStatus
import io.github.aalpersen.iptvdler.core.download.RecordingNames
import io.github.aalpersen.iptvdler.core.error.UserMessages
import io.github.aalpersen.iptvdler.i18n.strings
import io.github.aalpersen.iptvdler.ui.formatBytes
import io.github.aalpersen.iptvdler.ui.formatDuration
import io.github.aalpersen.iptvdler.ui.formatEta
import io.github.aalpersen.iptvdler.ui.platform.rememberDownloadFileAction
import io.github.aalpersen.iptvdler.ui.player.PlayerIcons
import io.github.aalpersen.iptvdler.ui.screens.DetailsText
import io.github.aalpersen.iptvdler.ui.screens.IptvdlerScaffold
import io.github.aalpersen.iptvdler.ui.screens.EmptyState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

class DownloadsViewModel(private val manager: DownloadManager) : ViewModel() {
    val items: StateFlow<List<DownloadItem>?> =
        manager.items.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun pause(id: Long) = viewModelScope.launch { manager.pause(id) }
    fun stopRecording(id: Long) = viewModelScope.launch { manager.stopRecording(id) }
    fun resume(id: Long) = viewModelScope.launch { manager.resume(id) }
    fun delete(id: Long, deleteFiles: Boolean) = viewModelScope.launch { manager.delete(id, deleteFiles) }

    /** Result of the last "add subtitles" attempt, for the dialog. */
    val addSubtitleResult = MutableStateFlow<String?>(null)

    fun addSubtitle(id: Long, url: String, language: String) = viewModelScope.launch {
        addSubtitleResult.value = try {
            val added = manager.addSubtitle(id, url, language)
            strings.addedSubtitles(added.language)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            strings.addSubtitlesFailed(UserMessages.describe(e))
        }
    }
}

/** Recording red, shared by record buttons and the REC indicator. */
val RecordRed = androidx.compose.ui.graphics.Color(0xFFD32F2F)

private val sections: List<Pair<String, (DownloadItem) -> Boolean>> get() = listOf(
    strings.sectionRecording to { it.task.isRecording && it.task.status in setOf(DownloadStatus.RUNNING, DownloadStatus.QUEUED) },
    strings.sectionDownloading to { !it.task.isRecording && it.task.status == DownloadStatus.RUNNING },
    strings.sectionQueued to { !it.task.isRecording && it.task.status == DownloadStatus.QUEUED },
    strings.sectionPaused to { it.task.status == DownloadStatus.PAUSED },
    strings.sectionFailed to { it.task.status == DownloadStatus.FAILED },
    strings.sectionCompleted to { it.task.status == DownloadStatus.COMPLETED },
)

@Composable
fun DownloadsScreen(
    onBack: () -> Unit,
    onPlay: (downloadId: Long) -> Unit,
    viewModel: DownloadsViewModel = koinViewModel(),
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val fileAction = rememberDownloadFileAction()
    var pendingDelete by remember { mutableStateOf<DownloadItem?>(null) }
    var addSubtitlesTo by remember { mutableStateOf<DownloadItem?>(null) }

    IptvdlerScaffold(title = strings.downloads, onBack = onBack) { modifier ->
        val list = items
        when {
            list == null -> Unit
            list.isEmpty() -> EmptyState(
                strings.noDownloads,
                strings.noDownloadsMessage,
                modifier,
            )
            else -> LazyColumn(modifier) {
                sections.forEach { (title, belongs) ->
                    val section = list.filter(belongs)
                    if (section.isEmpty()) return@forEach
                    item(key = "header-$title") {
                        Text(
                            "$title (${section.size})",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                        )
                    }
                    items(section, key = { it.task.id }) { item ->
                        DownloadRow(
                            item = item,
                            fileActionLabel = fileAction.label,
                            onPause = { viewModel.pause(item.task.id) },
                            onStopRecording = { viewModel.stopRecording(item.task.id) },
                            onResume = { viewModel.resume(item.task.id) },
                            onPlay = { onPlay(item.task.id) },
                            onFileAction = {
                                val path = item.task.outputPath
                                fileAction.run(path, path.substringAfterLast('/').substringAfterLast('\\'))
                            },
                            onDelete = { pendingDelete = item },
                            onAddSubtitles = {
                                viewModel.addSubtitleResult.value = null
                                addSubtitlesTo = item
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    addSubtitlesTo?.let { item ->
        val result by viewModel.addSubtitleResult.collectAsStateWithLifecycle()
        AddSubtitlesDialog(
            result = result,
            onAdd = { url, language -> viewModel.addSubtitle(item.task.id, url, language) },
            onDismiss = { addSubtitlesTo = null },
        )
    }

    pendingDelete?.let { item ->
        DeleteDialog(
            item = item,
            onConfirm = { deleteFiles ->
                viewModel.delete(item.task.id, deleteFiles)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun DownloadRow(
    item: DownloadItem,
    fileActionLabel: String,
    onPause: () -> Unit,
    onStopRecording: () -> Unit,
    onResume: () -> Unit,
    onPlay: () -> Unit,
    onFileAction: () -> Unit,
    onDelete: () -> Unit,
    onAddSubtitles: () -> Unit,
) {
    val task = item.task
    val progress = item.progress
    ListItem(
        headlineContent = { Text(task.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                // Errors can be long: tapping one opens the full, copyable message.
                DetailsText(
                    text = statusLine(item),
                    details = task.error,
                    color = when {
                        task.status == DownloadStatus.FAILED -> MaterialTheme.colorScheme.error
                        task.isRecording && task.status == DownloadStatus.RUNNING -> RecordRed
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                if (!task.isRecording && task.status in setOf(DownloadStatus.RUNNING, DownloadStatus.QUEUED, DownloadStatus.PAUSED)) {
                    val fraction = progress?.fraction ?: task.fraction()
                    if (fraction != null) {
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                    } else if (task.status == DownloadStatus.RUNNING) {
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                    }
                }
                if (task.hasSeparateAudio && task.status == DownloadStatus.COMPLETED) {
                    Text(strings.audioSavedSeparately, style = MaterialTheme.typography.bodySmall)
                }
                subtitleLine(item)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (task.status) {
                    DownloadStatus.RUNNING, DownloadStatus.QUEUED ->
                        if (task.isRecording) {
                            IconButton(onClick = onStopRecording) {
                                Icon(PlayerIcons.Stop, contentDescription = strings.stopRecording, tint = RecordRed)
                            }
                        } else {
                            IconButton(onClick = onPause) { Icon(PlayerIcons.Pause, contentDescription = strings.pause) }
                        }
                    DownloadStatus.PAUSED ->
                        IconButton(onClick = onResume) { Icon(PlayerIcons.Download, contentDescription = strings.resume) }
                    DownloadStatus.FAILED ->
                        IconButton(onClick = onResume) { Icon(Icons.Filled.Refresh, contentDescription = strings.retry) }
                    DownloadStatus.COMPLETED ->
                        IconButton(onClick = onPlay) { Icon(Icons.Filled.PlayArrow, contentDescription = strings.play) }
                }
                RowMenu(
                    completed = task.status == DownloadStatus.COMPLETED,
                    fileActionLabel = fileActionLabel,
                    onFileAction = onFileAction,
                    onAddSubtitles = onAddSubtitles,
                    onDelete = onDelete,
                )
            }
        },
    )
}

@Composable
private fun RowMenu(
    completed: Boolean,
    fileActionLabel: String,
    onFileAction: () -> Unit,
    onAddSubtitles: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) { Icon(Icons.Filled.MoreVert, contentDescription = strings.downloadActions) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (completed) {
                DropdownMenuItem(text = { Text(fileActionLabel) }, onClick = { expanded = false; onFileAction() })
                DropdownMenuItem(text = { Text(strings.addSubtitlesFromUrl) }, onClick = { expanded = false; onAddSubtitles() })
            }
            DropdownMenuItem(text = { Text(strings.delete) }, onClick = { expanded = false; onDelete() })
        }
    }
}

@Composable
private fun DeleteDialog(item: DownloadItem, onConfirm: (deleteFiles: Boolean) -> Unit, onDismiss: () -> Unit) {
    val completed = item.task.status == DownloadStatus.COMPLETED
    var deleteFiles by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (completed) strings.deleteDownloadTitle else strings.cancelDownloadTitle) },
        text = {
            Column {
                Text(item.task.title)
                if (completed) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = deleteFiles, onCheckedChange = { deleteFiles = it })
                        Text(strings.alsoDeleteFile)
                    }
                } else {
                    Text(strings.partialDataRemoved, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(!completed || deleteFiles) }) { Text(strings.delete) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.keep) } },
    )
}

@Composable
private fun AddSubtitlesDialog(result: String?, onAdd: (url: String, language: String) -> Unit, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf("") }
    var language by remember { mutableStateOf("") }
    val valid = url.trim().startsWith("http://", true) || url.trim().startsWith("https://", true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.addSubtitles) },
        text = {
            Column {
                Text(strings.addSubtitlesHint, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(url, { url = it }, label = { Text(strings.subtitleUrl) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                OutlinedTextField(language, { language = it }, label = { Text(strings.languageCodeHint) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                result?.let { Text(it, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = { TextButton(enabled = valid, onClick = { onAdd(url.trim(), language.trim()) }) { Text(strings.add) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.close) } },
    )
}

private fun subtitleLine(item: DownloadItem): String? {
    if (item.subtitles.isEmpty() || item.task.status != DownloadStatus.COMPLETED) return null
    val ok = item.subtitles.filter { it.playablePath != null }.map { it.language }
    val failed = item.subtitles.filter { it.error != null }.map { it.language }
    return listOfNotNull(
        ok.takeIf { it.isNotEmpty() }?.joinToString(", ")?.let(strings::subtitlesList),
        failed.takeIf { it.isNotEmpty() }?.joinToString(", ")?.let(strings::subtitlesFailed),
    ).joinToString(" · ").ifEmpty { null }
}

private fun io.github.aalpersen.iptvdler.core.download.DownloadTask.fraction(): Float? = when {
    totalSegments > 0 -> completedSegments.toFloat() / totalSegments
    (totalBytes ?: 0) > 0 -> downloadedBytes.toFloat() / totalBytes!!
    else -> null
}

private fun recordingLine(item: DownloadItem): String {
    val task = item.task
    val length = formatDuration(((task.durationSeconds ?: 0.0) * 1000).toLong())
    val size = formatBytes(item.progress?.downloadedBytes?.takeIf { it > 0 } ?: task.downloadedBytes)
    return when (task.status) {
        DownloadStatus.RUNNING -> listOfNotNull(
            strings.recIndicator(length),
            size,
            task.stopAt?.let { strings.recordingUntil(RecordingNames.clock(it)) },
            // Missed segments or reconnecting; plain "Recording" is already said by the REC indicator.
            item.progress?.note?.takeIf { it != strings.noteRecording },
        ).joinToString(" · ")
        DownloadStatus.QUEUED -> strings.startingRecording
        DownloadStatus.COMPLETED -> listOfNotNull(strings.recordingLabel, length, formatBytes(task.totalBytes ?: task.downloadedBytes), task.qualityLabel).joinToString(" · ")
        DownloadStatus.FAILED -> strings.recordingStoppedWithError(task.error ?: strings.failedLower, length)
        DownloadStatus.PAUSED -> strings.recordingStopped(length)
    }
}

private fun statusLine(item: DownloadItem): String {
    if (item.task.isRecording) return recordingLine(item)
    val task = item.task
    val progress = item.progress
    val total = progress?.totalBytes ?: task.totalBytes ?: task.estimatedBytes
    val downloaded = progress?.downloadedBytes ?: task.downloadedBytes
    val size = if (total != null && total > 0) {
        strings.sizeOf(formatBytes(downloaded), formatBytes(total), approximate = task.totalBytes == null)
    } else {
        formatBytes(downloaded)
    }
    return when (task.status) {
        DownloadStatus.RUNNING -> listOfNotNull(
            (progress?.fraction ?: task.fraction())?.let { strings.percent((it * 100).toInt()) },
            size,
            progress?.bytesPerSecond?.takeIf { it > 0 }?.let { strings.perSecond(formatBytes(it)) },
            progress?.etaSeconds?.let { strings.timeLeft(formatEta(it)) },
            progress?.note,
        ).joinToString(" · ")
        DownloadStatus.QUEUED -> listOfNotNull(progress?.note, task.error).joinToString(" · ").ifEmpty { strings.waitingToStart }
        DownloadStatus.PAUSED -> strings.pausedWithSize(size)
        DownloadStatus.FAILED -> task.error ?: strings.failed
        DownloadStatus.COMPLETED -> listOfNotNull(formatBytes(task.totalBytes ?: downloaded), task.qualityLabel).joinToString(" · ")
    }
}

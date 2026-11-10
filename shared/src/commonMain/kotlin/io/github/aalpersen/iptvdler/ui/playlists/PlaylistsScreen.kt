package io.github.aalpersen.iptvdler.ui.playlists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aalpersen.iptvdler.core.model.Playlist
import io.github.aalpersen.iptvdler.data.update.AvailableUpdate
import io.github.aalpersen.iptvdler.i18n.strings
import io.github.aalpersen.iptvdler.ui.formatCount
import io.github.aalpersen.iptvdler.ui.formatDate
import io.github.aalpersen.iptvdler.ui.formatRelative
import io.github.aalpersen.iptvdler.ui.screens.DetailsText
import io.github.aalpersen.iptvdler.ui.screens.IptvdlerScaffold
import io.github.aalpersen.iptvdler.ui.screens.EmptyState
import org.koin.compose.viewmodel.koinViewModel

/** Which playlist dialog is open: add (playlist == null) or edit. */
private data class DialogTarget(val playlist: Playlist?)

@Composable
fun PlaylistsScreen(
    onOpenPlaylist: (Long) -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: PlaylistsViewModel = koinViewModel(),
) {
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val progress by viewModel.refreshProgress.collectAsStateWithLifecycle()
    val update by viewModel.availableUpdate.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    var dialog by remember { mutableStateOf<DialogTarget?>(null) }
    var pendingDelete by remember { mutableStateOf<Playlist?>(null) }

    IptvdlerScaffold(
        title = strings.playlists,
        actions = {
            TextButton(onClick = onOpenDownloads) { Text(strings.downloads) }
            IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = strings.settings) }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { dialog = DialogTarget(null) },
                // The FAB's label isn't exposed to accessibility services on its own.
                modifier = Modifier.semantics { contentDescription = strings.addPlaylist },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(strings.addPlaylist) },
            )
        },
    ) { modifier ->
        Column(modifier) {
            update?.let { available ->
                UpdateBanner(
                    update = available,
                    onOpen = { uriHandler.openUri(available.pageUrl) },
                    onDismiss = { viewModel.dismissUpdate(available) },
                )
            }
            val content = Modifier.fillMaxWidth().weight(1f)
            val list = playlists
            when {
                list == null -> Box(content, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                list.isEmpty() -> EmptyState(
                    title = strings.noPlaylistsTitle,
                    message = strings.noPlaylistsMessage,
                    modifier = content,
                )
                else -> LazyColumn(content) {
                    items(list, key = { it.id }) { playlist ->
                        PlaylistRow(
                            playlist = playlist,
                            nowMillis = viewModel.nowMillis(),
                            loadingCount = progress[playlist.id],
                            onOpen = { onOpenPlaylist(playlist.id) },
                            onRefresh = { viewModel.refresh(playlist.id) },
                            onEdit = { dialog = DialogTarget(playlist) },
                            onDelete = { pendingDelete = playlist },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    dialog?.let { target ->
        PlaylistDialog(
            initial = target.playlist,
            onDismiss = { dialog = null },
            onConfirm = { input ->
                val existing = target.playlist
                if (existing == null) viewModel.add(input) else viewModel.update(existing, input)
                dialog = null
            },
        )
    }

    pendingDelete?.let { playlist ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(strings.deletePlaylistTitle) },
            text = { Text(strings.deletePlaylistMessage(playlist.name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(playlist.id)
                    pendingDelete = null
                }) { Text(strings.delete) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(strings.cancel) } },
        )
    }
}

@Composable
private fun PlaylistRow(
    playlist: Playlist,
    nowMillis: Long,
    loadingCount: Int?,
    onOpen: () -> Unit,
    onRefresh: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                when {
                    loadingCount != null -> {
                        Text(strings.loadingEntries(formatCount(loadingCount)))
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                    }
                    playlist.lastRefreshedAt != null -> Text(
                        strings.channelsUpdated(
                            playlist.channelCount,
                            formatCount(playlist.channelCount),
                            formatRelative(playlist.lastRefreshedAt, nowMillis),
                        ),
                    )
                    playlist.lastError == null -> Text(strings.notLoadedYet)
                }
                accountLine(playlist, nowMillis)?.let { (text, expired) ->
                    Text(
                        text,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (expired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (loadingCount == null && playlist.lastError != null) {
                    // Cut after two lines; tapping opens the full, copyable message (not the playlist).
                    DetailsText(
                        text = strings.refreshFailed(playlist.lastError),
                        details = strings.refreshFailed(playlist.lastError),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        trailingContent = {
            PlaylistMenu(onRefresh = onRefresh, onEdit = onEdit, onDelete = onDelete, refreshEnabled = loadingCount == null)
        },
        modifier = Modifier.clickable(onClick = onOpen),
    )
}

@Composable
private fun PlaylistMenu(onRefresh: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit, refreshEnabled: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = strings.playlistActions)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(strings.refresh) },
                enabled = refreshEnabled,
                onClick = { expanded = false; onRefresh() },
            )
            DropdownMenuItem(text = { Text(strings.edit) }, onClick = { expanded = false; onEdit() })
            DropdownMenuItem(text = { Text(strings.delete) }, onClick = { expanded = false; onDelete() })
        }
    }
}

/** Xtream account status for the row, and whether it's a problem; null for M3U playlists. */
private fun accountLine(playlist: Playlist, nowMillis: Long): Pair<String, Boolean>? {
    val status = playlist.accountStatus ?: return null
    val expires = playlist.accountExpiresAt
    return when {
        !status.equals("Active", ignoreCase = true) -> strings.xtreamAccountNotActive(status) to true
        expires != null && expires < nowMillis -> strings.accountExpiredOn(formatDate(expires)) to true
        expires != null -> strings.accountActiveUntil(formatDate(expires)) to false
        else -> strings.accountActiveNoExpiry to false
    }
}

/** "Version X is available": opens the GitHub release page in the browser; nothing is downloaded by the app. */
@Composable
private fun UpdateBanner(update: AvailableUpdate, onOpen: () -> Unit, onDismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            headlineContent = { Text(strings.updateAvailable(update.version)) },
            supportingContent = { Text(strings.updateAvailableDescription) },
            trailingContent = {
                Row {
                    TextButton(onClick = onDismiss) { Text(strings.updateDismiss) }
                    TextButton(onClick = onOpen) { Text(strings.updateOpen) }
                }
            },
        )
    }
}

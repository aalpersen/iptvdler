package io.github.aalpersen.iptvdler.ui.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.aalpersen.iptvdler.core.model.Channel
import io.github.aalpersen.iptvdler.core.model.ContentType
import io.github.aalpersen.iptvdler.i18n.strings
import io.github.aalpersen.iptvdler.ui.downloads.DownloadDialog
import io.github.aalpersen.iptvdler.ui.downloads.RecordDialog
import io.github.aalpersen.iptvdler.ui.downloads.RecordRed
import io.github.aalpersen.iptvdler.ui.navigation.ChannelListMode
import io.github.aalpersen.iptvdler.ui.navigation.ChannelsRoute
import io.github.aalpersen.iptvdler.ui.player.PlayerIcons
import io.github.aalpersen.iptvdler.ui.screens.IptvdlerScaffold
import io.github.aalpersen.iptvdler.ui.screens.EmptyState
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun ChannelsScreen(
    route: ChannelsRoute,
    onPlay: (Channel) -> Unit,
    onBack: () -> Unit,
    onOpenDownloads: () -> Unit,
    viewModel: ChannelsViewModel = koinViewModel { parametersOf(route) },
) {
    val channels by viewModel.channels.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var downloadFor by remember { mutableStateOf<Channel?>(null) }
    var recordFor by remember { mutableStateOf<Channel?>(null) }

    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect(viewModel::onScrolledTo)
    }

    val title = when (route.mode) {
        ChannelListMode.ALL -> strings.allChannels
        ChannelListMode.FAVORITES -> strings.favorites
        ChannelListMode.RECENT -> strings.recentlyWatched
        ChannelListMode.GROUP -> route.group.orEmpty().ifEmpty { strings.ungrouped }
    }

    IptvdlerScaffold(title = title, onBack = onBack) { modifier ->
        Column(modifier) {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::onQueryChange,
                placeholder = { Text(strings.search) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onQueryChange("") }) {
                            Icon(Icons.Filled.Clear, contentDescription = strings.clearSearch)
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )

            val list = channels
            when {
                list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                list.isEmpty() -> EmptyState(
                    title = if (query.isNotBlank()) strings.noMatches else strings.nothingHere,
                    message = when {
                        query.isNotBlank() -> strings.noChannelsMatch(query)
                        route.mode == ChannelListMode.FAVORITES -> strings.favoritesEmpty
                        route.mode == ChannelListMode.RECENT -> strings.recentEmpty
                        else -> strings.noChannels
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(list, key = { it.id }) { channel ->
                        ChannelRow(
                            channel = channel,
                            showGroup = route.mode != ChannelListMode.GROUP,
                            onClick = {
                                viewModel.onChannelOpened(channel)
                                onPlay(channel)
                            },
                            onToggleFavorite = { viewModel.toggleFavorite(channel) },
                            onDownload = { downloadFor = channel },
                            onRecord = { recordFor = channel },
                        )
                    }
                }
            }
        }
    }

    recordFor?.let { channel ->
        RecordDialog(channel = channel, onDismiss = { recordFor = null }, onOpenDownloads = {
            recordFor = null
            onOpenDownloads()
        })
    }

    downloadFor?.let { channel ->
        DownloadDialog(channel = channel, onDismiss = { downloadFor = null }, onOpenDownloads = {
            downloadFor = null
            onOpenDownloads()
        })
    }
}

@Composable
private fun ChannelRow(
    channel: Channel,
    showGroup: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDownload: () -> Unit,
    onRecord: () -> Unit,
) {
    val details = listOfNotNull(
        channel.group.ifEmpty { strings.ungrouped }.takeIf { showGroup },
        when (channel.type) {
            ContentType.MOVIE -> strings.movie
            ContentType.SERIES -> strings.series
            ContentType.LIVE -> null
        },
    ).joinToString(" · ")

    ListItem(
        leadingContent = { ChannelLogo(channel) },
        headlineContent = { Text(channel.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = if (details.isNotEmpty()) {
            { Text(details, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        } else {
            null
        },
        trailingContent = {
            Row {
                // VOD can be downloaded; live channels can be recorded; a series opens its episodes.
                when {
                    channel.seriesId != null -> Unit
                    channel.type == ContentType.LIVE -> IconButton(onClick = onRecord) {
                        Icon(PlayerIcons.Record, contentDescription = strings.recordAction, tint = RecordRed)
                    }
                    else -> IconButton(onClick = onDownload) {
                        Icon(PlayerIcons.Download, contentDescription = strings.download, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                FavoriteButton(channel, onToggleFavorite)
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun FavoriteButton(channel: Channel, onToggleFavorite: () -> Unit) {
    IconButton(onClick = onToggleFavorite) {
        Icon(
            if (channel.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            contentDescription = if (channel.isFavorite) strings.removeFromFavorites else strings.addToFavorites,
            tint = if (channel.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ChannelLogo(channel: Channel) {
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        var logoLoaded by remember(channel.logo) { mutableStateOf(false) }
        // The initial is shown until (and unless) the logo loads.
        if (!logoLoaded) {
            Text(
                channel.name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (channel.logo != null) {
            AsyncImage(
                model = channel.logo,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                onSuccess = { logoLoaded = true },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

package io.github.aalpersen.iptvdler.ui.channels

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.aalpersen.iptvdler.core.model.ChannelGroup
import io.github.aalpersen.iptvdler.core.model.ContentType
import io.github.aalpersen.iptvdler.core.model.Playlist
import io.github.aalpersen.iptvdler.data.playlist.PlaylistRepository
import io.github.aalpersen.iptvdler.i18n.strings
import io.github.aalpersen.iptvdler.ui.formatCount
import io.github.aalpersen.iptvdler.ui.navigation.ChannelListMode
import io.github.aalpersen.iptvdler.ui.navigation.ChannelsRoute
import io.github.aalpersen.iptvdler.ui.navigation.GroupsRoute
import io.github.aalpersen.iptvdler.ui.screens.IptvdlerScaffold
import io.github.aalpersen.iptvdler.ui.screens.EmptyState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalCoroutinesApi::class)
class GroupsViewModel(route: GroupsRoute, repository: PlaylistRepository) : ViewModel() {
    val playlistId = route.playlistId

    val playlist: StateFlow<Playlist?> = repository.observePlaylist(playlistId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _typeFilter = MutableStateFlow<ContentType?>(null)
    val typeFilter: StateFlow<ContentType?> = _typeFilter.asStateFlow()

    /** null while loading. */
    val groups: StateFlow<List<ChannelGroup>?> = _typeFilter
        .flatMapLatest { repository.observeGroups(playlistId, it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setTypeFilter(type: ContentType?) {
        _typeFilter.value = type
    }
}

@Composable
fun GroupsScreen(
    route: GroupsRoute,
    onOpenChannels: (ChannelsRoute) -> Unit,
    onBack: () -> Unit,
    viewModel: GroupsViewModel = koinViewModel { parametersOf(route) },
) {
    val playlist by viewModel.playlist.collectAsStateWithLifecycle()
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val typeFilter by viewModel.typeFilter.collectAsStateWithLifecycle()
    val contentType = typeFilter?.name

    fun open(mode: ChannelListMode, group: String? = null) =
        onOpenChannels(ChannelsRoute(viewModel.playlistId, mode, group, contentType))

    IptvdlerScaffold(
        title = playlist?.name ?: strings.groups,
        onBack = onBack,
        actions = {
            IconButton(onClick = { open(ChannelListMode.ALL) }) {
                Icon(Icons.Filled.Search, contentDescription = strings.searchChannels)
            }
        },
    ) { modifier ->
        LazyColumn(modifier) {
            item {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TypeChip(strings.all, typeFilter == null) { viewModel.setTypeFilter(null) }
                    TypeChip(strings.live, typeFilter == ContentType.LIVE) { viewModel.setTypeFilter(ContentType.LIVE) }
                    TypeChip(strings.movies, typeFilter == ContentType.MOVIE) { viewModel.setTypeFilter(ContentType.MOVIE) }
                    TypeChip(strings.seriesPlural, typeFilter == ContentType.SERIES) { viewModel.setTypeFilter(ContentType.SERIES) }
                }
            }
            item { ShortcutRow(strings.favorites, Icons.Filled.Favorite) { open(ChannelListMode.FAVORITES) } }
            item { ShortcutRow(strings.recentlyWatched, Icons.Filled.DateRange) { open(ChannelListMode.RECENT) } }
            item {
                ShortcutRow(strings.allChannels, Icons.AutoMirrored.Filled.List) { open(ChannelListMode.ALL) }
                HorizontalDivider()
            }

            val list = groups
            when {
                list == null -> item {
                    Box(Modifier.padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
                list.isEmpty() -> item {
                    EmptyState(
                        title = strings.nothingHere,
                        message = if (typeFilter == null) {
                            strings.playlistHasNoChannels
                        } else {
                            strings.noChannelsOfType
                        },
                    )
                }
                else -> items(list, key = { it.name }) { group ->
                    ListItem(
                        headlineContent = {
                            Text(group.name.ifEmpty { strings.ungrouped }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        trailingContent = { Text(formatCount(group.channelCount)) },
                        modifier = Modifier.clickable { open(ChannelListMode.GROUP, group.name) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TypeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

@Composable
private fun ShortcutRow(label: String, icon: ImageVector, onClick: () -> Unit) {
    ListItem(
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(label) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

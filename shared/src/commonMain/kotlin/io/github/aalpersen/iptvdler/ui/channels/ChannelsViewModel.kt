package io.github.aalpersen.iptvdler.ui.channels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.aalpersen.iptvdler.core.model.Channel
import io.github.aalpersen.iptvdler.core.model.ContentType
import io.github.aalpersen.iptvdler.core.text.SearchText
import io.github.aalpersen.iptvdler.data.playlist.ChannelFilter
import io.github.aalpersen.iptvdler.data.playlist.PlaylistRepository
import io.github.aalpersen.iptvdler.ui.navigation.ChannelListMode
import io.github.aalpersen.iptvdler.ui.navigation.ChannelsRoute
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class ChannelsViewModel(
    val route: ChannelsRoute,
    private val repository: PlaylistRepository,
) : ViewModel() {

    private val contentType = route.contentType?.let { name -> ContentType.entries.firstOrNull { it.name == name } }

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** Rows requested from the database; grows as the user scrolls so huge lists stay cheap. */
    private val limit = MutableStateFlow(PAGE_SIZE)

    /** null while loading. */
    val channels: StateFlow<List<Channel>?> = combine(
        _query.debounce { if (it.isEmpty()) 0L else SEARCH_DEBOUNCE_MILLIS }.distinctUntilChanged(),
        limit,
    ) { query, limit -> query to limit }
        .flatMapLatest { (query, limit) -> observe(query, limit) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun observe(query: String, limit: Long) = when (route.mode) {
        ChannelListMode.RECENT -> repository.observeRecent(route.playlistId, limit).map { list ->
            if (query.isBlank()) list else SearchText.fold(query.trim()).let { q -> list.filter { SearchText.fold(it.name).contains(q) } }
        }
        else -> repository.observeChannels(
            ChannelFilter(
                playlistId = route.playlistId,
                group = if (route.mode == ChannelListMode.GROUP) route.group.orEmpty() else null,
                contentType = contentType,
                search = query,
                favoritesOnly = route.mode == ChannelListMode.FAVORITES,
            ),
            limit,
        )
    }

    fun onQueryChange(value: String) {
        _query.value = value
        limit.value = PAGE_SIZE
    }

    /** Called with the last visible row index; loads the next page near the end of the list. */
    fun onScrolledTo(lastVisibleIndex: Int) {
        val loaded = channels.value?.size ?: return
        if (loaded >= limit.value && lastVisibleIndex >= loaded - LOAD_AHEAD) {
            limit.value += PAGE_SIZE
        }
    }

    fun toggleFavorite(channel: Channel) {
        viewModelScope.launch { repository.setFavorite(channel.playlistId, channel.url, !channel.isFavorite) }
    }

    fun onChannelOpened(channel: Channel) {
        viewModelScope.launch { repository.markWatched(channel.playlistId, channel.url) }
    }

    private companion object {
        const val PAGE_SIZE = 300L
        const val LOAD_AHEAD = 50
        const val SEARCH_DEBOUNCE_MILLIS = 250L
    }
}

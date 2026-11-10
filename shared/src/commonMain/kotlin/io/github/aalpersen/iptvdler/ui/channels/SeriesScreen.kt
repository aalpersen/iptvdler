package io.github.aalpersen.iptvdler.ui.channels

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import io.github.aalpersen.iptvdler.core.error.UserMessages
import io.github.aalpersen.iptvdler.core.model.Channel
import io.github.aalpersen.iptvdler.data.playlist.PlaylistRepository
import io.github.aalpersen.iptvdler.data.xtream.XtreamClient
import io.github.aalpersen.iptvdler.data.xtream.XtreamEpisode
import io.github.aalpersen.iptvdler.data.xtream.XtreamSeriesInfo
import io.github.aalpersen.iptvdler.data.xtream.xtreamCredentials
import io.github.aalpersen.iptvdler.i18n.strings
import io.github.aalpersen.iptvdler.ui.downloads.DownloadDialog
import io.github.aalpersen.iptvdler.ui.formatEta
import io.github.aalpersen.iptvdler.ui.navigation.PlayerRoute
import io.github.aalpersen.iptvdler.ui.navigation.SeriesRoute
import io.github.aalpersen.iptvdler.ui.player.PlayerIcons
import io.github.aalpersen.iptvdler.ui.player.streamChannel
import io.github.aalpersen.iptvdler.ui.screens.IptvdlerScaffold
import io.github.aalpersen.iptvdler.ui.screens.EmptyState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

sealed interface SeriesState {
    data object Loading : SeriesState
    data class Error(val message: String) : SeriesState
    data class Loaded(val info: XtreamSeriesInfo, val season: Int?) : SeriesState {
        val episodes: List<XtreamEpisode> get() = info.episodes.filter { season == null || it.season == season }
    }
}

/** Loads an Xtream series' episodes (not stored: they're fetched when the list opens). */
class SeriesViewModel(
    val route: SeriesRoute,
    private val repository: PlaylistRepository,
    private val xtream: XtreamClient,
) : ViewModel() {

    private val _state = MutableStateFlow<SeriesState>(SeriesState.Loading)
    val state: StateFlow<SeriesState> = _state.asStateFlow()

    private var episodeUrl: (XtreamEpisode) -> String = { "" }

    init {
        load()
    }

    fun load() {
        _state.value = SeriesState.Loading
        viewModelScope.launch {
            _state.value = try {
                val playlist = repository.observePlaylist(route.playlistId).filterNotNull().first()
                val credentials = playlist.xtreamCredentials() ?: error("Not an Xtream playlist")
                episodeUrl = { credentials.episodeUrl(it.id, it.containerExtension) }
                val info = xtream.seriesInfo(credentials, route.seriesId)
                SeriesState.Loaded(info, info.seasons.firstOrNull())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SeriesState.Error(strings.episodesLoadFailed(UserMessages.describe(e)))
            }
        }
    }

    fun selectSeason(season: Int) = _state.update { if (it is SeriesState.Loaded) it.copy(season = season) else it }

    /** Player route for an episode. */
    fun playRoute(episode: XtreamEpisode) =
        PlayerRoute(playlistId = route.playlistId, url = episodeUrl(episode), title = episodeTitle(episode))

    /** The episode as a channel, for the download dialog. */
    fun asChannel(episode: XtreamEpisode): Channel = streamChannel(playRoute(episode))

    /** "Show S01E03 Title" unless the panel's title already says which episode it is. */
    fun episodeTitle(episode: XtreamEpisode): String {
        val code = episodeCode(episode)
        return if (episode.title.contains(code, ignoreCase = true)) episode.title
        else listOf(route.name, code, episode.title).filter { it.isNotBlank() }.joinToString(" ")
    }
}

internal fun episodeCode(episode: XtreamEpisode) =
    "S${episode.season.toString().padStart(2, '0')}E${episode.episode.toString().padStart(2, '0')}"

@Composable
fun SeriesScreen(
    route: SeriesRoute,
    onPlay: (PlayerRoute) -> Unit,
    onBack: () -> Unit,
    onOpenDownloads: () -> Unit,
    viewModel: SeriesViewModel = koinViewModel { parametersOf(route) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var downloadFor by remember { mutableStateOf<Channel?>(null) }

    IptvdlerScaffold(title = route.name, onBack = onBack) { modifier ->
        when (val s = state) {
            SeriesState.Loading -> Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            is SeriesState.Error -> Column(
                modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            ) {
                Text(s.message, color = MaterialTheme.colorScheme.error)
                Button(onClick = viewModel::load) { Text(strings.retry) }
            }
            is SeriesState.Loaded -> if (s.info.episodes.isEmpty()) {
                EmptyState(strings.nothingHere, strings.noEpisodes, modifier)
            } else {
                LazyColumn(modifier) {
                    if (s.info.seasons.size > 1) {
                        item {
                            Row(
                                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                s.info.seasons.forEach { season ->
                                    FilterChip(
                                        selected = s.season == season,
                                        onClick = { viewModel.selectSeason(season) },
                                        label = { Text(strings.season(season)) },
                                    )
                                }
                            }
                        }
                    }
                    items(s.episodes, key = { it.id }) { episode ->
                        EpisodeRow(
                            episode = episode,
                            onPlay = { onPlay(viewModel.playRoute(episode)) },
                            onDownload = { downloadFor = viewModel.asChannel(episode) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    downloadFor?.let { channel ->
        DownloadDialog(channel = channel, onDismiss = { downloadFor = null }, onOpenDownloads = {
            downloadFor = null
            onOpenDownloads()
        })
    }
}

@Composable
private fun EpisodeRow(episode: XtreamEpisode, onPlay: () -> Unit, onDownload: () -> Unit) {
    ListItem(
        overlineContent = { Text(episodeCode(episode)) },
        headlineContent = { Text(episode.title.ifBlank { episodeCode(episode) }, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = episode.durationSeconds?.takeIf { it > 0 }?.let { { Text(formatEta(it)) } },
        trailingContent = {
            IconButton(onClick = onDownload) {
                Icon(PlayerIcons.Download, contentDescription = strings.download, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        modifier = Modifier.clickable(onClick = onPlay),
    )
}

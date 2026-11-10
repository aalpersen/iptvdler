package io.github.aalpersen.iptvdler.ui.player

import io.github.aalpersen.iptvdler.core.model.ContentType
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.aalpersen.iptvdler.core.model.Channel
import io.github.aalpersen.iptvdler.core.player.PlayableMedia
import io.github.aalpersen.iptvdler.core.player.SubtitleFile
import io.github.aalpersen.iptvdler.core.player.VideoPlayer
import io.github.aalpersen.iptvdler.core.player.VideoPlayerFactory
import io.github.aalpersen.iptvdler.data.download.DownloadRepository
import io.github.aalpersen.iptvdler.data.hls.HlsClient
import io.github.aalpersen.iptvdler.data.hls.StreamProbe
import io.github.aalpersen.iptvdler.data.playlist.PlaylistRepository
import io.github.aalpersen.iptvdler.ui.navigation.PlayerRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Owns the platform player so playback survives recomposition and configuration changes.
 * Plays either a playlist channel or (offline) a finished download.
 */
class PlayerViewModel(
    route: PlayerRoute,
    repository: PlaylistRepository,
    playerFactory: VideoPlayerFactory,
    private val hlsClient: HlsClient,
    downloads: DownloadRepository,
) : ViewModel() {

    val player: VideoPlayer = playerFactory.create()

    /** The channel being played; null for offline playback. */
    val channel: StateFlow<Channel?> =
        when {
            route.channelId >= 0 -> repository.observeChannel(route.channelId)
            route.url.isNotEmpty() -> flowOf(streamChannel(route))
            else -> flowOf(null)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title.asStateFlow()

    init {
        viewModelScope.launch {
            if (route.downloadId >= 0) {
                val task = downloads.task(route.downloadId) ?: return@launch
                _title.value = task.title
                val subtitles = downloads.subtitles(task.id).mapNotNull { sub ->
                    sub.playablePath?.let { SubtitleFile(it, sub.language, sub.name) }
                }
                // Local file path; both players accept plain paths.
                player.load(
                    PlayableMedia(
                        url = task.outputPath,
                        title = task.title,
                        isHls = false,
                        subtitles = subtitles,
                        externalAudioPath = task.audioOutputPath,
                    ),
                )
                return@launch
            }
            val channel = channel.filterNotNull().first()
            _title.value = channel.name
            player.load(
                PlayableMedia(
                    url = channel.url,
                    title = channel.name,
                    headers = channel.headers,
                    isHls = if (hasKnownExtension(channel.url)) null else probeIsHls(channel),
                ),
            )
        }
    }

    /** IPTV URLs often have no extension; peek at the response to tell HLS from a raw stream. */
    private suspend fun probeIsHls(channel: Channel): Boolean? =
        try {
            hlsClient.probe(channel.url, channel.headers) is StreamProbe.Hls
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null // let the player try and report its own error
        }

    override fun onCleared() {
        player.release()
    }
}

private val knownExtensions = setOf(
    "m3u8", "m3u", "ts", "mp4", "m4v", "mkv", "avi", "mov", "webm", "flv", "mpd", "mp3", "aac", "m4a",
)

internal fun hasKnownExtension(url: String): Boolean =
    url.substringBefore('?').substringBefore('#').substringAfterLast('/').substringAfterLast('.', "").lowercase() in knownExtensions

/** A stream that isn't stored as a channel (an Xtream episode), shaped like one for playback and download. */
internal fun streamChannel(route: PlayerRoute) = Channel(
    id = -1,
    playlistId = route.playlistId,
    name = route.title,
    url = route.url,
    logo = null,
    group = "",
    tvgId = null,
    type = ContentType.SERIES,
    headers = emptyMap(),
    catchup = null,
    catchupSource = null,
    durationSeconds = null,
    isFavorite = false,
)

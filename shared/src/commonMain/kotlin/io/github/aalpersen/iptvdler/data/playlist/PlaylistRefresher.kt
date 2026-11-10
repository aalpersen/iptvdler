package io.github.aalpersen.iptvdler.data.playlist

import io.github.aalpersen.iptvdler.core.error.UserMessages
import io.github.aalpersen.iptvdler.core.m3u.ContentClassifier
import io.github.aalpersen.iptvdler.core.m3u.M3uEntry
import io.github.aalpersen.iptvdler.core.m3u.M3uParser
import io.github.aalpersen.iptvdler.core.model.ContentType
import io.github.aalpersen.iptvdler.core.model.EpochClock
import io.github.aalpersen.iptvdler.core.model.PlaylistSourceType
import io.github.aalpersen.iptvdler.core.text.SearchText
import io.github.aalpersen.iptvdler.data.db.Channel_staging
import io.github.aalpersen.iptvdler.data.db.IptvdlerDatabase
import io.github.aalpersen.iptvdler.data.db.Playlist as PlaylistRow
import io.github.aalpersen.iptvdler.data.xtream.XtreamAuthException
import io.github.aalpersen.iptvdler.data.xtream.XtreamClient
import io.github.aalpersen.iptvdler.data.xtream.XtreamCredentials
import io.github.aalpersen.iptvdler.data.xtream.XtreamFormatException
import io.github.aalpersen.iptvdler.data.xtream.XtreamStream
import io.github.aalpersen.iptvdler.data.xtream.withoutCredentials
import io.github.aalpersen.iptvdler.i18n.strings
import io.github.aalpersen.iptvdler.platform.AppLog
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import io.ktor.utils.io.LineEnding
import io.ktor.utils.io.readLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.buffer
import okio.use

sealed interface RefreshResult {
    data class Success(val channelCount: Int) : RefreshResult
    data class Failed(val message: String) : RefreshResult
}

class PlaylistRefreshException(message: String) : Exception(message)

/**
 * Downloads/reads a playlist and replaces its channels.
 *
 * Entries are parsed as a stream and written in batches to a staging table, then swapped in
 * with a single transaction, so a failed or cancelled refresh leaves the previous list intact.
 */
class PlaylistRefresher(
    private val database: IptvdlerDatabase,
    private val httpClient: HttpClient,
    private val localFiles: LocalFileSource,
    private val ioDispatcher: CoroutineDispatcher,
    private val scope: CoroutineScope,
    private val clock: EpochClock,
    private val xtream: XtreamClient = XtreamClient(httpClient),
) {
    private val _progress = MutableStateFlow<Map<Long, Int>>(emptyMap())

    /** Playlists currently refreshing, mapped to the number of entries read so far. */
    val progress: StateFlow<Map<Long, Int>> = _progress.asStateFlow()

    private val jobs = MutableStateFlow<Map<Long, Job>>(emptyMap())
    private var autoRefreshJob: Job? = null

    /** Starts a background refresh unless one is already running for this playlist. */
    fun refresh(playlistId: Long): Job? {
        val job = scope.launch(start = CoroutineStart.LAZY) { refreshNow(playlistId) }
        var claimed = false
        jobs.update { current ->
            claimed = current[playlistId]?.isCompleted != false
            if (claimed) current + (playlistId to job) else current
        }
        if (!claimed) {
            job.cancel()
            return null
        }
        job.invokeOnCompletion { jobs.update { if (it[playlistId] === job) it - playlistId else it } }
        job.start()
        return job
    }

    suspend fun cancel(playlistId: Long) {
        jobs.value[playlistId]?.cancelAndJoin()
    }

    /** Checks every minute for playlists whose refresh interval has elapsed. Safe to call more than once. */
    fun startAutoRefresh() {
        if (autoRefreshJob != null) return
        autoRefreshJob = scope.launch {
            while (isActive) {
                refreshDuePlaylists()
                delay(AUTO_REFRESH_CHECK_MILLIS)
            }
        }
    }

    /** Starts refreshes for playlists whose interval has elapsed; returns the started jobs. */
    internal suspend fun refreshDuePlaylists(): List<Job> {
        val now = clock.nowMillis()
        val due = withContext(ioDispatcher) {
            database.playlistQueries.selectWithAutoRefresh().executeAsList()
        }.filter { playlist ->
            val interval = playlist.refresh_interval_minutes
            now - (playlist.last_attempt_at ?: 0L) >= interval * 60_000L
        }
        return due.mapNotNull { refresh(it.id) }
    }

    suspend fun refreshNow(playlistId: Long): RefreshResult = withContext(ioDispatcher) {
        val playlist = database.playlistQueries.selectById(playlistId).executeAsOneOrNull()
            ?: return@withContext RefreshResult.Failed(strings.playlistNotFound)
        val channels = database.channelQueries

        channels.clearStaging(playlistId)
        _progress.update { it + (playlistId to 0) }
        try {
            val seen = HashSet<String>()
            val batch = ArrayList<Channel_staging>(BATCH_SIZE)
            var count = 0

            fun flush() {
                if (batch.isEmpty()) return
                channels.transaction { batch.forEach(channels::insertStaging) }
                batch.clear()
                _progress.update { it + (playlistId to count) }
            }

            /** Queues one entry; [row] gets its position in the list. */
            fun add(dedupeKey: String, row: (index: Long) -> Channel_staging) {
                // Exact duplicates (same URL, title and group) are common in merged lists.
                if (!seen.add(dedupeKey)) return
                batch += row(count++.toLong())
                if (batch.size >= BATCH_SIZE) flush()
            }

            val epgUrl = if (playlist.source_type == PlaylistSourceType.XTREAM.name) {
                loadXtream(playlist, ::add)
            } else {
                val parser = M3uParser()
                readLines(playlist) { line ->
                    val entry = parser.feedLine(line) ?: return@readLines
                    add(entry.url + '\u0000' + entry.title + '\u0000' + entry.group.orEmpty()) { entry.toStaging(playlistId, it.toInt()) }
                }
                if (!parser.looksLikeM3u) throw PlaylistRefreshException(strings.notAnM3u)
                parser.header?.epgUrls?.firstOrNull()
            }
            flush()
            if (count == 0) throw PlaylistRefreshException(strings.playlistHasNoEntries)

            database.transaction {
                channels.deleteByPlaylist(playlistId)
                channels.publishStaging(playlistId)
                channels.clearStaging(playlistId)
                database.playlistQueries.markRefreshed(
                    refreshedAt = clock.nowMillis(),
                    channelCount = count.toLong(),
                    epgUrl = epgUrl,
                    id = playlistId,
                )
            }
            RefreshResult.Success(count)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { channels.clearStaging(playlistId) }
            throw e
        } catch (e: Exception) {
            AppLog.warn("Refresh of playlist $playlistId (${playlist.source_type} ${withoutCredentials(playlist.source)}) failed", e)
            val message = e.toUserMessage().let { text ->
                // An http:// playlist ending in a TLS error was redirected to https: say where to.
                if (UserMessages.describe(e) != UserMessages.NOT_TLS) text
                else redirectTarget(playlist)?.let { "$text ${strings.redirectedTo(it)}" } ?: text
            }
            channels.clearStaging(playlistId)
            database.playlistQueries.markFailed(error = message, attemptedAt = clock.nowMillis(), id = playlistId)
            RefreshResult.Failed(message)
        } finally {
            _progress.update { it - playlistId }
        }
    }

    /** Where an http:// playlist address redirects to (not followed), or null. Never throws. */
    private suspend fun redirectTarget(playlist: PlaylistRow): String? {
        val url = when (playlist.source_type) {
            PlaylistSourceType.URL.name -> playlist.source
            PlaylistSourceType.XTREAM.name ->
                XtreamCredentials(playlist.source, playlist.xtream_username.orEmpty(), playlist.xtream_password.orEmpty()).apiUrl()
            else -> return null
        }
        if (!url.startsWith("http://", ignoreCase = true)) return null
        return try {
            httpClient.config { followRedirects = false }.use { client ->
                client.prepareGet(url).execute { response ->
                    response.headers[HttpHeaders.Location]?.takeIf { response.status.value in 300..399 }
                }
            }?.let(::withoutCredentials)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Loads an Xtream account's live channels, movies and series (grouped by category) through
     * `player_api.php`. The login and live list must work; a failing movie or series list is skipped
     * so one broken panel endpoint doesn't hide everything else. Returns the account's XMLTV URL.
     */
    private suspend fun loadXtream(playlist: PlaylistRow, queue: (String, (Long) -> Channel_staging) -> Unit): String {
        val c = XtreamCredentials(playlist.source, playlist.xtream_username.orEmpty(), playlist.xtream_password.orEmpty())
        var entries = 0
        fun add(key: String, row: (Long) -> Channel_staging) {
            entries++
            queue(key, row)
        }
        val account = xtream.account(c)
        database.playlistQueries.markAccount(status = account.status, expiresAt = account.expiresAt, id = playlist.id)

        fun row(index: Long, stream: XtreamStream, url: String, group: String, type: ContentType, seriesId: String? = null) = Channel_staging(
            playlist_id = playlist.id,
            sort_index = index,
            name = stream.name,
            url = url,
            logo = stream.icon,
            group_title = group,
            tvg_id = stream.epgChannelId,
            tvg_name = null,
            content_type = type.name,
            http_headers = null,
            catchup = null,
            catchup_source = null,
            duration_seconds = null,
            search_name = SearchText.fold(stream.name),
            series_id = seriesId,
        )

        val liveGroups = xtream.liveCategories(c).associate { it.id to it.name }
        xtream.liveStreams(c).forEach { s ->
            val url = c.liveUrl(s.id, account.liveExtension)
            add(url) { row(it, s, url, liveGroups[s.categoryId].orEmpty(), ContentType.LIVE) }
        }
        var firstError: Exception? = null
        suspend fun optional(block: suspend () -> Unit) = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            firstError = firstError ?: e
        }
        optional {
            val groups = xtream.vodCategories(c).associate { it.id to it.name }
            xtream.vodStreams(c).forEach { s ->
                val url = c.movieUrl(s.id, s.containerExtension ?: "mp4")
                add(url) { row(it, s, url, groups[s.categoryId].orEmpty(), ContentType.MOVIE) }
            }
        }
        optional {
            val groups = xtream.seriesCategories(c).associate { it.id to it.name }
            xtream.series(c).forEach { s ->
                // Not playable: opens the episode list. Keyed without the password so favorites survive a change.
                val url = XtreamCredentials.seriesKey(s.id)
                add(url) { row(it, s, url, groups[s.categoryId].orEmpty(), ContentType.SERIES, seriesId = s.id) }
            }
        }
        firstError?.let { if (entries == 0) throw it }
        return "${c.server}/xmltv.php?username=${c.username.encodeURLParameter()}&password=${c.password.encodeURLParameter()}"
    }

    private suspend fun readLines(playlist: PlaylistRow, onLine: suspend (String) -> Unit) {
        when (PlaylistSourceType.valueOf(playlist.source_type)) {
            PlaylistSourceType.URL -> httpClient.prepareGet(playlist.source).execute { response ->
                if (!response.status.isSuccess()) {
                    throw PlaylistRefreshException(UserMessages.httpStatus(response.status.value))
                }
                val body = response.bodyAsChannel()
                while (true) {
                    val line = body.readLine(LineEnding.Lenient) ?: break
                    onLine(line)
                }
            }
            PlaylistSourceType.FILE -> localFiles.open(playlist.source).buffer().use { source ->
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    onLine(line)
                }
            }
            PlaylistSourceType.XTREAM -> error("Xtream playlists are loaded through the API")
        }
    }

    private fun M3uEntry.toStaging(playlistId: Long, index: Int) = Channel_staging(
        playlist_id = playlistId,
        sort_index = index.toLong(),
        name = title,
        url = url,
        logo = tvgLogo,
        group_title = group.orEmpty(),
        tvg_id = tvgId,
        tvg_name = tvgName,
        content_type = ContentClassifier.classify(url, group, title).name,
        http_headers = HeaderCodec.encode(headers),
        catchup = catchup,
        catchup_source = catchupSource,
        duration_seconds = durationSeconds?.takeIf { it > 0 },
        search_name = SearchText.fold(title),
        series_id = null,
    )

    private fun Exception.toUserMessage(): String = when (this) {
        is PlaylistRefreshException, is XtreamAuthException, is XtreamFormatException -> message.orEmpty()
        else -> strings.readPlaylistFailed(UserMessages.describe(this))
    }

    private companion object {
        const val BATCH_SIZE = 500
        const val AUTO_REFRESH_CHECK_MILLIS = 60_000L
    }
}

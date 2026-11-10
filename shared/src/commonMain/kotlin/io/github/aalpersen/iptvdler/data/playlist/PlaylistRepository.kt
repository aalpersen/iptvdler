package io.github.aalpersen.iptvdler.data.playlist

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import io.github.aalpersen.iptvdler.core.model.Channel
import io.github.aalpersen.iptvdler.core.model.ChannelGroup
import io.github.aalpersen.iptvdler.core.model.ContentType
import io.github.aalpersen.iptvdler.core.model.EpochClock
import io.github.aalpersen.iptvdler.core.model.Playlist
import io.github.aalpersen.iptvdler.core.model.PlaylistSourceType
import io.github.aalpersen.iptvdler.core.text.SearchText
import io.github.aalpersen.iptvdler.data.db.IptvdlerDatabase
import io.github.aalpersen.iptvdler.data.db.Playlist as PlaylistRow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

data class PlaylistInput(
    val name: String,
    val source: String,
    val sourceType: PlaylistSourceType,
    val refreshIntervalMinutes: Long?,
    /** Xtream login ([PlaylistSourceType.XTREAM] only; [source] is then the server URL). */
    val xtreamUsername: String? = null,
    val xtreamPassword: String? = null,
)

data class ChannelFilter(
    val playlistId: Long,
    /** null = all groups; "" = ungrouped channels. */
    val group: String? = null,
    val contentType: ContentType? = null,
    val search: String = "",
    val favoritesOnly: Boolean = false,
)

class PlaylistRepository(
    private val database: IptvdlerDatabase,
    private val ioDispatcher: CoroutineDispatcher,
    private val clock: EpochClock,
) {
    private val playlistQueries get() = database.playlistQueries
    private val channelQueries get() = database.channelQueries
    private val libraryQueries get() = database.libraryQueries

    fun observePlaylists(): Flow<List<Playlist>> =
        playlistQueries.selectAll().asFlow().mapToList(ioDispatcher).map { rows -> rows.map { it.toModel() } }

    fun observePlaylist(id: Long): Flow<Playlist?> =
        playlistQueries.selectById(id).asFlow().mapToOneOrNull(ioDispatcher).map { it?.toModel() }

    suspend fun addPlaylist(input: PlaylistInput): Long = withContext(ioDispatcher) {
        playlistQueries.transactionWithResult {
            playlistQueries.insert(
                name = input.name.trim(),
                source = input.source.trim(),
                source_type = input.sourceType.name,
                refresh_interval_minutes = input.refreshIntervalMinutes,
                created_at = clock.nowMillis(),
                xtream_username = input.xtreamUsername?.trim(),
                xtream_password = input.xtreamPassword,
            )
            playlistQueries.lastInsertRowId().executeAsOne()
        }
    }

    suspend fun updatePlaylist(id: Long, input: PlaylistInput) = withContext(ioDispatcher) {
        playlistQueries.update(
            name = input.name.trim(),
            source = input.source.trim(),
            sourceType = input.sourceType.name,
            refreshIntervalMinutes = input.refreshIntervalMinutes,
            xtreamUsername = input.xtreamUsername?.trim(),
            xtreamPassword = input.xtreamPassword,
            id = id,
        )
    }

    /** Callers must cancel any running refresh of this playlist first (see [PlaylistRefresher.cancel]). */
    suspend fun deletePlaylist(id: Long) = withContext(ioDispatcher) {
        database.transaction {
            channelQueries.clearStaging(id)
            channelQueries.deleteByPlaylist(id)
            libraryQueries.deleteFavoritesByPlaylist(id)
            libraryQueries.deleteRecentByPlaylist(id)
            playlistQueries.deleteById(id)
        }
    }

    fun observeGroups(playlistId: Long, contentType: ContentType? = null): Flow<List<ChannelGroup>> =
        channelQueries.selectGroups(playlistId, contentType?.name) { name, count -> ChannelGroup(name, count) }
            .asFlow()
            .mapToList(ioDispatcher)

    fun observeChannels(filter: ChannelFilter, limit: Long): Flow<List<Channel>> =
        channelQueries.selectChannels(
            playlistId = filter.playlistId,
            groupTitle = filter.group,
            contentType = filter.contentType?.name,
            favoritesOnly = if (filter.favoritesOnly) 1L else 0L,
            namePattern = "%" + escapeLike(SearchText.fold(filter.search.trim())) + "%",
            limit = limit,
            mapper = ::toChannel,
        ).asFlow().mapToList(ioDispatcher)

    fun observeRecent(playlistId: Long, limit: Long): Flow<List<Channel>> =
        channelQueries.selectRecent(playlistId, limit, ::toChannel).asFlow().mapToList(ioDispatcher)

    fun observeChannel(id: Long): Flow<Channel?> =
        channelQueries.selectById(id, ::toChannel).asFlow().mapToOneOrNull(ioDispatcher)

    suspend fun setFavorite(playlistId: Long, channelUrl: String, favorite: Boolean) = withContext(ioDispatcher) {
        if (favorite) {
            libraryQueries.addFavorite(playlistId, channelUrl, clock.nowMillis())
        } else {
            libraryQueries.removeFavorite(playlistId, channelUrl)
        }
    }

    suspend fun markWatched(playlistId: Long, channelUrl: String) = withContext(ioDispatcher) {
        libraryQueries.transaction {
            libraryQueries.markWatched(playlistId, channelUrl, clock.nowMillis())
            libraryQueries.trimRecent(playlistId, MAX_RECENT)
        }
    }

    /**
     * Recomputes [SearchText.fold] for existing channels (after the v4 migration only ASCII was
     * lowercased). Pages through the table so huge playlists don't load at once. Returns rows fixed.
     */
    suspend fun backfillSearchNames(): Int = withContext(ioDispatcher) {
        var lastId = 0L
        var fixed = 0
        while (true) {
            val page = channelQueries.selectSearchNamesPage(lastId, BACKFILL_PAGE).executeAsList()
            if (page.isEmpty()) break
            channelQueries.transaction {
                page.forEach { row ->
                    val folded = SearchText.fold(row.name)
                    if (folded != row.search_name) {
                        channelQueries.updateSearchName(folded, row.id)
                        fixed++
                    }
                }
            }
            lastId = page.last().id
        }
        fixed
    }

    private companion object {
        const val MAX_RECENT = 50L
        const val BACKFILL_PAGE = 5_000L

        fun escapeLike(text: String): String =
            text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    }
}

internal fun PlaylistRow.toModel() = Playlist(
    id = id,
    name = name,
    source = source,
    sourceType = PlaylistSourceType.valueOf(source_type),
    refreshIntervalMinutes = refresh_interval_minutes,
    lastRefreshedAt = last_refreshed_at,
    channelCount = channel_count,
    epgUrl = epg_url,
    lastError = last_error,
    xtreamUsername = xtream_username,
    xtreamPassword = xtream_password,
    accountStatus = account_status,
    accountExpiresAt = account_expires_at,
)

@Suppress("LongParameterList")
private fun toChannel(
    id: Long,
    playlistId: Long,
    name: String,
    url: String,
    logo: String?,
    group: String,
    tvgId: String?,
    contentType: String,
    httpHeaders: String?,
    catchup: String?,
    catchupSource: String?,
    durationSeconds: Double?,
    seriesId: String?,
    favorite: Long,
) = Channel(
    id = id,
    playlistId = playlistId,
    name = name,
    url = url,
    logo = logo,
    group = group,
    tvgId = tvgId,
    type = ContentType.entries.firstOrNull { it.name == contentType } ?: ContentType.LIVE,
    headers = HeaderCodec.decode(httpHeaders),
    catchup = catchup,
    catchupSource = catchupSource,
    durationSeconds = durationSeconds,
    isFavorite = favorite != 0L,
    seriesId = seriesId,
)

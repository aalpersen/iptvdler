package io.github.aalpersen.iptvdler.core.model


enum class ContentType { LIVE, MOVIE, SERIES }

enum class PlaylistSourceType {
    URL,
    FILE,

    /** Xtream Codes account; [Playlist.source] is the server's base URL. */
    XTREAM,
}

data class Playlist(
    val id: Long,
    val name: String,
    /** http(s) URL, local file path, or Android content:// URI. */
    val source: String,
    val sourceType: PlaylistSourceType,
    /** null = manual refresh only. */
    val refreshIntervalMinutes: Long?,
    val lastRefreshedAt: Long?,
    val channelCount: Long,
    val epgUrl: String?,
    val lastError: String?,
    /** Xtream login; null for M3U playlists. */
    val xtreamUsername: String? = null,
    val xtreamPassword: String? = null,
    /** Xtream account status ("Active", …) and expiry (epoch millis) as of the last refresh. */
    val accountStatus: String? = null,
    val accountExpiresAt: Long? = null,
)

data class ChannelGroup(
    /** Empty string for channels without a group. */
    val name: String,
    val channelCount: Long,
)

data class Channel(
    val id: Long,
    val playlistId: Long,
    val name: String,
    val url: String,
    val logo: String?,
    /** Empty string for channels without a group. */
    val group: String,
    val tvgId: String?,
    val type: ContentType,
    /** HTTP headers to send for playback, keys, segments and downloads. */
    val headers: Map<String, String>,
    val catchup: String?,
    val catchupSource: String?,
    val durationSeconds: Double?,
    val isFavorite: Boolean,
    /** Xtream series: a container that opens its episode list instead of playing. */
    val seriesId: String? = null,
)

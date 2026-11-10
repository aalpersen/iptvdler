package io.github.aalpersen.iptvdler.ui.navigation

import kotlinx.serialization.Serializable

@Serializable data object DisclaimerRoute
@Serializable data object PlaylistsRoute
@Serializable data class GroupsRoute(val playlistId: Long)

enum class ChannelListMode { ALL, GROUP, FAVORITES, RECENT }

/** Enums are stored by name: navigation can't serialize enum route arguments without a custom type map. */
@Serializable
data class ChannelsRoute(
    val playlistId: Long,
    /** [ChannelListMode] name; use [mode]. */
    val modeName: String,
    /** Group name for [ChannelListMode.GROUP]; "" = ungrouped. */
    val group: String? = null,
    /** [io.github.aalpersen.iptvdler.core.model.ContentType] name, null = all types. */
    val contentType: String? = null,
) {
    constructor(playlistId: Long, mode: ChannelListMode, group: String? = null, contentType: String? = null) :
        this(playlistId, mode.name, group, contentType)

    val mode: ChannelListMode get() = ChannelListMode.valueOf(modeName)
}

/**
 * Plays a channel, a finished download offline, or a stream that isn't a channel row (an Xtream episode:
 * [url] + [title] from [playlistId]). -1 / "" = not set (nullable Longs aren't supported in routes).
 */
@Serializable
data class PlayerRoute(
    val channelId: Long = -1,
    val downloadId: Long = -1,
    val playlistId: Long = -1,
    val url: String = "",
    val title: String = "",
)

/** Episode list of an Xtream series. */
@Serializable
data class SeriesRoute(val playlistId: Long, val seriesId: String, val name: String)
@Serializable data object DownloadsRoute
@Serializable data object SettingsRoute

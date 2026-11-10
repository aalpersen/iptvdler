package io.github.aalpersen.iptvdler.data.xtream

import io.github.aalpersen.iptvdler.core.model.Playlist
import io.github.aalpersen.iptvdler.core.model.PlaylistSourceType
import io.ktor.http.Url
import io.ktor.http.encodeURLParameter
import io.ktor.http.encodeURLPathPart

/** An Xtream Codes account: the server's base URL (scheme, host, port, no path) plus the login. */
data class XtreamCredentials(val server: String, val username: String, val password: String) {

    /** `player_api.php` URL for [action] (null = account info) with extra query parameters. */
    fun apiUrl(action: String? = null, vararg params: Pair<String, String>): String = buildString {
        append(server).append("/player_api.php?username=").append(query(username)).append("&password=").append(query(password))
        if (action != null) append("&action=").append(action)
        params.forEach { (k, v) -> append('&').append(k).append('=').append(query(v)) }
    }

    fun liveUrl(streamId: String, extension: String) = streamUrl("live", streamId, extension)
    fun movieUrl(streamId: String, extension: String) = streamUrl("movie", streamId, extension)
    fun episodeUrl(episodeId: String, extension: String) = streamUrl("series", episodeId, extension)

    private fun streamUrl(kind: String, id: String, extension: String) =
        "$server/$kind/${username.encodeURLPathPart()}/${password.encodeURLPathPart()}/$id.${extension.ifBlank { "ts" }}"

    private fun query(value: String) = value.encodeURLParameter()

    override fun toString() = "XtreamCredentials($server, $username, ***)" // keep passwords out of logs

    companion object {
        /** Channel URL for a series row: not playable, and without the password so favorites survive a change. */
        fun seriesKey(seriesId: String) = "xtream-series:$seriesId"
    }
}

/** [url] with `username`/`password` query values masked, for showing in messages. */
fun withoutCredentials(url: String): String =
    url.replace(Regex("(?i)([?&](?:username|password)=)[^&#]*"), "$1***")

/** The login of an Xtream playlist, or null for M3U playlists. */
fun Playlist.xtreamCredentials(): XtreamCredentials? =
    if (sourceType == PlaylistSourceType.XTREAM) XtreamCredentials(source, xtreamUsername.orEmpty(), xtreamPassword.orEmpty()) else null

/** Recognizes the ways people share Xtream accounts and normalizes the server address. */
object XtreamLink {

    /**
     * Credentials from a pasted link, or null if it isn't one:
     * `…/get.php?username=U&password=P&type=m3u_plus`, `…/player_api.php?username=U&password=P`,
     * or a stream URL `…/live|movie|series/U/P/123.ext`.
     */
    fun parse(text: String): XtreamCredentials? {
        val url = urlOf(text) ?: return null
        val server = serverOf(url)
        val user = url.parameters["username"]
        val pass = url.parameters["password"]
        if (!user.isNullOrBlank() && pass != null) {
            return XtreamCredentials(server, user, pass) // Ktor has decoded the query already
        }
        val segments = url.segments
        if (segments.size >= 4 && segments[segments.size - 4] in STREAM_KINDS) {
            val (u, p) = segments[segments.size - 3] to segments[segments.size - 2]
            if (u.isNotBlank() && p.isNotBlank()) return XtreamCredentials(server, u, p)
        }
        return null
    }

    /** True for M3U links that are really Xtream accounts (`get.php?username=…&password=…`). */
    fun isXtreamM3uLink(text: String): Boolean =
        text.contains("get.php", ignoreCase = true) && parse(text) != null

    /**
     * "example.com:8080", "http://example.com:8080/", ".../player_api.php" or ".../get.php?…" become
     * "http://example.com:8080". Null if there's no host.
     */
    fun normalizeServer(text: String): String? = urlOf(text)?.let(::serverOf)

    /** Parsed URL with a real host (Ktor turns "http://" into localhost), or null. */
    private fun urlOf(text: String): Url? {
        val trimmed = text.trim()
        val authority = withScheme(trimmed).substringAfter("://").substringBefore('/').substringBefore('?')
        if (authority.isBlank() || authority.any { it.isWhitespace() }) return null
        return runCatching { Url(withScheme(trimmed)) }.getOrNull()?.takeIf { it.host.isNotBlank() }
    }

    private fun serverOf(url: Url): String {
        val defaultPort = url.protocol.defaultPort
        val port = if (url.port == defaultPort || url.specifiedPort == 0) "" else ":${url.port}"
        // Keep a sub-path (some panels live under /xtream/), minus the API script and stream parts.
        val path = url.segments
            .takeWhile { it !in STREAM_KINDS && !it.endsWith(".php", ignoreCase = true) }
            .joinToString("/")
            .let { if (it.isEmpty()) "" else "/$it" }
        return "${url.protocol.name}://${url.host}$port$path"
    }

    private fun withScheme(text: String) =
        if (text.startsWith("http://", ignoreCase = true) || text.startsWith("https://", ignoreCase = true)) text else "http://$text"

    private val STREAM_KINDS = setOf("live", "movie", "series")
}

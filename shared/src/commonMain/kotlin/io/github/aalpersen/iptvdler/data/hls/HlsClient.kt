package io.github.aalpersen.iptvdler.data.hls

import io.github.aalpersen.iptvdler.core.error.UserMessages
import io.github.aalpersen.iptvdler.core.hls.DrmDetector
import io.github.aalpersen.iptvdler.core.hls.EncryptionStatus
import io.github.aalpersen.iptvdler.core.hls.HlsParser
import io.github.aalpersen.iptvdler.core.hls.HlsPlaylist
import io.github.aalpersen.iptvdler.core.hls.MasterPlaylist
import io.github.aalpersen.iptvdler.core.hls.MediaPlaylist
import io.github.aalpersen.iptvdler.core.hls.QualityPreference
import io.github.aalpersen.iptvdler.core.hls.StreamSelection
import io.github.aalpersen.iptvdler.core.hls.VariantSelector
import io.github.aalpersen.iptvdler.data.network.streamHeaders
import io.github.aalpersen.iptvdler.i18n.strings
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.request
import io.ktor.http.HttpHeaders
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readBuffer
import kotlinx.io.readByteArray

class HlsFetchException(message: String) : Exception(message)

/** What a stream URL turned out to be. */
sealed interface StreamProbe {
    /** An HLS stream. [media] is the chosen variant's media playlist (or the URL itself if it was one). */
    data class Hls(
        val master: MasterPlaylist?,
        val selection: StreamSelection?,
        val media: MediaPlaylist,
        /** Separate audio rendition playlist, when the selection has one. */
        val audioMedia: MediaPlaylist?,
        val encryption: EncryptionStatus,
    ) : StreamProbe

    /** Not HLS: progressive file, MPEG-TS stream, DASH, ... [contentLength] null = unknown (often live). */
    data class Direct(val contentType: String?, val contentLength: Long? = null) : StreamProbe
}

/** Fetches and inspects HLS playlists using a channel's HTTP headers. */
class HlsClient(private val httpClient: HttpClient) {

    /** Fetches and parses a playlist; relative URIs are resolved against the final URL after redirects. */
    suspend fun fetch(url: String, headers: Map<String, String>): HlsPlaylist =
        httpClient.prepareGet(url) { streamHeaders(headers) }.execute { response ->
            response.requireSuccess()
            val bytes = response.bodyAsChannel().readBuffer(MAX_PLAYLIST_BYTES + 1).readByteArray()
            if (bytes.size > MAX_PLAYLIST_BYTES) throw HlsFetchException(strings.playlistTooLarge)
            HlsParser.parse(bytes.decodeToString(), response.request.url.toString())
        }

    suspend fun fetchMedia(url: String, headers: Map<String, String>): MediaPlaylist =
        fetch(url, headers) as? MediaPlaylist ?: throw HlsFetchException("Expected a media playlist at $url")

    /**
     * Works out what [url] is. Non-HLS responses are closed after the first few KB, so probing a
     * live MPEG-TS stream doesn't download it. For HLS, follows the master playlist to the selected
     * variant (and separate audio) to check their encryption.
     */
    suspend fun probe(
        url: String,
        headers: Map<String, String>,
        preference: QualityPreference = QualityPreference.Highest,
        audioLanguage: String? = null,
    ): StreamProbe {
        val playlist = httpClient.prepareGet(url) { streamHeaders(headers) }.execute { response ->
            response.requireSuccess()
            val channel = response.bodyAsChannel()
            val head = channel.readBuffer(PROBE_BYTES).readByteArray()
            val looksLikeHls = head.decodeToString().removePrefix("﻿").trimStart().startsWith("#EXTM3U")
            if (!looksLikeHls) return@execute StreamProbe.Direct(response.headers[HttpHeaders.ContentType], response.contentLength())
            val rest = channel.readBuffer(MAX_PLAYLIST_BYTES).readByteArray()
            HlsParser.parse((head + rest).decodeToString(), response.request.url.toString())
        }
        if (playlist is StreamProbe.Direct) return playlist

        return when (playlist) {
            is MediaPlaylist -> StreamProbe.Hls(null, null, playlist, null, DrmDetector.check(playlist))
            is MasterPlaylist -> {
                val selection = VariantSelector.select(playlist, preference, audioLanguage)
                    ?: throw HlsFetchException(strings.masterHasNoVariants)
                val media = fetchMedia(selection.variant.uri, headers)
                val audio = selection.audio?.uri?.let { fetchMedia(it, headers) }
                StreamProbe.Hls(
                    master = playlist,
                    selection = selection,
                    media = media,
                    audioMedia = audio,
                    encryption = DrmDetector.checkStream(playlist, *listOfNotNull(media, audio).toTypedArray()),
                )
            }
            else -> error("unreachable")
        }
    }

    private fun HttpResponse.requireSuccess() {
        if (!status.isSuccess()) throw HlsFetchException(UserMessages.httpStatus(status.value))
    }

    private companion object {
        const val PROBE_BYTES = 4 * 1024L
        const val MAX_PLAYLIST_BYTES = 20L * 1024 * 1024
    }
}

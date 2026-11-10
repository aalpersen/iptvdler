package io.github.aalpersen.iptvdler.data.hls

import io.github.aalpersen.iptvdler.core.error.UserMessages
import io.github.aalpersen.iptvdler.core.hls.EncryptionStatus
import io.github.aalpersen.iptvdler.core.hls.MasterPlaylist
import io.github.aalpersen.iptvdler.data.network.DEFAULT_USER_AGENT
import io.github.aalpersen.iptvdler.readFixture
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking

class HlsClientTest {

    private val requests = mutableListOf<HttpRequestData>()

    /** url -> (status, body, extra headers) */
    private val routes = mutableMapOf<String, Triple<HttpStatusCode, String, Map<String, String>>>()

    private val httpClient = HttpClient(MockEngine) {
        // Same plugins as the app's client.
        install(UserAgent) { agent = DEFAULT_USER_AGENT }
        install(HttpTimeout)
        engine {
            addHandler { request ->
                requests += request
                respondRoute(request.url.toString())
            }
        }
    }
    private val client = HlsClient(httpClient)

    private fun MockRequestHandleScope.respondRoute(url: String) =
        routes[url]?.let { (status, body, headers) ->
            respond(body, status, headersOf(*headers.map { (k, v) -> k to listOf(v) }.toTypedArray()))
        } ?: respond("", HttpStatusCode.NotFound)

    private fun route(url: String, body: String, status: HttpStatusCode = HttpStatusCode.OK, headers: Map<String, String> = emptyMap()) {
        routes[url] = Triple(status, body, headers)
    }

    @Test
    fun resolvesRelativeUrisAgainstTheUrlAfterRedirects() = runBlocking {
        route("https://short.test/tv", "", HttpStatusCode.Found, mapOf(HttpHeaders.Location to "https://cdn.test/show/v1/master.m3u8"))
        route("https://cdn.test/show/v1/master.m3u8", readFixture("hls/master.m3u8"))

        val master = client.fetch("https://short.test/tv", emptyMap())

        assertIs<MasterPlaylist>(master)
        assertEquals("https://cdn.test/show/v1/master.m3u8", master.uri)
        assertEquals("https://cdn.test/show/v1/low/index.m3u8", master.variants.first().uri)
    }

    @Test
    fun sendsChannelHeadersInsteadOfDefaults() = runBlocking {
        route("https://cdn.test/a.m3u8", readFixture("hls/media_vod.m3u8"))

        client.fetch("https://cdn.test/a.m3u8", mapOf("User-Agent" to "IPTV-Box/1.0", "Referer" to "https://portal.test/"))
        client.fetch("https://cdn.test/a.m3u8", emptyMap())

        assertEquals(listOf("IPTV-Box/1.0"), requests[0].headers.getAll(HttpHeaders.UserAgent))
        assertEquals("https://portal.test/", requests[0].headers[HttpHeaders.Referrer])
        assertEquals(listOf(DEFAULT_USER_AGENT), requests[1].headers.getAll(HttpHeaders.UserAgent))
    }

    @Test
    fun probeFollowsMasterToSelectedVariantAndAudio() = runBlocking {
        route("https://cdn.test/show/v1/master.m3u8", readFixture("hls/master.m3u8"))
        route("https://cdn.test/hi/index.m3u8?token=abc", readFixture("hls/media_aes.m3u8"))
        route("https://cdn.test/show/v1/audio/en/index.m3u8", readFixture("hls/media_vod.m3u8"))

        val probe = client.probe("https://cdn.test/show/v1/master.m3u8", emptyMap())

        assertIs<StreamProbe.Hls>(probe)
        assertEquals(1080, probe.selection?.variant?.resolution?.height)
        assertEquals(5, probe.media.segments.size)
        assertEquals(3, probe.audioMedia?.segments?.size)
        assertEquals(EncryptionStatus.Aes128, probe.encryption)
    }

    @Test
    fun probeDetectsDrmInTheSelectedAudioRendition() = runBlocking {
        route("https://cdn.test/show/v1/master.m3u8", readFixture("hls/master.m3u8"))
        route("https://cdn.test/hi/index.m3u8?token=abc", readFixture("hls/media_vod.m3u8"))
        route("https://cdn.test/show/v1/audio/en/index.m3u8", readFixture("hls/drm_widevine.m3u8"))

        val probe = client.probe("https://cdn.test/show/v1/master.m3u8", emptyMap())

        assertEquals(EncryptionStatus.Drm("Widevine"), (probe as StreamProbe.Hls).encryption)
        assertEquals(false, probe.encryption.downloadable)
    }

    @Test
    fun probeRecognisesMediaPlaylistsWithoutExtension() = runBlocking {
        route("https://iptv.test/live/user/pass/1001", readFixture("hls/media_live.m3u8"))

        val probe = client.probe("https://iptv.test/live/user/pass/1001", emptyMap())

        assertIs<StreamProbe.Hls>(probe)
        assertEquals(true, probe.media.isLive)
        assertEquals("https://iptv.test/live/user/pass/live2680.ts", probe.media.segments.first().uri)
    }

    @Test
    fun probeLeavesNonHlsStreamsAlone() = runBlocking {
        val tsPackets = String(CharArray(188 * 100) { if (it % 188 == 0) 'G' else 'x' })
        route("https://iptv.test/live/2002", tsPackets, headers = mapOf(HttpHeaders.ContentType to "video/mp2t"))

        val probe = client.probe("https://iptv.test/live/2002", emptyMap())

        assertEquals(StreamProbe.Direct("video/mp2t"), probe)
    }

    @Test
    fun httpErrorsAreReported() = runBlocking {
        route("https://cdn.test/forbidden.m3u8", "nope", HttpStatusCode.Forbidden)
        val error = assertFailsWith<HlsFetchException> { client.fetch("https://cdn.test/forbidden.m3u8", emptyMap()) }
        assertEquals(UserMessages.httpStatus(403), error.message)
    }
}

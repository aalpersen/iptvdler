package io.github.aalpersen.iptvdler.core.download

import io.github.aalpersen.iptvdler.TestEnvironment
import io.github.aalpersen.iptvdler.core.crypto.createAes128CbcDecryptor
import io.github.aalpersen.iptvdler.core.error.UserMessages
import io.github.aalpersen.iptvdler.core.hls.RenditionType
import io.github.aalpersen.iptvdler.core.subtitle.SubtitleOutput
import io.github.aalpersen.iptvdler.data.download.DownloadRepository
import io.github.aalpersen.iptvdler.data.hls.HlsClient
import io.github.aalpersen.iptvdler.data.hls.StreamProbe
import io.github.aalpersen.iptvdler.data.settings.AppSettings
import com.russhwolf.settings.MapSettings
import io.ktor.http.HttpStatusCode
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toPath

class SubtitleDownloadTest {

    private val env = TestEnvironment()
    private val server = TestServer()
    private val storage = FakeDownloadStorage()
    private val settings = AppSettings(MapSettings())
    private val repository = DownloadRepository(env.database, Dispatchers.IO, env.clock)
    private val hls = HlsClient(server.client)
    private val planner = DownloadPlanner(hls, repository, storage)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val manager = DownloadManager(
        repository, server.client, storage, settings, { true }, { }, createAes128CbcDecryptor(), scope, env.clock,
        retryPolicy = RetryPolicy(maxAttempts = 2, initialDelayMillis = 1, maxDelayMillis = 2),
        maxNetworkRetries = 0,
    ).also { it.start() }

    @AfterTest
    fun tearDown() = scope.cancel()

    private val base = "https://vod.test/show"

    /** A TS segment whose first PES has the given PTS (90 kHz). */
    private fun tsSegment(pts: Long): ByteArray {
        val p = ByteArray(188) { 0xFF.toByte() }
        p[0] = 0x47; p[1] = 0x41; p[2] = 0x00; p[3] = 0x10
        val encoded = byteArrayOf(
            (0x21 or ((pts shr 29) and 0x0E).toInt()).toByte(), (pts shr 22).toByte(),
            (((pts shr 14) and 0xFE) or 1).toByte(), (pts shr 7).toByte(), (((pts shl 1) and 0xFE) or 1).toByte(),
        )
        (byteArrayOf(0, 0, 1, 0xE0.toByte(), 0, 0, 0x80.toByte(), 0x80.toByte(), 5) + encoded).copyInto(p, 4)
        return p + p.copyOf().also { it[1] = 0x01 } // second packet: continuation, no PES
    }

    private fun vtt(mpegTs: Long, vararg cues: String) =
        "WEBVTT\nX-TIMESTAMP-MAP=MPEGTS:$mpegTs,LOCAL:00:00:00.000\n\n" + cues.joinToString("\n\n") + "\n"

    private fun serveShowWithSubtitles(germanBroken: Boolean) {
        server.put(
            "$base/master.m3u8",
            """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English",LANGUAGE="en",DEFAULT=YES,URI="subs/en.m3u8"
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="Deutsch",LANGUAGE="de",URI="subs/de.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=500000,RESOLUTION=640x360,SUBTITLES="subs"
            v/index.m3u8
            """.trimIndent(),
        )
        server.put("$base/v/index.m3u8", "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\n0.ts\n#EXTINF:6,\n1.ts\n#EXT-X-ENDLIST\n")
        // Video timeline starts at PTS 900000 (10 s), like many real streams.
        server.put("$base/v/0.ts", tsSegment(900_000))
        server.put("$base/v/1.ts", tsSegment(1_440_000))
        val subsPlaylist = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\n0.vtt\n#EXTINF:6,\n1.vtt\n#EXT-X-ENDLIST\n"
        server.put("$base/subs/en.m3u8", subsPlaylist)
        server.put("$base/subs/0.vtt", vtt(900_000, "00:00:01.000 --> 00:00:03.000\n<i>Hello</i> &amp; welcome"))
        server.put("$base/subs/1.vtt", vtt(1_440_000, "00:00:00.500 --> 00:00:02.000\nSecond segment"))
        server.put("$base/subs/de.m3u8", subsPlaylist.replace(".vtt", "-de.vtt"))
        server.put("$base/subs/0-de.vtt", vtt(900_000, "00:00:01.000 --> 00:00:03.000\nHallo"))
        if (!germanBroken) server.put("$base/subs/1-de.vtt", vtt(1_440_000, "00:00:00.500 --> 00:00:02.000\nZweites"))
    }

    private suspend fun downloadWithAllSubtitles(): DownloadTask {
        val probe = hls.probe("$base/master.m3u8", emptyMap()) as StreamProbe.Hls
        val subtitles = probe.master!!.renditions(RenditionType.SUBTITLES, "subs")
        val plan = planner.planHls("Show", "$base/master.m3u8", emptyMap(), probe.master, probe.selection?.variant, null, probe.media, subtitles)
        val id = manager.enqueue(plan)
        return withTimeout(10_000) {
            repository.observeTask(id).first { it?.status == DownloadStatus.COMPLETED || it?.status == DownloadStatus.FAILED }!!
        }
    }

    private fun read(path: String) = storage.fake.read(path.toPath()) { readUtf8() }

    @Test
    fun subtitleRenditionsAreMergedAlignedAndSavedAsSidecars() = runBlocking {
        serveShowWithSubtitles(germanBroken = false)
        settings.setSubtitleOutput(SubtitleOutput.BOTH)

        val task = downloadWithAllSubtitles()

        assertEquals(DownloadStatus.COMPLETED, task.status, task.error)
        assertEquals(6, task.totalSegments, "2 video + 2×2 subtitle segments all count toward progress")
        val subs = repository.subtitles(task.id)
        assertEquals(listOf("en", "de"), subs.map { it.language })
        assertEquals("/downloads/Show.en.srt", subs[0].srtPath)
        assertEquals("/downloads/Show.en.vtt", subs[0].vttPath)
        assertEquals(
            "1\n00:00:01,000 --> 00:00:03,000\n<i>Hello</i> & welcome\n\n" +
                // Segment 2 maps LOCAL 0 to PTS 1440000 = 6 s after the video start.
                "2\n00:00:06,500 --> 00:00:08,000\nSecond segment\n\n",
            read(subs[0].srtPath!!),
        )
        assertTrue(read(subs[0].vttPath!!).startsWith("WEBVTT\n\n00:00:01.000 --> 00:00:03.000\n<i>Hello</i> &amp; welcome"))
        assertEquals("/downloads/Show.de.srt", subs[1].srtPath)
        assertFalse(storage.fake.exists(storage.tempDirectory(task.id)))
    }

    @Test
    fun brokenSubtitleTrackDoesNotFailTheVideo() = runBlocking {
        serveShowWithSubtitles(germanBroken = true)

        val task = downloadWithAllSubtitles()

        assertEquals(DownloadStatus.COMPLETED, task.status, task.error)
        val (en, de) = repository.subtitles(task.id)
        assertNotNull(en.srtPath)
        assertNull(en.vttPath, "default output is SRT only")
        assertNull(de.playablePath)
        assertEquals(UserMessages.httpStatus(404), de.error)
    }

    @Test
    fun standaloneSubtitlesCanBeAddedToAFinishedDownload() = runBlocking {
        server.put("https://vod.test/m.mp4", ByteArray(5_000) { it.toByte() })
        server.put("https://subs.test/movie.srt", "1\n00:00:01,000 --> 00:00:02,000\nHi <b>there</b>\n")
        server.put("https://subs.test/broken.srt", "<html>nope</html>")
        val plan = planner.planDirect("Movie", "https://vod.test/m.mp4", emptyMap(), null, 5_000)
        val id = manager.enqueue(plan)
        withTimeout(10_000) { repository.observeTask(id).first { it?.status == DownloadStatus.COMPLETED } }

        val added = manager.addSubtitle(id, "https://subs.test/movie.srt", "tr")
        assertEquals("/downloads/Movie.tr.srt", added.srtPath)
        assertEquals("1\n00:00:01,000 --> 00:00:02,000\nHi <b>there</b>\n\n", read(added.srtPath!!))

        // Legacy Turkish SRT (windows-1254), as commonly found: decoded by language.
        server.put(
            "https://subs.test/cp1254.srt",
            "1\n00:00:03,000 --> 00:00:04,000\nTürkçe: ğüşıöç İĞÜŞÖÇ\n".toByteArray(charset("windows-1254")),
        )
        val legacy = manager.addSubtitle(id, "https://subs.test/cp1254.srt", "tr")
        assertTrue(read(legacy.srtPath!!).contains("Türkçe: ğüşıöç İĞÜŞÖÇ"), read(legacy.srtPath!!))
        storage.fake.delete(legacy.srtPath!!.toPath())

        val second = manager.addSubtitle(id, "https://subs.test/movie.srt", "tr")
        assertEquals("/downloads/Movie.tr.2.srt", second.srtPath, "same language twice gets a numbered name")

        val error = runCatching { manager.addSubtitle(id, "https://subs.test/broken.srt", "en") }.exceptionOrNull()
        assertEquals("Not a WebVTT or SRT subtitle file", error?.message)

        manager.delete(id, deleteFiles = true)
        assertFalse(storage.fake.exists("/downloads/Movie.tr.srt".toPath()))
        assertTrue(repository.subtitles(id).isEmpty())
    }

    @Test
    fun missingHttpServerForSubtitleIsReported() = runBlocking {
        server.put("https://vod.test/m.mp4", ByteArray(100))
        val id = manager.enqueue(planner.planDirect("M2", "https://vod.test/m.mp4", emptyMap(), null, 100))
        withTimeout(10_000) { repository.observeTask(id).first { it?.status == DownloadStatus.COMPLETED } }
        server.failNext("https://subs.test/x.vtt", HttpStatusCode.NotFound)
        val error = runCatching { manager.addSubtitle(id, "https://subs.test/x.vtt", "en") }.exceptionOrNull()
        assertEquals(UserMessages.httpStatus(404), error?.message)
    }
}

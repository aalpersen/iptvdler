package io.github.aalpersen.iptvdler.core.download

import io.github.aalpersen.iptvdler.TestEnvironment
import io.github.aalpersen.iptvdler.core.crypto.createAes128CbcDecryptor
import io.github.aalpersen.iptvdler.core.hls.RenditionType
import io.github.aalpersen.iptvdler.core.media.Mp4Boxes
import io.github.aalpersen.iptvdler.data.download.DownloadRepository
import io.github.aalpersen.iptvdler.data.hls.HlsClient
import io.github.aalpersen.iptvdler.data.hls.StreamProbe
import io.github.aalpersen.iptvdler.data.settings.AppSettings
import com.russhwolf.settings.MapSettings
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toPath

/** fMP4 output starts at 0 like TS (players show fMP4's own timestamps), gets a sidx, subtitles line up. */
class Fmp4AssemblyTest {

    private val env = TestEnvironment()
    private val server = TestServer()
    private val storage = FakeDownloadStorage()
    private val repository = DownloadRepository(env.database, Dispatchers.IO, env.clock)
    private val hls = HlsClient(server.client)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val manager = DownloadManager(
        repository, server.client, storage, AppSettings(MapSettings()), { true }, { }, createAes128CbcDecryptor(), scope, env.clock,
    ).also { it.start() }

    @AfterTest
    fun tearDown() = scope.cancel()

    private fun u32(v: Long) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    private fun box(type: String, payload: ByteArray) = u32(8L + payload.size) + type.encodeToByteArray() + payload
    private fun trak(id: Long, timescale: Long) = box(
        "trak",
        box("tkhd", u32(0) + u32(0) + u32(0) + u32(id)) + box("mdia", box("mdhd", u32(0) + u32(0) + u32(0) + u32(timescale))),
    )

    private val init = box("ftyp", u32(0)) + box("moov", trak(1, 90_000) + trak(2, 48_000))

    /** One fragment: a video traf and an audio traf, then some media data. */
    private fun fragment(videoTime: Long, audioTime: Long, fill: Int) = box(
        "moof",
        box("traf", box("tfhd", u32(0) + u32(1)) + box("tfdt", byteArrayOf(1, 0, 0, 0) + u32(videoTime ushr 32) + u32(videoTime))) +
            box("traf", box("tfhd", u32(0) + u32(2)) + box("tfdt", byteArrayOf(1, 0, 0, 0) + u32(audioTime ushr 32) + u32(audioTime))),
    ) + box("mdat", ByteArray(200) { fill.toByte() })

    @Test
    fun shiftsTimelineToZeroWritesIndexAndAlignsSubtitles() = runBlocking {
        val base = "https://vod.test/f"
        // Video starts at 10.000 s, audio at 9.956 s: shift by 9.956 s for both, keeping A/V sync.
        server.put("$base/init.mp4", init)
        server.put("$base/s0.m4s", fragment(900_000, 477_888, 1))
        server.put("$base/s1.m4s", fragment(1_440_000, 765_888, 2))
        server.put("$base/master.m3u8", """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="s",NAME="English",LANGUAGE="en",DEFAULT=YES,URI="en.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=500000,RESOLUTION=640x360,SUBTITLES="s"
            v.m3u8
        """.trimIndent())
        server.put("$base/v.m3u8", "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:6,\ns0.m4s\n#EXTINF:6,\ns1.m4s\n#EXT-X-ENDLIST\n")
        server.put("$base/en.m3u8", "#EXTM3U\n#EXT-X-TARGETDURATION:12\n#EXTINF:12,\nen.vtt\n#EXT-X-ENDLIST\n")
        // Subtitle cue at 10 s + 1 s on the stream's clock = 1.044 s after the video's first frame.
        server.put("$base/en.vtt", "WEBVTT\nX-TIMESTAMP-MAP=MPEGTS:900000,LOCAL:00:00:00.000\n\n00:00:01.000 --> 00:00:02.000\nHello\n")

        val probe = hls.probe("$base/master.m3u8", emptyMap()) as StreamProbe.Hls
        val subs = probe.master!!.renditions(RenditionType.SUBTITLES, "s")
        val plan = DownloadPlanner(hls, repository, storage)
            .planHls("Film", "$base/master.m3u8", emptyMap(), probe.master, probe.selection?.variant, null, probe.media, subs)
        val id = manager.enqueue(plan)
        val task = withTimeout(10_000) { repository.observeTask(id).first { it?.status == DownloadStatus.COMPLETED || it?.status == DownloadStatus.FAILED }!! }
        assertEquals(DownloadStatus.COMPLETED, task.status, task.error)

        val out = storage.fake.read(task.outputPath.toPath()) { readByteArray() }
        val top = Mp4Boxes.children(out).map { it.type }
        assertEquals(listOf("ftyp", "moov", "sidx", "moof", "mdat", "moof", "mdat"), top)

        // Fragment times after the shift (video 90 kHz, audio 48 kHz).
        assertEquals(
            listOf(1L to 3_960L, 2L to 0L, 1L to 543_960L, 2L to 288_000L),
            Mp4Boxes.fragmentTimes(out).map { it.first to it.second },
        )

        // sidx: earliest presentation time is the shifted video start; 6 s per segment.
        val sidx = Mp4Boxes.children(out).first { it.type == "sidx" }
        assertEquals(3_960, Mp4Boxes.readUInt64(out, sidx.payloadStart + 12))
        assertEquals(540_000, Mp4Boxes.readUInt32(out, sidx.payloadStart + 36))

        val srt = storage.fake.read(repository.subtitles(id).single().srtPath!!.toPath()) { readUtf8() }
        assertEquals("1\n00:00:01,044 --> 00:00:02,044\nHello\n\n", srt)
    }
}

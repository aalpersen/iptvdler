package io.github.aalpersen.iptvdler.core.download

import io.github.aalpersen.iptvdler.TestEnvironment
import io.github.aalpersen.iptvdler.core.crypto.createAes128CbcDecryptor
import io.github.aalpersen.iptvdler.core.hls.DRM_DOWNLOAD_REFUSED_MESSAGE
import io.github.aalpersen.iptvdler.core.hls.HlsIv
import io.github.aalpersen.iptvdler.data.download.DownloadRepository
import io.github.aalpersen.iptvdler.data.hls.HlsClient
import io.github.aalpersen.iptvdler.data.hls.StreamProbe
import io.github.aalpersen.iptvdler.data.settings.AppSettings
import io.github.aalpersen.iptvdler.readFixture
import com.russhwolf.settings.MapSettings
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.TimeZone
import okio.Path.Companion.toPath

/** A live HLS window that slides forward one segment every [advance]. */
class LiveStreamSim(private val server: TestServer, private val base: String, windowSize: Int = 4, firstSequence: Long = 100) {
    var first = firstSequence
    var last = firstSequence + windowSize - 1
    var ended = false
    var fmp4 = false
    var key: ByteArray? = null

    /** Key rotation in real streams comes with a new key URI. */
    var keyGeneration = 1
    val missing = HashSet<Long>()

    fun segment(sequence: Long): ByteArray = segmentBytes(sequence.toInt(), 400 + (sequence % 7).toInt())

    fun advance(steps: Int = 1) {
        first += steps
        last += steps
        publish()
    }

    /** The stream restarts: sequence numbers start over at [sequence]. */
    fun reset(sequence: Long, windowSize: Int = 4) {
        first = sequence
        last = sequence + windowSize - 1
        publish()
    }

    fun publish() {
        val text = buildString {
            append("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXT-X-MEDIA-SEQUENCE:$first\n")
            if (fmp4) append("#EXT-X-MAP:URI=\"init.mp4\"\n")
            if (key != null) append("#EXT-X-KEY:METHOD=AES-128,URI=\"key$keyGeneration.bin\"\n")
            for (seq in first..last) {
                append("#EXTINF:6.0,\ns$seq.ts\n")
                val plain = segment(seq)
                if (seq !in missing) server.put("$base/s$seq.ts", key?.let { TestCrypto.encrypt(plain, it, HlsIv.fromSequence(seq)) } ?: plain)
            }
            if (ended) append("#EXT-X-ENDLIST\n")
        }
        server.put("$base/live.m3u8", text)
        server.put("$base/init.mp4", INIT)
        key?.let { server.put("$base/key$keyGeneration.bin", it) }
    }

    companion object {
        val INIT = "INIT-SECTION".encodeToByteArray()
    }
}

class LiveRecordingTest {

    private val env = TestEnvironment()
    private val server = TestServer()
    private val storage = FakeDownloadStorage()
    private val settings = AppSettings(MapSettings())
    private val repository = DownloadRepository(env.database, Dispatchers.IO, env.clock)
    private val hls = HlsClient(server.client)
    private val planner = DownloadPlanner(hls, repository, storage)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val base = "https://live.test/ch1"
    private val sim = LiveStreamSim(server, base).also { it.publish() }

    @AfterTest
    fun tearDown() = scope.cancel()

    /** The recorder's sleep: advance fake time and the live window by one segment. */
    private val sleepAndAdvance: suspend (Long) -> Unit = { ms ->
        env.now += ms
        sim.advance()
    }

    private suspend fun recordingTask(stopAfterMillis: Long?): DownloadTask {
        val probe = hls.probe("$base/live.m3u8", emptyMap()) as StreamProbe.Hls
        val plan = planner.planHlsRecording(
            "Channel 1", "$base/live.m3u8", emptyMap(), null, null, null, probe.media,
            stopAt = stopAfterMillis?.let { env.now + it }, startedAt = env.now,
        )
        return repository.task(repository.create(plan))!!
    }

    private suspend fun record(task: DownloadTask): RecordingStats = HlsLiveRecordingJob(
        task, repository, hls, SegmentFetcher(server.client, RetryPolicy(maxAttempts = 1)), createAes128CbcDecryptor(),
        storage, env.clock, ProgressReporter(env.clock, 0, null, 0, 0) { }, sleepAndAdvance,
    ).run()

    private fun read(path: String) = storage.fake.read(path.toPath()) { readByteArray() }
    private fun concat(range: LongRange) = range.map(sim::segment).reduce { a, b -> a + b }

    @Test
    fun startsNearLiveEdgeAndAppendsNewSegmentsUntilStopTime() = runBlocking {
        val task = recordingTask(stopAfterMillis = 30_000)

        val stats = record(task)

        // Polls at 0, 6, 12, 18, 24 s: last 3 of the first window (101-103), then one new segment each.
        assertSameBytes(concat(101L..107L), read(task.outputPath))
        assertEquals(7 * 6.0, stats.seconds)
        assertEquals(0, stats.missedSegments)
        assertEquals(42.0, repository.task(task.id)!!.durationSeconds)
    }

    @Test
    fun followsPlaylistResets() = runBlocking {
        val task = recordingTask(stopAfterMillis = 24_000)
        var polls = 0
        val stats = HlsLiveRecordingJob(
            task, repository, hls, SegmentFetcher(server.client, RetryPolicy(maxAttempts = 1)), createAes128CbcDecryptor(),
            storage, env.clock, ProgressReporter(env.clock, 0, null, 0, 0) { },
            sleep = { ms ->
                env.now += ms
                if (++polls == 2) sim.reset(sequence = 0) else sim.advance()
            },
        ).run()

        assertEquals(1, stats.playlistResets)
        // 101-103, 104, then after the restart: last 3 of 0-3 (1-3), then 4
        assertSameBytes(concat(101L..104L) + concat(1L..4L), read(task.outputPath))
    }

    @Test
    fun countsSegmentsThatLeftTheWindowAndSkipsBrokenOnes() = runBlocking {
        val task = recordingTask(stopAfterMillis = 18_000)
        sim.missing += 111 // will 404
        var polls = 0
        val stats = HlsLiveRecordingJob(
            task, repository, hls, SegmentFetcher(server.client, RetryPolicy(maxAttempts = 1)), createAes128CbcDecryptor(),
            storage, env.clock, ProgressReporter(env.clock, 0, null, 0, 0) { },
            sleep = { ms ->
                env.now += ms
                sim.advance(if (++polls == 1) 8 else 1) // we fell behind: 104-107 left the window
            },
        ).run()

        // Polls at 0, 6, 12 s: 101-103; window 108-111 (104-107 left it, 111 fails); then 112.
        assertEquals(4 + 1, stats.missedSegments)
        assertSameBytes(concat(101L..103L) + concat(108L..110L) + sim.segment(112), read(task.outputPath))
    }

    @Test
    fun endsWhenTheEventEnds() = runBlocking {
        val task = recordingTask(stopAfterMillis = null) // no stop time: only the ENDLIST ends it
        var polls = 0
        val stats = HlsLiveRecordingJob(
            task, repository, hls, SegmentFetcher(server.client, RetryPolicy(maxAttempts = 1)), createAes128CbcDecryptor(),
            storage, env.clock, ProgressReporter(env.clock, 0, null, 0, 0) { },
            sleep = { ms ->
                env.now += ms
                sim.advance()
                if (++polls == 2) {
                    sim.ended = true
                    sim.publish()
                }
            },
        ).run()
        // 101-103, 104, then 105 arrives with #EXT-X-ENDLIST
        assertSameBytes(concat(101L..105L), read(task.outputPath))
        assertEquals(5 * 6.0, stats.seconds)
    }

    @Test
    fun decryptsRotatingAes128Keys() = runBlocking {
        sim.key = ByteArray(16) { 7 }
        sim.publish()
        val task = recordingTask(stopAfterMillis = 12_000)
        var polls = 0
        HlsLiveRecordingJob(
            task, repository, hls, SegmentFetcher(server.client, RetryPolicy(maxAttempts = 1)), createAes128CbcDecryptor(),
            storage, env.clock, ProgressReporter(env.clock, 0, null, 0, 0) { },
            sleep = { ms ->
                env.now += ms
                if (++polls == 1) {
                    sim.key = ByteArray(16) { 9 } // key rotation
                    sim.keyGeneration = 2
                }
                sim.advance()
            },
        ).run()
        // Polls at 0 and 6 s: 101-103 with key 1, then 104 with the rotated key 2.
        assertSameBytes(concat(101L..104L), read(task.outputPath))
        assertEquals(1, server.requestCount("$base/key1.bin"))
        assertEquals(1, server.requestCount("$base/key2.bin"))
    }

    @Test
    fun writesFmp4InitSectionOnceAndAgainAfterReset() = runBlocking {
        sim.fmp4 = true
        sim.publish()
        val task = recordingTask(stopAfterMillis = 18_000)
        assertTrue(task.outputPath.endsWith(".mp4"))
        var polls = 0
        HlsLiveRecordingJob(
            task, repository, hls, SegmentFetcher(server.client, RetryPolicy(maxAttempts = 1)), createAes128CbcDecryptor(),
            storage, env.clock, ProgressReporter(env.clock, 0, null, 0, 0) { },
            sleep = { ms ->
                env.now += ms
                if (++polls == 1) sim.reset(0) else sim.advance()
            },
        ).run()
        val init = LiveStreamSim.INIT
        assertSameBytes(init + concat(101L..103L) + init + concat(1L..4L), read(task.outputPath))
    }

    @Test
    fun recordsContinuousStreamsAndReconnects() = runBlocking {
        val body = segmentBytes(5, 3000)
        server.put("https://iptv.test/live/u/p/7.ts", body, contentType = "video/mp2t")
        val plan = planner.planDirectRecording("News", "https://iptv.test/live/u/p/7.ts", emptyMap(), "video/mp2t", null, env.now + 5_000, env.now)
        val task = repository.task(repository.create(plan))!!
        assertTrue(task.outputPath.endsWith(".ts"))

        DirectLiveRecordingJob(
            task, repository, server.client, storage, env.clock, ProgressReporter(env.clock, 0, null, 0, 0) { },
            sleep = { env.now += it },
        ).run()

        // The server "drops" after each body: 3 connections before the 5 s stop (2 s reconnect delay).
        assertSameBytes(body + body + body, read(task.outputPath))
        assertEquals(3, server.requestCount("https://iptv.test/live/u/p/7.ts"))
    }

    @Test
    fun managerStopsRecordingOnRequestAndKeepsTheFile() = runBlocking {
        val manager = DownloadManager(
            repository, server.client, storage, settings, { true }, { }, createAes128CbcDecryptor(), scope, env.clock,
            recordingSleep = { ms ->
                delay(20)
                env.now += ms
                sim.advance()
            },
        ).also { it.start() }
        val probe = hls.probe("$base/live.m3u8", emptyMap()) as StreamProbe.Hls
        val id = manager.enqueue(
            planner.planHlsRecording("Channel 1", "$base/live.m3u8", emptyMap(), null, null, null, probe.media, null, env.now),
        )
        withTimeout(10_000) { repository.observeTask(id).first { (it?.durationSeconds ?: 0.0) >= 30.0 } }

        manager.stopRecording(id)

        val task = repository.task(id)!!
        assertEquals(DownloadStatus.COMPLETED, task.status)
        assertTrue(read(task.outputPath).isNotEmpty())
        assertEquals(read(task.outputPath).size.toLong(), task.totalBytes)
    }

    @Test
    fun recordingsIgnoreTheDownloadLimit() = runBlocking {
        settings.setMaxConcurrentDownloads(1)
        server.latencyMillis = 200
        val manager = DownloadManager(
            repository, server.client, storage, settings, { true }, { }, createAes128CbcDecryptor(), scope, env.clock,
            recordingSleep = { delay(50) },
        ).also { it.start() }
        server.put("https://vod.test/big.mp4", ByteArray(50_000))
        manager.enqueue(planner.planDirect("Movie", "https://vod.test/big.mp4", emptyMap(), null, 50_000))
        manager.enqueue(planner.planDirect("Movie 2", "https://vod.test/big.mp4", emptyMap(), null, 50_000))
        val probe = hls.probe("$base/live.m3u8", emptyMap()) as StreamProbe.Hls
        val recording = manager.enqueue(
            planner.planHlsRecording("Channel 1", "$base/live.m3u8", emptyMap(), null, null, null, probe.media, null, env.now),
        )

        withTimeout(5_000) { repository.observeTask(recording).first { it?.status == DownloadStatus.RUNNING } }
        val running = repository.tasksWithStatus(DownloadStatus.RUNNING)
        assertEquals(1, running.count { !it.isRecording }, "downloads still respect the limit")
        manager.stopRecording(recording)
    }

    @Test
    fun plannerRefusesNonLiveAndDrmStreams() = runBlocking {
        server.put("$base/vod.m3u8", readFixture("hls/media_vod.m3u8"))
        server.put("$base/drm.m3u8", readFixture("hls/drm_widevine.m3u8").replace("#EXT-X-ENDLIST", ""))
        val vod = assertFailsWith<DownloadRefusedException> {
            planner.planHlsRecording("V", "$base/vod.m3u8", emptyMap(), null, null, null, null, null, env.now)
        }
        assertEquals(NOT_LIVE_RECORDING_MESSAGE, vod.message)
        val drm = assertFailsWith<DownloadRefusedException> {
            planner.planHlsRecording("D", "$base/drm.m3u8", emptyMap(), null, null, null, null, null, env.now)
        }
        assertEquals(DRM_DOWNLOAD_REFUSED_MESSAGE, drm.message)
        val file = assertFailsWith<DownloadRefusedException> {
            planner.planDirectRecording("F", "https://vod.test/m.mp4", emptyMap(), "video/mp4", 1_000, null, env.now)
        }
        assertEquals(NOT_LIVE_RECORDING_MESSAGE, file.message)
    }

    @Test
    fun localTimeHelpers() {
        val istanbul = TimeZone.of("Europe/Istanbul") // UTC+3, no DST
        val now = 1_758_819_600_000L // 2025-09-25T17:00:00Z = 20:00 in Istanbul
        assertEquals("2025-09-25 20.00", RecordingNames.timestamp(now, istanbul))
        assertEquals("20:00", RecordingNames.clock(now, istanbul))
        assertEquals(now + 90 * 60_000, RecordingNames.nextOccurrence(21, 30, now, istanbul), "later today")
        assertEquals(now + 23 * 3_600_000, RecordingNames.nextOccurrence(19, 0, now, istanbul), "already passed: tomorrow")
        assertEquals(21 to 5, RecordingNames.parseClock("21:05"))
        assertEquals(9 to 5, RecordingNames.parseClock("9:5"))
        assertEquals(21 to 5, RecordingNames.parseClock("2105"))
        assertEquals(null, RecordingNames.parseClock("25:00"))
    }

    /** Compares without dumping kilobytes of array contents into the report. */
    private fun assertSameBytes(expected: ByteArray, actual: ByteArray) {
        assertEquals(expected.size, actual.size, "size")
        assertTrue(expected.contentEquals(actual), "content differs")
    }
}

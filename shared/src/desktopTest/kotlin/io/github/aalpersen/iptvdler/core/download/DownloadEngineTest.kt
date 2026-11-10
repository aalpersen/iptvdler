package io.github.aalpersen.iptvdler.core.download

import io.github.aalpersen.iptvdler.TestEnvironment
import io.github.aalpersen.iptvdler.core.crypto.createAes128CbcDecryptor
import io.github.aalpersen.iptvdler.core.error.UserMessages
import io.github.aalpersen.iptvdler.core.hls.DRM_DOWNLOAD_REFUSED_MESSAGE
import io.github.aalpersen.iptvdler.core.hls.HlsIv
import io.github.aalpersen.iptvdler.core.hls.MasterPlaylist
import io.github.aalpersen.iptvdler.core.hls.VariantSelector
import io.github.aalpersen.iptvdler.data.download.DownloadRepository
import io.github.aalpersen.iptvdler.data.hls.HlsClient
import io.github.aalpersen.iptvdler.data.hls.StreamProbe
import io.github.aalpersen.iptvdler.data.settings.AppSettings
import io.github.aalpersen.iptvdler.readFixture
import com.russhwolf.settings.MapSettings
import io.ktor.http.HttpStatusCode
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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

class DownloadEngineTest {

    private val env = TestEnvironment()
    private val server = TestServer()
    private val storage = FakeDownloadStorage()
    private val settings = AppSettings(MapSettings())
    private val repository = DownloadRepository(env.database, Dispatchers.IO, env.clock)
    private val hls = HlsClient(server.client)
    private val planner = DownloadPlanner(hls, repository, storage)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val fastRetry = RetryPolicy(maxAttempts = 3, initialDelayMillis = 1, maxDelayMillis = 5)

    private fun manager(maxNetworkRetries: Int = 0) = DownloadManager(
        repository = repository,
        httpClient = server.client,
        storage = storage,
        settings = settings,
        networkMonitor = { true },
        foreground = { },
        decryptor = createAes128CbcDecryptor(),
        scope = scope,
        clock = env.clock,
        retryPolicy = fastRetry,
        networkRetryDelayMillis = 10,
        maxNetworkRetries = maxNetworkRetries,
    )

    @AfterTest
    fun tearDown() {
        scope.cancel()
        storage.fake.checkNoOpenFiles()
    }

    private val key = ByteArray(16) { (it + 1).toByte() }
    private val base = "https://vod.test/movie"

    /** 5 TS segments; 0-2 encrypted with a sequence-derived IV, 3 with an explicit IV, 4 clear. */
    private fun serveEncryptedTsMovie(): ByteArray {
        val plain = (0 until 5).map { segmentBytes(it) }
        val explicitIv = ByteArray(16) { (0xA0 + it).toByte() }
        server.put("$base/key.bin", key)
        (0..2).forEach { i -> server.put("$base/s$i.ts", TestCrypto.encrypt(plain[i], key, HlsIv.fromSequence(100L + i))) }
        server.put("$base/s3.ts", TestCrypto.encrypt(plain[3], key, explicitIv))
        server.put("$base/s4.ts", plain[4])
        server.put(
            "$base/index.m3u8",
            """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:100
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin"
            #EXTINF:6,
            s0.ts
            #EXTINF:6,
            s1.ts
            #EXTINF:6,
            s2.ts
            #EXT-X-KEY:METHOD=AES-128,URI="key.bin",IV=0xA0A1A2A3A4A5A6A7A8A9AAABACADAEAF
            #EXTINF:6,
            s3.ts
            #EXT-X-KEY:METHOD=NONE
            #EXTINF:4,
            s4.ts
            #EXT-X-ENDLIST
            """.trimIndent(),
        )
        return plain.reduce { a, b -> a + b }
    }

    private suspend fun enqueueHls(title: String, url: String): Long {
        val probe = hls.probe(url, emptyMap()) as StreamProbe.Hls
        val plan = planner.planHls(title, url, emptyMap(), probe.master, probe.selection?.variant, probe.selection?.audio, probe.media)
        return repository.create(plan)
    }

    private suspend fun awaitStatus(id: Long, vararg statuses: DownloadStatus): DownloadTask =
        withTimeout(10_000) { repository.observeTask(id).first { it != null && it.status in statuses }!! }

    private fun read(path: String) = storage.fake.read(path.toPath()) { readByteArray() }

    @Test
    fun downloadsDecryptsAndAssemblesTsSegments() = runBlocking {
        val expected = serveEncryptedTsMovie()
        val manager = manager()
        manager.start()
        val id = enqueueHls("My Movie: Part 1", "$base/index.m3u8")

        val task = awaitStatus(id, DownloadStatus.COMPLETED, DownloadStatus.FAILED)

        assertEquals(DownloadStatus.COMPLETED, task.status, task.error)
        assertEquals("/downloads/My Movie_ Part 1.ts", task.outputPath)
        assertContentEquals(expected, read(task.outputPath))
        assertEquals(expected.size.toLong(), task.totalBytes)
        assertEquals(5, task.completedSegments)
        assertFalse(storage.fake.exists(storage.tempDirectory(id)), "temp segments removed after verification")
        assertEquals(1, server.requestCount("$base/key.bin"), "key is fetched once and cached")
    }

    @Test
    fun downloadsFmp4WithByteRangesAndInitSection() = runBlocking {
        val init = segmentBytes(99, 300)
        val media = (0 until 3).map { segmentBytes(it, 500) }
        server.put("$base/all.mp4", init + media.reduce { a, b -> a + b })
        server.put(
            "$base/fmp4.m3u8",
            """
            #EXTM3U
            #EXT-X-TARGETDURATION:4
            #EXT-X-MAP:URI="all.mp4",BYTERANGE="300@0"
            #EXTINF:4,
            #EXT-X-BYTERANGE:500@300
            all.mp4
            #EXTINF:4,
            #EXT-X-BYTERANGE:500
            all.mp4
            #EXTINF:4,
            #EXT-X-BYTERANGE:500
            all.mp4
            #EXT-X-ENDLIST
            """.trimIndent(),
        )
        manager().start()
        val id = enqueueHls("Fragmented", "$base/fmp4.m3u8")

        val task = awaitStatus(id, DownloadStatus.COMPLETED, DownloadStatus.FAILED)
        assertEquals(DownloadStatus.COMPLETED, task.status, task.error)
        assertTrue(task.outputPath.endsWith(".mp4"))
        assertContentEquals(init + media.reduce { a, b -> a + b }, read(task.outputPath))
        assertEquals(3, task.totalSegments, "init section isn't counted as a segment")
    }

    @Test
    fun byteRangesWorkWhenServerIgnoresRangeHeader() = runBlocking {
        server.ignoreRange = true
        val data = segmentBytes(1, 2000)
        server.put("$base/one.ts", data)
        server.put(
            "$base/br.m3u8",
            "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXTINF:4,\n#EXT-X-BYTERANGE:1000@0\none.ts\n#EXTINF:4,\n#EXT-X-BYTERANGE:1000\none.ts\n#EXT-X-ENDLIST\n",
        )
        manager().start()
        val task = awaitStatus(enqueueHls("Ranges", "$base/br.m3u8"), DownloadStatus.COMPLETED, DownloadStatus.FAILED)
        assertContentEquals(data, read(task.outputPath))
    }

    @Test
    fun separateAudioRenditionIsSavedSideBySide() = runBlocking {
        val master = readFixture("hls/master.m3u8")
        server.put("https://cdn.test/show/v1/master.m3u8", master)
        server.put("https://cdn.test/hi/index.m3u8?token=abc", "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXTINF:4,\nv0.ts\n#EXTINF:4,\nv1.ts\n#EXT-X-ENDLIST\n")
        server.put("https://cdn.test/show/v1/audio/en/index.m3u8", "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXTINF:8,\na0.aac\n#EXT-X-ENDLIST\n")
        server.put("https://cdn.test/hi/v0.ts", segmentBytes(0))
        server.put("https://cdn.test/hi/v1.ts", segmentBytes(1))
        server.put("https://cdn.test/show/v1/audio/en/a0.aac", segmentBytes(2))

        manager().start()
        val task = awaitStatus(enqueueHls("Show", "https://cdn.test/show/v1/master.m3u8"), DownloadStatus.COMPLETED, DownloadStatus.FAILED)

        assertEquals(DownloadStatus.COMPLETED, task.status, task.error)
        assertEquals("1080p", task.qualityLabel)
        assertEquals("/downloads/Show.ts", task.outputPath)
        assertEquals("/downloads/Show.audio.aac", task.audioOutputPath)
        assertContentEquals(segmentBytes(0) + segmentBytes(1), read(task.outputPath))
        assertContentEquals(segmentBytes(2), read(task.audioOutputPath!!))
        val estimate = VariantSelector.estimateBytes((hls.fetch("https://cdn.test/show/v1/master.m3u8", emptyMap()) as MasterPlaylist).variants[1], 8.0)
        assertEquals(estimate, task.estimatedBytes)
    }

    @Test
    fun retriesTransientErrorsWithBackoff() = runBlocking {
        val expected = serveEncryptedTsMovie()
        server.failNext("$base/s2.ts", HttpStatusCode.ServiceUnavailable, HttpStatusCode.BadGateway)
        manager().start()
        val task = awaitStatus(enqueueHls("Flaky", "$base/index.m3u8"), DownloadStatus.COMPLETED, DownloadStatus.FAILED)
        assertEquals(DownloadStatus.COMPLETED, task.status, task.error)
        assertContentEquals(expected, read(task.outputPath))
        assertEquals(3, server.requestCount("$base/s2.ts"))
    }

    @Test
    fun resumesFailedTaskWithoutRefetchingFinishedSegments() = runBlocking {
        val expected = serveEncryptedTsMovie()
        settings.setParallelSegments(1) // deterministic: 0-2 finish before 3 fails
        server.failNext("$base/s3.ts", HttpStatusCode.NotFound) // permanent error: task fails
        val manager = manager()
        manager.start()
        val id = enqueueHls("Resume", "$base/index.m3u8")

        val failed = awaitStatus(id, DownloadStatus.FAILED, DownloadStatus.COMPLETED)
        assertEquals(DownloadStatus.FAILED, failed.status)
        assertEquals(UserMessages.httpStatus(404), failed.error)
        val before = listOf(0, 1, 2).associateWith { server.requestCount("$base/s$it.ts") }

        manager.resume(id)
        val done = awaitStatus(id, DownloadStatus.COMPLETED)
        assertContentEquals(expected, read(done.outputPath))
        listOf(0, 1, 2).forEach { i ->
            assertEquals(before[i], server.requestCount("$base/s$i.ts"), "segment $i was already downloaded")
        }
    }

    @Test
    fun missingTempFileIsDownloadedAgainOnResume() = runBlocking {
        val expected = serveEncryptedTsMovie()
        server.failNext("$base/s4.ts", HttpStatusCode.Forbidden)
        // One segment at a time, so segment 1 is surely stored when segment 4 fails (no race with the failure).
        settings.setParallelSegments(1)
        val manager = manager()
        manager.start()
        val id = enqueueHls("Lost", "$base/index.m3u8")
        awaitStatus(id, DownloadStatus.FAILED)

        val lost = storage.tempDirectory(id) / "t0_1.seg"
        assertTrue(storage.fake.exists(lost))
        storage.fake.delete(lost) // e.g. cleaned by the OS
        manager.resume(id)

        assertContentEquals(expected, read(awaitStatus(id, DownloadStatus.COMPLETED).outputPath))
        assertEquals(2, server.requestCount("$base/s1.ts"))
    }

    @Test
    fun pauseStopsAndResumeFinishes() = runBlocking {
        val expected = serveEncryptedTsMovie()
        server.latencyMillis = 150
        settings.setParallelSegments(1)
        val manager = manager()
        manager.start()
        val id = enqueueHls("Pausable", "$base/index.m3u8")

        withTimeout(10_000) { repository.observeTask(id).first { (it?.completedSegments ?: 0) >= 1 } }
        manager.pause(id)
        assertEquals(DownloadStatus.PAUSED, repository.task(id)!!.status)
        val requestsAtPause = server.requests.size
        kotlinx.coroutines.delay(500)
        assertEquals(requestsAtPause, server.requests.size, "no requests while paused")

        manager.resume(id)
        assertContentEquals(expected, read(awaitStatus(id, DownloadStatus.COMPLETED).outputPath))
    }

    @Test
    fun interruptedTasksResumeOnStart() = runBlocking {
        val expected = serveEncryptedTsMovie()
        val id = enqueueHls("After reboot", "$base/index.m3u8")
        repository.updateStatus(id, DownloadStatus.RUNNING) // app was killed mid-download

        manager().start()

        assertContentEquals(expected, read(awaitStatus(id, DownloadStatus.COMPLETED).outputPath))
    }

    @Test
    fun networkFailuresAreRetriedAutomatically() = runBlocking {
        val expected = serveEncryptedTsMovie()
        // More failures than one job's retry budget (3): the manager re-queues the task and resumes it.
        server.failNext("$base/s1.ts", *Array(4) { HttpStatusCode.ServiceUnavailable })
        manager(maxNetworkRetries = 3).start()
        val task = awaitStatus(enqueueHls("Network", "$base/index.m3u8"), DownloadStatus.COMPLETED, DownloadStatus.FAILED)
        assertEquals(DownloadStatus.COMPLETED, task.status, task.error)
        assertContentEquals(expected, read(task.outputPath))
    }

    @Test
    fun refusesDrmAndLiveStreams() = runBlocking {
        server.put("$base/drm.m3u8", readFixture("hls/drm_widevine.m3u8"))
        server.put("$base/live.m3u8", readFixture("hls/media_live.m3u8"))

        val drm = assertFailsWith<DownloadRefusedException> { enqueueHls("DRM", "$base/drm.m3u8") }
        assertEquals(DRM_DOWNLOAD_REFUSED_MESSAGE, drm.message)
        val live = assertFailsWith<DownloadRefusedException> { enqueueHls("Live", "$base/live.m3u8") }
        assertEquals(LIVE_DOWNLOAD_REFUSED_MESSAGE, live.message)
        assertTrue(repository.observeTasks().first().isEmpty())
    }

    @Test
    fun refusesWhenNotEnoughFreeSpace() = runBlocking {
        serveEncryptedTsMovie()
        server.put("https://vod.test/m.m3u8", "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=80000000,RESOLUTION=1920x1080\nmovie/index.m3u8\n")
        storage.freeBytes = 10L * 1024 * 1024
        manager().start()
        val task = awaitStatus(enqueueHls("Big", "https://vod.test/m.m3u8"), DownloadStatus.FAILED, DownloadStatus.COMPLETED)
        assertEquals(DownloadStatus.FAILED, task.status)
        assertTrue(task.error!!.startsWith("Not enough free space"), task.error)
    }

    @Test
    fun outputNamesAreUniqueAndSafe() = runBlocking {
        serveEncryptedTsMovie()
        storage.downloadsDirectory()
        storage.fake.write("/downloads/Dup.ts".toPath()) { writeUtf8("existing file") }
        val first = repository.task(enqueueHls("Dup", "$base/index.m3u8"))!!
        val second = repository.task(enqueueHls("Dup", "$base/index.m3u8"))!!
        assertEquals("/downloads/Dup (2).ts", first.outputPath)
        assertEquals("/downloads/Dup (3).ts", second.outputPath, "paths claimed by queued tasks are skipped")
    }

    @Test
    fun directDownloadResumesPartialFileWithRange() = runBlocking {
        val movie = segmentBytes(7, 300_000)
        server.put("https://vod.test/films/Movie.mkv", movie, contentType = "video/x-matroska")
        val plan = planner.planDirect("Movie", "https://vod.test/films/Movie.mkv", emptyMap(), "video/x-matroska", movie.size.toLong())
        assertEquals("/downloads/Movie.mkv", plan.outputPath)
        // Simulate an earlier interrupted run.
        storage.downloadsDirectory()
        storage.fake.write("/downloads/Movie.mkv.part".toPath()) { write(movie.copyOfRange(0, 120_000)) }

        manager().start()
        val task = awaitStatus(repository.create(plan), DownloadStatus.COMPLETED, DownloadStatus.FAILED)

        assertEquals(DownloadStatus.COMPLETED, task.status, task.error)
        assertContentEquals(movie, read(task.outputPath))
        assertFalse(storage.fake.exists("/downloads/Movie.mkv.part".toPath()))
    }

    @Test
    fun directDownloadRestartsWhenServerIgnoresRange() = runBlocking {
        server.ignoreRange = true
        val movie = segmentBytes(8, 50_000)
        server.put("https://vod.test/m.mp4", movie)
        storage.downloadsDirectory()
        storage.fake.write("/downloads/Film.mp4.part".toPath()) { write(ByteArray(10_000) { 9 }) } // stale bytes
        val plan = planner.planDirect("Film", "https://vod.test/m.mp4", emptyMap(), null, movie.size.toLong())

        manager().start()
        val task = awaitStatus(repository.create(plan), DownloadStatus.COMPLETED, DownloadStatus.FAILED)
        assertContentEquals(movie, read(task.outputPath))
    }

    @Test
    fun directDownloadsWithoutLengthAreTreatedAsLive() = runBlocking {
        val e = assertFailsWith<DownloadRefusedException> { planner.planDirect("Live TV", "https://iptv.test/live/1", emptyMap(), "video/mp2t", null) }
        assertEquals(LIVE_DOWNLOAD_REFUSED_MESSAGE, e.message)
        assertEquals("mkv", DownloadPlanner.directExtension("https://h.test/get.php?id=1", "video/x-matroska"))
        assertEquals("avi", DownloadPlanner.directExtension("https://h.test/movie/u/p/5.avi", null))
    }

    @Test
    fun deleteRemovesTaskTempAndPartialFiles() = runBlocking {
        serveEncryptedTsMovie()
        server.failNext("$base/s4.ts", HttpStatusCode.Forbidden)
        val manager = manager()
        manager.start()
        val id = enqueueHls("Delete me", "$base/index.m3u8")
        awaitStatus(id, DownloadStatus.FAILED)
        assertTrue(storage.fake.exists(storage.tempDirectory(id)))

        manager.delete(id, deleteFiles = true)

        assertNull(repository.task(id))
        assertFalse(storage.fake.exists(storage.tempDirectory(id)))
    }
}

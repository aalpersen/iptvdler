package io.github.aalpersen.iptvdler.core.download

import io.github.aalpersen.iptvdler.TestEnvironment
import io.github.aalpersen.iptvdler.core.crypto.createAes128CbcDecryptor
import io.github.aalpersen.iptvdler.core.hls.QualityPreference
import io.github.aalpersen.iptvdler.core.model.EpochClock
import io.github.aalpersen.iptvdler.core.hls.VariantSelector
import io.github.aalpersen.iptvdler.core.storage.DownloadStorage
import io.github.aalpersen.iptvdler.data.download.DownloadRepository
import io.github.aalpersen.iptvdler.data.hls.HlsClient
import io.github.aalpersen.iptvdler.data.hls.StreamProbe
import io.github.aalpersen.iptvdler.data.network.createHttpClient
import io.github.aalpersen.iptvdler.data.settings.AppSettings
import com.russhwolf.settings.MapSettings
import java.io.File
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
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * Real HTTP + real files. Opt-in:
 * - `-Piptvdler.localHls=http://127.0.0.1:8000` downloads the ffmpeg-generated streams from docs/TESTING.md
 * - `-Piptvdler.networkTests=true` downloads Mux's public test stream (lowest quality)
 * Outputs land in shared/build/smoke-downloads for checking with ffmpeg/VLC.
 */
class RealDownloadSmokeTest {

    private val localHls = System.getProperty("iptvdler.localHls").orEmpty()
    private val network = System.getProperty("iptvdler.networkTests") == "true"

    private val env = TestEnvironment()
    private val outDir = File("build/smoke-downloads").apply { mkdirs() }
    private val storage = object : DownloadStorage {
        override val fileSystem = FileSystem.SYSTEM
        override fun downloadsDirectory(): Path = outDir.absolutePath.toPath()
        override fun freeBytes(path: Path): Long? = outDir.usableSpace
    }
    private val http = createHttpClient()
    private val hls = HlsClient(http)
    private val repository = DownloadRepository(env.database, Dispatchers.IO, env.clock)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val manager = DownloadManager(
        // Real clock: recordings stop at real times here.
        repository, http, storage, AppSettings(MapSettings()), { true }, { }, createAes128CbcDecryptor(), scope, EpochClock.System,
    ).also { it.start() }
    private val planner = DownloadPlanner(hls, repository, storage)

    @AfterTest
    fun tearDown() = scope.cancel()

    private suspend fun download(
        title: String,
        url: String,
        quality: QualityPreference = QualityPreference.Highest,
        subtitleLanguages: Set<String> = emptySet(),
    ): DownloadTask {
        val probe = hls.probe(url, emptyMap(), quality) as StreamProbe.Hls
        val subtitles = if (subtitleLanguages.isEmpty()) emptyList() else
            VariantSelector.select(probe.master!!, quality, subtitleLanguages = subtitleLanguages)!!.subtitles
        val id = manager.enqueue(
            planner.planHls(title, url, emptyMap(), probe.master, probe.selection?.variant, probe.selection?.audio, probe.media, subtitles),
        )
        val task = withTimeout(300_000) {
            repository.observeTask(id).first { it?.status == DownloadStatus.COMPLETED || it?.status == DownloadStatus.FAILED }!!
        }
        assertEquals(DownloadStatus.COMPLETED, task.status, task.error)
        println("SMOKE $title -> ${task.outputPath} (${task.totalBytes} bytes, ${task.totalSegments} segments)")
        return task
    }

    @Test
    fun localAes128TsAndFmp4() = runBlocking {
        if (localHls.isEmpty()) return@runBlocking
        download("local-aes-ts", "$localHls/aes/index.m3u8")
        download("local-fmp4", "$localHls/fmp4/index.m3u8")
        download("local-fmp4-aes", "$localHls/fmp4-aes/index.m3u8")
        Unit
    }

    @Test
    fun appleFmp4WithEnglishSubtitles() = runBlocking {
        if (!network) return@runBlocking
        val task = download(
            "apple-fmp4-subs",
            "https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8",
            QualityPreference.Lowest,
            subtitleLanguages = setOf("en"),
        )
        val subs = repository.subtitles(task.id)
        println("SMOKE subtitles: $subs")
        val srt = File(subs.single().srtPath!!).readText()
        println("SMOKE srt head: " + srt.lines().take(12).joinToString(" | "))
        Unit
    }

    /** `-Piptvdler.liveUrl=<live HLS URL>`: records ~20 s with the real recorder. */
    @Test
    fun recordsLiveStream() = runBlocking {
        val url = System.getProperty("iptvdler.liveUrl").orEmpty()
        if (url.isEmpty()) return@runBlocking
        val probe = hls.probe(url, emptyMap(), QualityPreference.Lowest) as StreamProbe.Hls
        println("SMOKE live: isLive=${probe.media.isLive} target=${probe.media.targetDurationSeconds} encryption=${probe.encryption}")
        val start = System.currentTimeMillis()
        val id = manager.enqueue(
            planner.planHlsRecording(
                "live-smoke", url, emptyMap(), probe.master, probe.selection?.variant, probe.selection?.audio, probe.media,
                stopAt = start + 20_000, startedAt = start,
            ),
        )
        val task = withTimeout(90_000) {
            repository.observeTask(id).first { it?.status == DownloadStatus.COMPLETED || it?.status == DownloadStatus.FAILED }!!
        }
        println("SMOKE live -> ${task.status} ${task.outputPath} ${task.totalBytes} bytes, ${task.durationSeconds} s recorded, error=${task.error}")
        kotlin.test.assertEquals(DownloadStatus.COMPLETED, task.status, task.error)
    }

    @Test
    fun muxPublicStreamLowestQuality() = runBlocking {
        if (!network) return@runBlocking
        download("mux-bbb", "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8", QualityPreference.Lowest)
        Unit
    }
}

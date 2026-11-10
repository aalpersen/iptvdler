package io.github.aalpersen.iptvdler.core.download

import io.github.aalpersen.iptvdler.core.model.EpochClock

/**
 * Accumulates bytes/segments for one task and derives speed (sliding window) and ETA.
 * Thread-safe enough for the worker pool: all mutation goes through [add], which is synchronized
 * by the caller's single-writer discipline (workers report through a Mutex in the job).
 */
class ProgressReporter(
    private val clock: EpochClock,
    private val totalSegments: Int,
    private var totalBytes: Long?,
    initialBytes: Long,
    initialSegments: Int,
    private val publish: (DownloadProgress) -> Unit,
) {
    private var bytes = initialBytes
    private var segments = initialSegments
    private var note: String? = null
    private val samples = ArrayDeque<Pair<Long, Long>>() // (time, cumulative bytes)

    val downloadedBytes: Long get() = bytes
    val completedSegments: Int get() = segments

    init {
        samples.addLast(clock.nowMillis() to bytes)
        emit()
    }

    fun add(byteCount: Long, segmentCount: Int = 0) {
        bytes += byteCount
        segments += segmentCount
        note = null
        val now = clock.nowMillis()
        samples.addLast(now to bytes)
        while (samples.size > 2 && now - samples.first().first > WINDOW_MILLIS) samples.removeFirst()
        emit()
    }

    fun setTotalBytes(total: Long?) {
        totalBytes = total
        emit()
    }

    fun setNote(text: String?) {
        note = text
        emit()
    }

    private fun emit() {
        val (t0, b0) = samples.first()
        val (t1, b1) = samples.last()
        val speed = if (t1 > t0) (b1 - b0) * 1000 / (t1 - t0) else 0
        val eta = when {
            speed <= 0 -> null
            totalSegments > 0 && segments > 0 -> {
                // Average segment size so far predicts the remaining bytes better than a bandwidth estimate.
                val remaining = (totalSegments - segments).toLong() * (bytes / segments)
                remaining / speed
            }
            totalBytes != null -> ((totalBytes!! - bytes).coerceAtLeast(0)) / speed
            else -> null
        }
        publish(
            DownloadProgress(
                downloadedBytes = bytes,
                totalBytes = totalBytes,
                completedSegments = segments,
                totalSegments = totalSegments,
                bytesPerSecond = speed,
                etaSeconds = eta,
                note = note,
            ),
        )
    }

    private companion object {
        const val WINDOW_MILLIS = 5_000L
    }
}

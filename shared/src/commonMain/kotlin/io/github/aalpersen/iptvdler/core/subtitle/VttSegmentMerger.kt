package io.github.aalpersen.iptvdler.core.subtitle

/**
 * Merges segmented WebVTT (HLS subtitle renditions) into one cue list on the downloaded video's timeline.
 *
 * Each segment may carry `X-TIMESTAMP-MAP=MPEGTS:m,LOCAL:l`: cue time `t` (local) is media time
 * `m / 90000 + (t - l)`. The downloaded file starts at the video's first timestamp [videoStartPts]
 * (players show that as 0), so output time = media time − videoStartPts / 90000.
 *
 * MPEG-TS timestamps are 33-bit and wrap around (2^33 ticks ≈ 26.5 hours); differences are unwrapped.
 */
object VttSegmentMerger {

    const val TICKS_PER_SECOND = 90_000L
    const val PTS_WRAP = 1L shl 33

    /**
     * @param segments WebVTT text of each segment, in playlist order
     * @param videoStartPts first PTS of the video (90 kHz); null = unknown, then the first segment's
     *   MPEGTS is used as the timeline origin
     */
    fun merge(segments: List<String>, videoStartPts: Long?): List<Cue> {
        val docs = segments.mapNotNull { text -> runCatching { WebVtt.parse(text) }.getOrNull() }
        val origin = videoStartPts ?: docs.firstNotNullOfOrNull { it.timestampMap?.mpegTs }

        val cues = ArrayList<Cue>()
        for (doc in docs) {
            val map = doc.timestampMap
            val shiftMs = if (map != null && origin != null) {
                ptsDelta(from = origin, to = map.mpegTs) * 1000 / TICKS_PER_SECOND - map.localMs
            } else {
                0L // no map: cue times are already relative to the stream start
            }
            doc.cues.forEach { cues += it.shifted(shiftMs) }
        }
        return normalize(cues)
    }

    /** `to - from` in ticks, unwrapping the 33-bit rollover (assumes the true gap is under ~13 hours). */
    fun ptsDelta(from: Long, to: Long): Long {
        var delta = (to - from) % PTS_WRAP
        if (delta > PTS_WRAP / 2) delta -= PTS_WRAP
        if (delta < -PTS_WRAP / 2) delta += PTS_WRAP
        return delta
    }

    /** Sorts by time, drops cues entirely before 0, clamps starts at 0 and removes duplicates (cues repeated across segments). */
    fun normalize(cues: List<Cue>): List<Cue> {
        val seen = HashSet<Triple<Long, Long, String>>()
        return cues
            .filter { it.endMs > 0 }
            .map { if (it.startMs < 0) it.copy(startMs = 0) else it }
            .sortedWith(compareBy<Cue> { it.startMs }.thenBy { it.endMs })
            .filter { seen.add(Triple(it.startMs, it.endMs, it.text)) }
            .map { it.copy(id = null) }
    }
}

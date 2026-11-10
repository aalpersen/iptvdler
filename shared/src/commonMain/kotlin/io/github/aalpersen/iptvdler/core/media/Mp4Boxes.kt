package io.github.aalpersen.iptvdler.core.media

/** Minimal ISO-BMFF (MP4) box reading/writing for fMP4 downloads. */
object Mp4Boxes {

    /** Returns (payload start, payload end) of the box at [path], descending into the first match at each level. */
    fun findBox(data: ByteArray, path: List<String>, from: Int = 0, to: Int = data.size): Pair<Int, Int>? {
        var offset = from
        while (offset + 8 <= to) {
            var size = readUInt32(data, offset)
            val type = data.decodeToString(offset + 4, offset + 8)
            var header = 8
            if (size == 1L) {
                if (offset + 16 > to) return null
                size = readUInt64(data, offset + 8)
                header = 16
            } else if (size == 0L) {
                size = (to - offset).toLong()
            }
            if (size < header || offset + size > to) return null
            val payloadStart = offset + header
            val end = (offset + size).toInt()
            if (type == path.first()) {
                // Full boxes (mdhd, tfdt, tkhd) have version+flags first; callers handle it.
                return if (path.size == 1) payloadStart to end else findBox(data, path.drop(1), payloadStart, end)
            }
            offset = end
        }
        return null
    }

    /** Timescale of the first track (`moov/trak/mdia/mdhd`). */
    fun firstTrackTimescale(init: ByteArray): Long? = findBox(init, listOf("moov", "trak", "mdia", "mdhd"))?.let { (start, _) ->
        val version = init[start].toInt()
        // version 0: 4 creation + 4 modification; version 1: 8 + 8; then the 4-byte timescale.
        readUInt32(init, start + 4 + if (version == 1) 16 else 8).takeIf { it > 0 }
    }

    /** track_ID of the first track (`moov/trak/tkhd`). */
    fun firstTrackId(init: ByteArray): Long? = findBox(init, listOf("moov", "trak", "tkhd"))?.let { (start, _) ->
        val version = init[start].toInt()
        readUInt32(init, start + 4 + if (version == 1) 16 else 8)
    }

    /** Base media decode time of a fragment (`moof/traf/tfdt`), in the track's timescale. */
    fun baseDecodeTime(fragment: ByteArray): Long? = findBox(fragment, listOf("moof", "traf", "tfdt"))?.let { (start, _) ->
        if (fragment[start].toInt() == 1) readUInt64(fragment, start + 4) else readUInt32(fragment, start + 4)
    }

    /** A box: [type], its payload range and its end (exclusive). */
    class Box(val type: String, val payloadStart: Int, val end: Int)

    /** Direct children of the byte range (top-level boxes by default). Stops at malformed data. */
    fun children(data: ByteArray, from: Int = 0, to: Int = data.size): List<Box> {
        val boxes = ArrayList<Box>()
        var offset = from
        while (offset + 8 <= to) {
            var size = readUInt32(data, offset)
            var header = 8
            if (size == 1L) {
                if (offset + 16 > to) break
                size = readUInt64(data, offset + 8)
                header = 16
            } else if (size == 0L) {
                size = (to - offset).toLong()
            }
            if (size < header || offset + size > to) break
            boxes += Box(data.decodeToString(offset + 4, offset + 8), offset + header, (offset + size).toInt())
            offset += size.toInt()
        }
        return boxes
    }

    /** track_ID -> timescale for every track in the init section. */
    fun trackTimescales(init: ByteArray): Map<Long, Long> {
        val moov = children(init).firstOrNull { it.type == "moov" } ?: return emptyMap()
        return children(init, moov.payloadStart, moov.end).filter { it.type == "trak" }.mapNotNull { trak ->
            val boxes = children(init, trak.payloadStart, trak.end)
            val tkhd = boxes.firstOrNull { it.type == "tkhd" } ?: return@mapNotNull null
            val trackId = readUInt32(init, tkhd.payloadStart + 4 + if (init[tkhd.payloadStart].toInt() == 1) 16 else 8)
            val mdia = boxes.firstOrNull { it.type == "mdia" } ?: return@mapNotNull null
            val mdhd = children(init, mdia.payloadStart, mdia.end).firstOrNull { it.type == "mdhd" } ?: return@mapNotNull null
            val timescale = readUInt32(init, mdhd.payloadStart + 4 + if (init[mdhd.payloadStart].toInt() == 1) 16 else 8)
            if (timescale > 0) trackId to timescale else null
        }.toMap()
    }

    /** (track_ID, base media decode time) of every `moof/traf` in [fragment], with the tfdt field offset. */
    fun fragmentTimes(fragment: ByteArray): List<Triple<Long, Long, Int>> =
        children(fragment).filter { it.type == "moof" }.flatMap { moof ->
            children(fragment, moof.payloadStart, moof.end).filter { it.type == "traf" }.mapNotNull { traf ->
                val boxes = children(fragment, traf.payloadStart, traf.end)
                val tfhd = boxes.firstOrNull { it.type == "tfhd" } ?: return@mapNotNull null
                val tfdt = boxes.firstOrNull { it.type == "tfdt" } ?: return@mapNotNull null
                val trackId = readUInt32(fragment, tfhd.payloadStart + 4)
                val time = if (fragment[tfdt.payloadStart].toInt() == 1) readUInt64(fragment, tfdt.payloadStart + 4)
                else readUInt32(fragment, tfdt.payloadStart + 4)
                Triple(trackId, time, tfdt.payloadStart)
            }
        }

    /**
     * Rewrites every tfdt in [fragment] in place with [transform] (track_ID, time) -> new time.
     * Returns false (leaving the data untouched) if a new value doesn't fit or is negative.
     */
    fun rewriteDecodeTimes(fragment: ByteArray, transform: (trackId: Long, time: Long) -> Long): Boolean {
        val updates = fragmentTimes(fragment).map { (trackId, time, at) -> Triple(at, transform(trackId, time), fragment[at].toInt() == 1) }
        if (updates.any { (_, value, is64) -> value < 0 || (!is64 && value > 0xFFFFFFFFL) }) return false
        updates.forEach { (at, value, is64) ->
            if (is64) writeUInt64(fragment, at + 4, value) else writeUInt32(fragment, at + 4, value)
        }
        return true
    }

    private fun writeUInt32(d: ByteArray, i: Int, v: Long) {
        for (k in 0..3) d[i + k] = (v shr (24 - 8 * k)).toByte()
    }

    private fun writeUInt64(d: ByteArray, i: Int, v: Long) {
        writeUInt32(d, i, v ushr 32)
        writeUInt32(d, i + 4, v and 0xFFFFFFFFL)
    }

    fun readUInt32(d: ByteArray, i: Int): Long =
        ((d[i].toLong() and 0xFF) shl 24) or ((d[i + 1].toLong() and 0xFF) shl 16) or
            ((d[i + 2].toLong() and 0xFF) shl 8) or (d[i + 3].toLong() and 0xFF)

    fun readUInt64(d: ByteArray, i: Int): Long = (readUInt32(d, i) shl 32) or readUInt32(d, i + 4)
}

/**
 * Builds a `sidx` (segment index) box for a concatenated fMP4 download. Without it, players that
 * don't scan the file (ExoPlayer) treat fragmented MP4 as unseekable. Written right after the init
 * section, so `first_offset` is 0 and each reference is one downloaded segment (its moof+mdat pairs).
 */
object Fmp4SegmentIndex {

    class SegmentInfo(val sizeBytes: Long, val baseDecodeTime: Long)

    /**
     * @param lastDurationSeconds duration of the final segment (the playlist's EXTINF), since there's no next tfdt
     * @return the box bytes, or null if an index can't be built (then the file is written without one)
     */
    fun build(init: ByteArray, segments: List<SegmentInfo>, lastDurationSeconds: Double): ByteArray? {
        if (segments.isEmpty() || segments.size > 0xFFFF) return null
        val timescale = Mp4Boxes.firstTrackTimescale(init) ?: return null
        val trackId = Mp4Boxes.firstTrackId(init) ?: return null
        if (segments.any { it.sizeBytes <= 0 || it.sizeBytes >= (1L shl 31) }) return null

        val durations = segments.mapIndexed { i, segment ->
            if (i < segments.lastIndex) segments[i + 1].baseDecodeTime - segment.baseDecodeTime
            else (lastDurationSeconds * timescale).toLong()
        }
        if (durations.any { it <= 0 || it >= (1L shl 32) }) return null

        val size = 12 + 4 + 4 + 8 + 8 + 2 + 2 + 12 * segments.size
        val out = ByteArray(size)
        var p = 0
        fun u32(v: Long) { for (s in 24 downTo 0 step 8) out[p++] = (v shr s).toByte() }
        fun u64(v: Long) { u32(v ushr 32); u32(v and 0xFFFFFFFFL) }
        fun u16(v: Int) { out[p++] = (v shr 8).toByte(); out[p++] = v.toByte() }

        u32(size.toLong())
        "sidx".encodeToByteArray().forEach { out[p++] = it }
        u32(1L shl 24) // version 1, flags 0
        u32(trackId)
        u32(timescale)
        u64(segments.first().baseDecodeTime) // earliest_presentation_time
        u64(0) // first_offset: the first segment follows this box
        u16(0) // reserved
        u16(segments.size)
        segments.forEachIndexed { i, segment ->
            u32(segment.sizeBytes) // reference_type 0 (media) + referenced_size
            u32(durations[i])
            u32(0x90000000L) // starts_with_SAP = 1, SAP_type = 1, SAP_delta_time = 0
        }
        return out
    }
}

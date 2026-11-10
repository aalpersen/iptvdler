package io.github.aalpersen.iptvdler.core.subtitle

import io.github.aalpersen.iptvdler.core.media.Mp4Boxes

/**
 * Reads the first presentation timestamp of a media segment, in 90 kHz ticks, so subtitles can be
 * aligned with the downloaded video (players show the first timestamp as 0:00).
 */
object MediaTimestamps {

    /** Smallest PTS among the first PES packets of an MPEG-TS segment, or null. */
    fun firstTsPts(data: ByteArray, maxPackets: Int = 2000): Long? {
        var offset = data.indexOfFirstSync() ?: return null
        var best: Long? = null
        var seen = 0
        while (offset + 188 <= data.size && seen < maxPackets) {
            seen++
            val p = offset
            offset += 188
            if (data[p].toInt() != 0x47) continue
            val payloadStart = data[p + 1].toInt() and 0x40 != 0
            val adaptation = (data[p + 3].toInt() shr 4) and 0x3
            if (!payloadStart || adaptation == 2) continue
            var payload = p + 4
            if (adaptation == 3) payload += 1 + (data[p + 4].toInt() and 0xFF)
            if (payload + 14 > p + 188) continue
            // PES start code 00 00 01, then stream id; PTS flags in byte 7.
            if (data[payload].toInt() != 0 || data[payload + 1].toInt() != 0 || data[payload + 2].toInt() != 1) continue
            val streamId = data[payload + 3].toInt() and 0xFF
            if (streamId !in 0xC0..0xEF) continue // audio/video elementary streams only
            val ptsFlag = (data[payload + 7].toInt() and 0x80) != 0
            if (!ptsFlag) continue
            val pts = readPts(data, payload + 9)
            best = if (best == null) pts else minOf(best, pts)
            if (seen > 200) break
        }
        return best
    }

    private fun ByteArray.indexOfFirstSync(): Int? {
        for (i in 0 until minOf(size, 188)) {
            if (this[i].toInt() == 0x47 && (i + 188 >= size || this[i + 188].toInt() == 0x47)) return i
        }
        return null
    }

    private fun readPts(d: ByteArray, i: Int): Long {
        val b0 = d[i].toLong() and 0xFF
        val b1 = d[i + 1].toLong() and 0xFF
        val b2 = d[i + 2].toLong() and 0xFF
        val b3 = d[i + 3].toLong() and 0xFF
        val b4 = d[i + 4].toLong() and 0xFF
        return ((b0 shr 1) and 0x07 shl 30) or (b1 shl 22) or ((b2 shr 1) shl 15) or (b3 shl 7) or (b4 shr 1)
    }

    /**
     * First decode time of an fMP4 media segment (`moof/traf/tfdt`) converted to 90 kHz using the
     * track's timescale from the init section (`moov/trak/mdia/mdhd`). Uses the first track.
     */
    fun firstFmp4Pts(initSection: ByteArray, mediaSegment: ByteArray): Long? {
        val timescale = Mp4Boxes.firstTrackTimescale(initSection) ?: return null
        val baseTime = Mp4Boxes.baseDecodeTime(mediaSegment) ?: return null
        return baseTime * VttSegmentMerger.TICKS_PER_SECOND / timescale
    }
}

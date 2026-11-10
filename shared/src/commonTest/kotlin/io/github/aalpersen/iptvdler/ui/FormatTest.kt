package io.github.aalpersen.iptvdler.ui

import io.github.aalpersen.iptvdler.core.player.TrackInfo
import io.github.aalpersen.iptvdler.core.player.TrackType
import io.github.aalpersen.iptvdler.ui.player.displayLabel
import kotlin.test.Test
import kotlin.test.assertEquals

class FormatTest {

    @Test
    fun formatsDurations() {
        assertEquals("0:00", formatDuration(0))
        assertEquals("0:59", formatDuration(59_999))
        assertEquals("12:34", formatDuration(754_000))
        assertEquals("1:02:03", formatDuration(3_723_000))
        assertEquals("0:00", formatDuration(-5))
    }

    @Test
    fun formatsBitratesAndCounts() {
        assertEquals("5.2 Mbps", formatBitrate(5_200_000))
        assertEquals("1.0 Mbps", formatBitrate(999_999))
        assertEquals("800 kbps", formatBitrate(800_000))
        assertEquals("12,345", formatCount(12_345))
        assertEquals("-1,000", formatCount(-1_000))
    }

    @Test
    fun labelsVideoTracksByHeightAndBitrate() {
        assertEquals("1080p · 5.2 Mbps", TrackInfo("1", TrackType.VIDEO, "x", bitrate = 5_200_000, height = 1080).displayLabel())
        assertEquals("Main", TrackInfo("1", TrackType.VIDEO, "Main").displayLabel())
        assertEquals("English", TrackInfo("1", TrackType.AUDIO, "English", "en", bitrate = 128_000).displayLabel())
    }
}

package io.github.aalpersen.iptvdler.ui

import io.github.aalpersen.iptvdler.i18n.strings
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** 12345 -> "12,345" (en) / "12.345" (tr) */
internal fun formatCount(value: Long): String {
    val digits = kotlin.math.abs(value).toString()
    val grouped = digits.reversed().chunked(3).joinToString(strings.groupSeparator.toString()).reversed()
    return if (value < 0) "-$grouped" else grouped
}

internal fun formatCount(value: Int): String = formatCount(value.toLong())

/** Playback time: "4:05", "1:02:03". */
internal fun formatDuration(millis: Long): String {
    val totalSeconds = millis.coerceAtLeast(0) / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    val mmss = "${minutes.toString().padStart(if (hours > 0) 2 else 1, '0')}:${seconds.toString().padStart(2, '0')}"
    return if (hours > 0) "$hours:$mmss" else mmss
}

/** One decimal with the language's separator: 52 -> "5.2" / "5,2". */
private fun tenths(value: Long) = "${value / 10}${strings.decimalSeparator}${value % 10}"

/** 5_200_000 -> "5.2 Mbps", 800_000 -> "800 kbps" */
internal fun formatBitrate(bitsPerSecond: Int): String =
    if (bitsPerSecond >= 950_000) {
        "${tenths(((bitsPerSecond + 50_000) / 100_000).toLong())} Mbps"
    } else {
        "${(bitsPerSecond + 500) / 1000} kbps"
    }

/** 1536 -> "1.5 KB", 734003200 -> "700 MB" (binary units, one decimal below 100). */
internal fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    val text = if (value >= 100) value.toLong().toString() else tenths((value * 10 + 0.5).toLong())
    return "$text ${units[unit]}"
}

/** ETA: "45 s", "3 min", "1 h 20 min". */
internal fun formatEta(seconds: Long): String = when {
    seconds < 60 -> strings.seconds(seconds)
    seconds < 3600 -> strings.minutes(seconds / 60)
    else -> strings.hoursMinutes(seconds / 3600, (seconds % 3600) / 60)
}

/** Coarse "x ago" text for timestamps in epoch millis. */
internal fun formatRelative(thenMillis: Long, nowMillis: Long): String {
    val minutes = (nowMillis - thenMillis).coerceAtLeast(0) / 60_000
    return when {
        minutes < 1 -> strings.justNow
        minutes < 60 -> strings.minutesAgo(minutes)
        minutes < 60 * 24 -> strings.hoursAgo(minutes / 60)
        else -> strings.daysAgo(minutes / (60 * 24))
    }
}

/** Calendar date in the device's time zone: "2027-03-12" (en) / "12.03.2027" (tr). */
internal fun formatDate(epochMillis: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
    val date = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(zone).date
    return strings.date(date.year, date.month.ordinal + 1, date.day)
}

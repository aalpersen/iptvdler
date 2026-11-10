package io.github.aalpersen.iptvdler.core.download

import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/** Local-time helpers for recordings (file names, "until 21:00"). */
object RecordingNames {

    /** "2026-09-25 20.30" in the device's time zone (no ':' so it's a valid Windows file name). */
    fun timestamp(epochMillis: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
        val t = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(zone)
        return "${t.date} ${two(t.hour)}.${two(t.minute)}"
    }

    /** "21:05" local time. */
    fun clock(epochMillis: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
        val t = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(zone)
        return "${two(t.hour)}:${two(t.minute)}"
    }

    /**
     * The next time the local clock shows [hour]:[minute] after [nowMillis]: today if still ahead,
     * otherwise tomorrow. Handles DST via the time zone rules.
     */
    fun nextOccurrence(hour: Int, minute: Int, nowMillis: Long, zone: TimeZone = TimeZone.currentSystemDefault()): Long {
        val now = Instant.fromEpochMilliseconds(nowMillis)
        val today: LocalDate = now.toLocalDateTime(zone).date
        val todayAt = today.atTime(LocalTime(hour, minute)).toInstant(zone)
        val target = if (todayAt > now) todayAt else today.plus(DatePeriod(days = 1)).atTime(LocalTime(hour, minute)).toInstant(zone)
        return target.toEpochMilliseconds()
    }

    /** Parses "21:05" / "9:5" / "2105"; null if not a valid time. */
    fun parseClock(text: String): Pair<Int, Int>? {
        val t = text.trim()
        val (h, m) = when {
            ':' in t -> t.substringBefore(':') to t.substringAfter(':')
            t.length == 4 && t.all(Char::isDigit) -> t.take(2) to t.takeLast(2)
            else -> return null
        }
        val hour = h.toIntOrNull()?.takeIf { it in 0..23 } ?: return null
        val minute = m.toIntOrNull()?.takeIf { it in 0..59 } ?: return null
        return hour to minute
    }

    private fun two(v: Int) = v.toString().padStart(2, '0')
}

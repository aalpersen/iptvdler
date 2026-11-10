package io.github.aalpersen.iptvdler.core.subtitle

/** One subtitle cue. Times are in milliseconds on the output timeline. */
data class Cue(
    val startMs: Long,
    val endMs: Long,
    /** Cue text with WebVTT markup (`<i>`, `<c.x>`, `&amp;` ...), lines joined with '\n'. */
    val text: String,
    /** WebVTT cue settings (`align:start position:10%`), kept when writing VTT. */
    val settings: String = "",
    val id: String? = null,
) {
    fun shifted(deltaMs: Long) = copy(startMs = startMs + deltaMs, endMs = endMs + deltaMs)
}

/** `X-TIMESTAMP-MAP=MPEGTS:<90 kHz ticks>,LOCAL:<cue time>` from a WebVTT header. */
data class TimestampMap(val mpegTs: Long, val localMs: Long)

data class VttDocument(val cues: List<Cue>, val timestampMap: TimestampMap? = null)

enum class SubtitleFormat(val extension: String) { VTT("vtt"), SRT("srt") }

class SubtitleParseException(message: String) : Exception(message)

/** Timestamp parsing/formatting shared by VTT and SRT. */
internal object SubtitleTime {
    /** `hh:mm:ss.ttt`, `mm:ss.ttt`, also tolerates `,` as decimal separator and 1-3 digit fractions. */
    fun parse(text: String): Long? {
        val t = text.trim().replace(',', '.')
        val parts = t.split(':')
        if (parts.size !in 2..3) return null
        val hours = if (parts.size == 3) parts[0].toLongOrNull() ?: return null else 0L
        val minutes = parts[parts.size - 2].toLongOrNull() ?: return null
        val secParts = parts.last().split('.')
        val seconds = secParts[0].toLongOrNull() ?: return null
        val fraction = secParts.getOrNull(1)?.let { f ->
            if (f.isEmpty() || !f.all(Char::isDigit)) return null
            f.take(3).padEnd(3, '0').toLong()
        } ?: 0L
        return ((hours * 60 + minutes) * 60 + seconds) * 1000 + fraction
    }

    fun format(ms: Long, decimalSeparator: Char): String {
        val v = ms.coerceAtLeast(0)
        val h = v / 3_600_000
        val m = (v / 60_000) % 60
        val s = (v / 1000) % 60
        val f = v % 1000
        return "${pad(h, 2)}:${pad(m, 2)}:${pad(s, 2)}$decimalSeparator${pad(f, 3)}"
    }

    private fun pad(v: Long, width: Int) = v.toString().padStart(width, '0')
}

private fun String.normalizedLines(): List<String> =
    removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n').split('\n')

object WebVtt {

    fun parse(text: String): VttDocument {
        val lines = text.normalizedLines()
        if (lines.firstOrNull()?.startsWith("WEBVTT") != true) throw SubtitleParseException("Missing WEBVTT header")

        var map: TimestampMap? = null
        var i = 1
        // Header block: until the first blank line.
        while (i < lines.size && lines[i].isNotBlank()) {
            val line = lines[i].trim()
            if (line.startsWith("X-TIMESTAMP-MAP=")) map = parseTimestampMap(line.substringAfter('='))
            i++
        }

        val cues = ArrayList<Cue>()
        while (i < lines.size) {
            // Collect one block.
            while (i < lines.size && lines[i].isBlank()) i++
            val block = ArrayList<String>()
            while (i < lines.size && lines[i].isNotBlank()) block += lines[i++]
            if (block.isEmpty()) continue
            if (block[0].startsWith("NOTE") || block[0].startsWith("STYLE") || block[0].startsWith("REGION")) continue

            val timingIndex = block.indexOfFirst { "-->" in it }
            if (timingIndex !in 0..1) continue
            val id = if (timingIndex == 1) block[0].trim() else null
            val timing = block[timingIndex]
            val start = SubtitleTime.parse(timing.substringBefore("-->")) ?: continue
            val afterArrow = timing.substringAfter("-->").trim()
            val end = SubtitleTime.parse(afterArrow.substringBefore(' ').substringBefore('\t')) ?: continue
            val settings = afterArrow.substringAfter(' ', "").trim()
            val cueText = block.drop(timingIndex + 1).joinToString("\n")
            if (end < start) continue
            cues += Cue(start, end, cueText, settings, id)
        }
        return VttDocument(cues, map)
    }

    private fun parseTimestampMap(value: String): TimestampMap? {
        var mpegTs: Long? = null
        var local: Long? = null
        value.split(',').forEach { part ->
            val key = part.substringBefore(':').trim()
            val v = part.substringAfter(':').trim()
            when (key) {
                "MPEGTS" -> mpegTs = v.toLongOrNull()
                "LOCAL" -> local = SubtitleTime.parse(v)
            }
        }
        return if (mpegTs != null && local != null) TimestampMap(mpegTs, local) else null
    }

    fun write(cues: List<Cue>): String = buildString {
        append("WEBVTT\n\n")
        cues.forEach { cue ->
            cue.id?.let { append(it).append('\n') }
            append(SubtitleTime.format(cue.startMs, '.')).append(" --> ").append(SubtitleTime.format(cue.endMs, '.'))
            if (cue.settings.isNotEmpty()) append(' ').append(cue.settings)
            append('\n').append(cue.text).append("\n\n")
        }
    }
}

object SubRip {

    fun parse(text: String): List<Cue> {
        val lines = text.normalizedLines()
        val cues = ArrayList<Cue>()
        var i = 0
        while (i < lines.size) {
            while (i < lines.size && lines[i].isBlank()) i++
            val block = ArrayList<String>()
            while (i < lines.size && lines[i].isNotBlank()) block += lines[i++]
            val timingIndex = block.indexOfFirst { "-->" in it }
            if (timingIndex < 0) continue
            val timing = block[timingIndex]
            val start = SubtitleTime.parse(timing.substringBefore("-->")) ?: continue
            // SRT allows trailing coordinates ("X1:.. X2:..") after the end time.
            val end = SubtitleTime.parse(timing.substringAfter("-->").trim().substringBefore(' ')) ?: continue
            cues += Cue(start, end, escapeForVtt(block.drop(timingIndex + 1).joinToString("\n")))
        }
        return cues
    }

    /** SRT with plain text plus the tags SRT players understand (`<i>`, `<b>`, `<u>`). */
    fun write(cues: List<Cue>): String = buildString {
        cues.forEachIndexed { index, cue ->
            append(index + 1).append('\n')
            append(SubtitleTime.format(cue.startMs, ',')).append(" --> ").append(SubtitleTime.format(cue.endMs, ','))
            append('\n').append(toSrtText(cue.text)).append("\n\n")
        }
    }

    private val keptTags = Regex("""</?(i|b|u)>""", RegexOption.IGNORE_CASE)
    private val anyTag = Regex("""<[^>]*>""")
    private val voiceTag = Regex("""<v(?:\.[^ >]*)?\s+([^>]+)>""")

    /** WebVTT markup → SRT text: keep i/b/u, voice spans become "Name: ", other tags dropped, entities decoded. */
    internal fun toSrtText(vttText: String): String {
        var t = vttText.replace(voiceTag) { "${it.groupValues[1].trim()}: " }
        val kept = ArrayList<String>()
        t = t.replace(keptTags) { kept += it.value.lowercase(); "\u0000${kept.size - 1}\u0000" }
        t = t.replace(anyTag, "")
        t = Regex("\u0000(\\d+)\u0000").replace(t) { kept[it.groupValues[1].toInt()] }
        return t.replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").replace("&lrm;", "‎")
            .replace("&rlm;", "‏").replace("&amp;", "&")
            .lines().joinToString("\n") { it.trimEnd() }.trim('\n')
    }

    /** SRT text is plain text: escape characters that are markup in WebVTT (keeping i/b/u tags). */
    private fun escapeForVtt(srtText: String): String {
        val kept = ArrayList<String>()
        var t = srtText.replace(keptTags) { kept += it.value.lowercase(); "\u0000${kept.size - 1}\u0000" }
        // <font ...> and other HTML-ish tags common in SRT aren't valid VTT: drop them.
        t = t.replace(anyTag, "")
        t = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return Regex("\u0000(\\d+)\u0000").replace(t) { kept[it.groupValues[1].toInt()] }
    }
}

/** Detects the format of a standalone subtitle file and returns its cues. */
object SubtitleFiles {
    fun parse(text: String): List<Cue> {
        val trimmed = text.removePrefix("﻿").trimStart()
        return if (trimmed.startsWith("WEBVTT")) WebVtt.parse(trimmed).cues else SubRip.parse(trimmed).also {
            if (it.isEmpty()) throw SubtitleParseException("Not a WebVTT or SRT subtitle file")
        }
    }

    fun write(cues: List<Cue>, format: SubtitleFormat): String = when (format) {
        SubtitleFormat.VTT -> WebVtt.write(cues)
        SubtitleFormat.SRT -> SubRip.write(cues)
    }
}

package io.github.aalpersen.iptvdler.core.subtitle

import io.github.aalpersen.iptvdler.core.storage.FileNames
import okio.FileSystem
import okio.Path

/** Which subtitle files to write next to a download. */
enum class SubtitleOutput(val formats: List<SubtitleFormat>) {
    SRT(listOf(SubtitleFormat.SRT)),
    VTT(listOf(SubtitleFormat.VTT)),
    BOTH(listOf(SubtitleFormat.SRT, SubtitleFormat.VTT)),
}

data class WrittenSidecars(val vttPath: String?, val srtPath: String?)

/** Writes `Movie.en.srt` / `Movie.en.vtt` next to `Movie.ts` (players pick these up by name). */
object SubtitleSidecars {

    fun write(
        fileSystem: FileSystem,
        videoPath: Path,
        language: String,
        cues: List<Cue>,
        output: SubtitleOutput,
    ): WrittenSidecars {
        val dir = videoPath.parent ?: throw IllegalArgumentException("Video path has no folder: $videoPath")
        val base = videoPath.name.substringBeforeLast('.')
        val lang = FileNames.sanitize(language.ifBlank { "und" }, maxLength = 32)

        // Same language twice (e.g. regular + forced): Movie.en.srt, Movie.en.2.srt ...
        var suffix = lang
        var n = 2
        while (output.formats.any { fileSystem.exists(dir / "$base.$suffix.${it.extension}") }) {
            suffix = "$lang.$n"
            n++
        }

        var vtt: String? = null
        var srt: String? = null
        output.formats.forEach { format ->
            val path = dir / "$base.$suffix.${format.extension}"
            fileSystem.write(path) { writeUtf8(SubtitleFiles.write(cues, format)) }
            when (format) {
                SubtitleFormat.VTT -> vtt = path.toString()
                SubtitleFormat.SRT -> srt = path.toString()
            }
        }
        return WrittenSidecars(vtt, srt)
    }
}

package io.github.aalpersen.iptvdler.player

/**
 * Turns libVLC track descriptions into readable, distinct labels.
 *
 * VLC formats them as `<name> - [<language>]` where the language is localised to VLC's UI language,
 * and uses a generic `<"Track" in the UI language> <n>` when the stream has no name. Examples:
 * "Türkçe - [Türkçe]" → "Türkçe", "English - [İngilizce]" → "English", "Kayıt 1 - [Türkçe]" → "Türkçe".
 */
internal object VlcTrackLabels {

    private val nameAndLanguage = Regex("""^(.*?)\s+-\s+\[(.+)]$""")

    /** "Track 1", "Kayıt 1", "Spur 2", "Piste 3": one or two words followed by a number. */
    private val genericTrack = Regex("""^\p{L}+(?:\s\p{L}+)?\s+\d+$""")

    fun clean(descriptions: List<String>): List<String> {
        val labels = descriptions.map(::cleanOne)
        val seen = HashMap<String, Int>()
        return labels.map { label ->
            val count = (seen[label] ?: 0) + 1
            seen[label] = count
            if (count == 1) label else "$label ($count)"
        }
    }

    private fun cleanOne(description: String): String {
        val text = description.trim()
        val match = nameAndLanguage.matchEntire(text) ?: return text
        val name = match.groupValues[1].trim()
        val language = match.groupValues[2].trim()
        return when {
            name.isEmpty() || genericTrack.matches(name) -> language
            else -> name
        }
    }
}

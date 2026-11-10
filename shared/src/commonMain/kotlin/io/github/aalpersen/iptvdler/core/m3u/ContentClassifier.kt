package io.github.aalpersen.iptvdler.core.m3u

import io.github.aalpersen.iptvdler.core.model.ContentType

/** Best-effort Live / Movie / Series classification from URL patterns, group names and titles. */
object ContentClassifier {

    private val vodExtensions = setOf("mp4", "mkv", "avi", "mov", "m4v", "wmv", "flv", "webm", "mpg", "mpeg")
    private val episodePattern = Regex("""\bS\d{1,2}\s?E\d{1,4}\b""", RegexOption.IGNORE_CASE)
    // Whole words only, so e.g. "Vodafone" isn't taken for "VOD".
    private val seriesGroupPattern =
        Regex("""\b(series|serie|tv ?shows?|seasons?|dizi|diziler)\b""", RegexOption.IGNORE_CASE)
    private val movieGroupPattern =
        Regex("""\b(movies?|films?|filmler|vod|cinema|sinema)\b""", RegexOption.IGNORE_CASE)

    fun classify(url: String, group: String?, title: String): ContentType {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        // Xtream-style URL layouts are the most reliable signal.
        when {
            "/live/" in path -> return ContentType.LIVE
            "/series/" in path -> return ContentType.SERIES
            "/movie/" in path || "/movies/" in path -> return ContentType.MOVIE
        }

        val groupName = group.orEmpty()
        if (episodePattern.containsMatchIn(title) || seriesGroupPattern.containsMatchIn(groupName)) {
            return ContentType.SERIES
        }

        val extension = path.substringAfterLast('/').substringAfterLast('.', missingDelimiterValue = "")
        if (extension in vodExtensions || movieGroupPattern.containsMatchIn(groupName)) return ContentType.MOVIE

        return ContentType.LIVE
    }
}

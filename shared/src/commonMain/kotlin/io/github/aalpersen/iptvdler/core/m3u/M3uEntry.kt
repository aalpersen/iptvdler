package io.github.aalpersen.iptvdler.core.m3u

/** Attributes of the `#EXTM3U` header line. */
data class M3uHeader(val attributes: Map<String, String>) {
    /** XMLTV guide URLs from `url-tvg` / `x-tvg-url` (either may hold a comma-separated list). */
    val epgUrls: List<String>
        get() = listOfNotNull(attributes["url-tvg"], attributes["x-tvg-url"])
            .flatMap { it.split(',') }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
}

/** One playlist item: its `#EXTINF` metadata plus the stream URL that followed it. */
data class M3uEntry(
    val title: String,
    /** Stream URL with any `|Header=value` suffix removed. */
    val url: String,
    /** `#EXTINF` duration; -1 (live/unknown) is kept as-is. */
    val durationSeconds: Double?,
    /** `#EXTINF` attributes with lower-cased keys (`tvg-id`, `group-title`, ...). */
    val attributes: Map<String, String>,
    /** `group-title`, falling back to `#EXTGRP`. */
    val group: String?,
    /** HTTP headers from `#EXTVLCOPT`, `#KODIPROP` and pipe syntax, with canonical names. */
    val headers: Map<String, String>,
    /** Raw `#KODIPROP` key/values (lower-cased keys). */
    val kodiProps: Map<String, String>,
) {
    val tvgId: String? get() = attribute("tvg-id")
    val tvgName: String? get() = attribute("tvg-name")
    val tvgLogo: String? get() = attribute("tvg-logo")
    val catchup: String? get() = attribute("catchup")
    val catchupSource: String? get() = attribute("catchup-source")

    private fun attribute(key: String): String? = attributes[key]?.takeIf { it.isNotBlank() }
}

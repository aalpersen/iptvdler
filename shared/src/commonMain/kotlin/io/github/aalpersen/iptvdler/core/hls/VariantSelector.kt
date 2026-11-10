package io.github.aalpersen.iptvdler.core.hls

sealed interface QualityPreference {
    data object Highest : QualityPreference
    data object Lowest : QualityPreference

    /** Best variant whose height is at most [maxHeight] (falls back to the lowest one). */
    data class MaxHeight(val maxHeight: Int) : QualityPreference
}

/** What to download/play from a master playlist. */
data class StreamSelection(
    val variant: Variant,
    /** Separate audio rendition to fetch, or null when audio is muxed into [variant]. */
    val audio: Rendition?,
    val subtitles: List<Rendition>,
) {
    /** Video and audio come from different playlists (needs side-by-side files or a remux). */
    val hasSeparateAudio: Boolean get() = audio != null
}

object VariantSelector {

    /**
     * Variants ordered best-first: video before audio-only, then height, then widely supported audio
     * (AAC over AC-3/E-AC-3 duplicates), then bandwidth.
     */
    fun ranked(master: MasterPlaylist): List<Variant> =
        master.variants.sortedWith(
            compareBy<Variant> { it.isAudioOnly }
                .thenByDescending { it.resolution?.height ?: 0 }
                .thenBy { !it.hasWidelySupportedAudio }
                .thenByDescending { it.bandwidth },
        )

    /**
     * The choices worth showing a user: video variants best-first, without the same picture offered
     * again with only a different audio codec (kept only when no widely supported alternative exists).
     */
    fun qualityOptions(master: MasterPlaylist): List<Variant> {
        val video = ranked(master).filterNot { it.isAudioOnly }
        return video.filter { variant ->
            variant.hasWidelySupportedAudio || video.none { other ->
                other.hasWidelySupportedAudio && other.resolution == variant.resolution && other.videoCodecFamily == variant.videoCodecFamily
            }
        }
    }

    /** "1080p · 8.0 Mbps", plus the codec family when [options] mix codecs at that height. */
    fun label(variant: Variant, options: List<Variant>): String {
        val height = variant.resolution?.height
        val codec = variant.videoCodecFamily?.takeIf {
            options.any { o -> o.resolution?.height == height && o.videoCodecFamily != null && o.videoCodecFamily != it }
        }
        return listOfNotNull(
            height?.let { "${it}p" },
            codec,
            formatMbps(variant.averageBandwidth ?: variant.bandwidth),
        ).joinToString(" · ")
    }

    private fun formatMbps(bps: Long): String =
        if (bps >= 950_000) {
            val tenths = (bps + 50_000) / 100_000
            "${tenths / 10}.${tenths % 10} Mbps"
        } else {
            "${(bps + 500) / 1000} kbps"
        }

    fun selectVariant(master: MasterPlaylist, preference: QualityPreference = QualityPreference.Highest): Variant? {
        // Same list the user sees: no AC-3-only duplicates at either end.
        val ranked = qualityOptions(master).ifEmpty { ranked(master) }
        return when (preference) {
            QualityPreference.Highest -> ranked.firstOrNull()
            QualityPreference.Lowest -> ranked.lastOrNull()
            is QualityPreference.MaxHeight ->
                ranked.firstOrNull { (it.resolution?.height ?: 0) <= preference.maxHeight } ?: ranked.lastOrNull()
        }
    }

    /**
     * Picks the variant plus renditions. Audio: [audioLanguage] → DEFAULT=YES → first.
     * An audio group whose renditions have no URI means the audio is muxed into the variant.
     * Subtitles: renditions of the variant's SUBTITLES group matching [subtitleLanguages] (all if empty).
     */
    fun select(
        master: MasterPlaylist,
        preference: QualityPreference = QualityPreference.Highest,
        audioLanguage: String? = null,
        subtitleLanguages: Set<String> = emptySet(),
    ): StreamSelection? {
        val variant = selectVariant(master, preference) ?: return null

        val audioOptions = master.renditions(RenditionType.AUDIO, variant.audioGroup).filter { it.uri != null }
        val audio = audioOptions.firstOrNull { audioLanguage != null && it.language.matchesLanguage(audioLanguage) }
            ?: audioOptions.firstOrNull { it.isDefault }
            ?: audioOptions.firstOrNull()

        val subtitles = master.renditions(RenditionType.SUBTITLES, variant.subtitlesGroup)
            .filter { it.uri != null }
            .filter { subtitleLanguages.isEmpty() || subtitleLanguages.any { lang -> it.language.matchesLanguage(lang) } }

        return StreamSelection(variant, audio, subtitles)
    }

    /**
     * Estimated download size: bandwidth × duration. Uses AVERAGE-BANDWIDTH when present (BANDWIDTH is a peak).
     * [audio] adds nothing measurable here since variant bandwidth already covers its audio group.
     */
    fun estimateBytes(variant: Variant, durationSeconds: Double): Long =
        ((variant.averageBandwidth ?: variant.bandwidth) * durationSeconds / 8).toLong()

    /** "en" matches "en", "EN", "en-US". */
    private fun String?.matchesLanguage(wanted: String): Boolean {
        val language = this?.lowercase() ?: return false
        val target = wanted.lowercase()
        return language == target || language.substringBefore('-') == target.substringBefore('-')
    }
}

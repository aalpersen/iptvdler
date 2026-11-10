package io.github.aalpersen.iptvdler.core.hls

/** A parsed HLS playlist. All URIs inside are absolute (resolved against [uri]). */
sealed interface HlsPlaylist {
    /** The playlist's own URL (after redirects); relative URIs were resolved against it. */
    val uri: String
}

data class MasterPlaylist(
    override val uri: String,
    val variants: List<Variant>,
    val renditions: List<Rendition>,
    /** `#EXT-X-SESSION-KEY`: keys announced up front for all media playlists. */
    val sessionKeys: List<HlsKey>,
) : HlsPlaylist {
    fun renditions(type: RenditionType, groupId: String?): List<Rendition> =
        if (groupId == null) emptyList() else renditions.filter { it.type == type && it.groupId == groupId }
}

data class Resolution(val width: Int, val height: Int)

/** `#EXT-X-STREAM-INF` + its URI line. */
data class Variant(
    val uri: String,
    val bandwidth: Long,
    val averageBandwidth: Long? = null,
    val resolution: Resolution? = null,
    val codecs: String? = null,
    val frameRate: Double? = null,
    val audioGroup: String? = null,
    val videoGroup: String? = null,
    val subtitlesGroup: String? = null,
    /** Group id, or null for none (including `CLOSED-CAPTIONS=NONE`). */
    val closedCaptionsGroup: String? = null,
) {
    /** Video codec family from CODECS ("H.264", "HEVC", ...), or null if unknown. */
    val videoCodecFamily: String?
        get() = codecs?.split(',')?.map { it.trim().lowercase() }?.firstNotNullOfOrNull { codec ->
            when {
                codec.startsWith("avc1") || codec.startsWith("avc3") -> "H.264"
                codec.startsWith("hvc1") || codec.startsWith("hev1") -> "HEVC"
                codec.startsWith("dvh1") || codec.startsWith("dvhe") || codec.startsWith("dva1") || codec.startsWith("dvav") -> "Dolby Vision"
                codec.startsWith("av01") -> "AV1"
                codec.startsWith("vp09") || codec.startsWith("vp9") -> "VP9"
                else -> null
            }
        }

    /**
     * True unless CODECS names audio and none of it is AAC/MP3 (e.g. AC-3/E-AC-3 only), which some
     * players and devices can't decode.
     */
    val hasWidelySupportedAudio: Boolean
        get() {
            val audio = codecs?.split(',')?.map { it.trim().lowercase() }?.filter { it.isAudioCodec() } ?: return true
            return audio.isEmpty() || audio.any { it.startsWith("mp4a") || it.startsWith("mp3") }
        }

    /** True when CODECS lists only audio codecs (an audio-only variant). */
    val isAudioOnly: Boolean
        get() {
            val codecList = codecs?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() } ?: return false
            return resolution == null && codecList.isNotEmpty() && codecList.all { it.isAudioCodec() }
        }
}

private fun String.isAudioCodec() =
    startsWith("mp4a") || startsWith("ac-3") || startsWith("ec-3") || startsWith("opus") || startsWith("flac") ||
        startsWith("mp3") || startsWith("ac-4") || startsWith("alac") || startsWith("dts")

enum class RenditionType { AUDIO, VIDEO, SUBTITLES, CLOSED_CAPTIONS }

/** `#EXT-X-MEDIA`. */
data class Rendition(
    val type: RenditionType,
    val groupId: String,
    val name: String,
    val language: String? = null,
    /** Absolute URI; null when the rendition is carried inside the variant stream (e.g. muxed audio, CEA-608). */
    val uri: String? = null,
    val isDefault: Boolean = false,
    val autoSelect: Boolean = false,
    val forced: Boolean = false,
    val instreamId: String? = null,
    val channels: String? = null,
    val characteristics: String? = null,
)

enum class PlaylistType { VOD, EVENT }

data class MediaPlaylist(
    override val uri: String,
    val targetDurationSeconds: Double,
    /** Media sequence number of the first segment. */
    val mediaSequence: Long,
    val discontinuitySequence: Long,
    val playlistType: PlaylistType?,
    val hasEndList: Boolean,
    val segments: List<Segment>,
    val version: Int? = null,
    val iFramesOnly: Boolean = false,
) : HlsPlaylist {
    /** Live = the playlist can still grow. */
    val isLive: Boolean get() = !hasEndList && playlistType != PlaylistType.VOD

    val totalDurationSeconds: Double get() = segments.sumOf { it.durationSeconds }

    /** fMP4 when any segment has an `#EXT-X-MAP` init section, otherwise MPEG-TS (or packed audio). */
    val isFragmentedMp4: Boolean get() = segments.any { it.initSegment != null }
}

data class ByteRange(val length: Long, val offset: Long) {
    val endExclusive: Long get() = offset + length
}

enum class KeyMethod { NONE, AES_128, SAMPLE_AES, SAMPLE_AES_CTR, UNKNOWN }

/** `#EXT-X-KEY` / `#EXT-X-SESSION-KEY`. */
data class HlsKey(
    val method: KeyMethod,
    /** Absolute key URI (may be a non-HTTP scheme like `skd://` or `data:`). */
    val uri: String? = null,
    /** 32 hex chars (16 bytes) from the IV attribute, without the 0x prefix; null = derive from sequence. */
    val ivHex: String? = null,
    val keyFormat: String = IDENTITY_KEY_FORMAT,
    val keyFormatVersions: String? = null,
    /** Raw METHOD value, kept for error messages when [method] is UNKNOWN. */
    val rawMethod: String = method.name,
) {
    val isIdentity: Boolean get() = keyFormat.equals(IDENTITY_KEY_FORMAT, ignoreCase = true)

    companion object {
        const val IDENTITY_KEY_FORMAT = "identity"
    }
}

/** `#EXT-X-MAP`: fMP4 initialization section. */
data class InitSegment(
    val uri: String,
    val byteRange: ByteRange? = null,
    /** Keys in effect where the map tag appeared (an encrypted init section). */
    val keys: List<HlsKey> = emptyList(),
)

data class Segment(
    val uri: String,
    val durationSeconds: Double,
    val mediaSequence: Long,
    val title: String? = null,
    val byteRange: ByteRange? = null,
    /** Keys in effect for this segment (normally 0 or 1; several when multiple KEYFORMATs are offered). */
    val keys: List<HlsKey> = emptyList(),
    val initSegment: InitSegment? = null,
    /** `#EXT-X-DISCONTINUITY` appeared before this segment. */
    val discontinuity: Boolean = false,
    val discontinuitySequence: Long = 0,
    val programDateTime: String? = null,
    val gap: Boolean = false,
) {
    /** The clear-key AES-128 key iptvdler can decrypt, if the segment is encrypted that way. */
    val aes128Key: HlsKey? get() = keys.firstOrNull { it.method == KeyMethod.AES_128 && it.isIdentity }
}

class HlsParseException(message: String) : Exception(message)

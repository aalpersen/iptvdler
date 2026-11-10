package io.github.aalpersen.iptvdler.core.hls

import io.github.aalpersen.iptvdler.i18n.strings

/** Whether iptvdler may download a stream, based on its encryption. */
sealed interface EncryptionStatus {
    /** Downloading allowed. */
    val downloadable: Boolean

    data object Clear : EncryptionStatus {
        override val downloadable = true
    }

    /** Plain HLS AES-128 with a key URI — part of the HLS spec; downloadable. */
    data object Aes128 : EncryptionStatus {
        override val downloadable = true
    }

    /** DRM-protected (Widevine, PlayReady, FairPlay, SAMPLE-AES...). Never downloadable. */
    data class Drm(val system: String) : EncryptionStatus {
        override val downloadable = false
    }

    /** Encryption iptvdler doesn't recognise; treated like DRM (refused). */
    data class Unsupported(val description: String) : EncryptionStatus {
        override val downloadable = false
    }
}

val DRM_DOWNLOAD_REFUSED_MESSAGE: String get() = strings.drmDownloadRefused

object DrmDetector {

    private const val WIDEVINE_UUID = "urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"
    private const val PLAYREADY_UUID = "urn:uuid:9a04f079-9840-4286-ab92-e65be0885f95"
    private const val PLAYREADY_FORMAT = "com.microsoft.playready"
    private const val FAIRPLAY_FORMAT = "com.apple.streamingkeydelivery"

    fun check(playlist: HlsPlaylist): EncryptionStatus = when (playlist) {
        is MasterPlaylist -> check(playlist.sessionKeys)
        is MediaPlaylist -> check(
            playlist.segments.flatMap { it.keys } + playlist.segments.mapNotNull { it.initSegment }.flatMap { it.keys },
        )
    }

    /** Combined status for a master playlist and the media playlist(s) chosen from it. */
    fun checkStream(master: MasterPlaylist?, vararg media: MediaPlaylist): EncryptionStatus =
        worst(listOfNotNull(master?.let { check(it) }) + media.map { check(it) })

    fun check(keys: List<HlsKey>): EncryptionStatus = worst(keys.map(::classify))

    fun classify(key: HlsKey): EncryptionStatus {
        drmSystem(key)?.let { return EncryptionStatus.Drm(it) }
        return when (key.method) {
            KeyMethod.NONE -> EncryptionStatus.Clear
            KeyMethod.AES_128 ->
                if (key.uri != null) EncryptionStatus.Aes128 else EncryptionStatus.Unsupported("AES-128 key without URI")
            KeyMethod.SAMPLE_AES, KeyMethod.SAMPLE_AES_CTR -> EncryptionStatus.Drm("SAMPLE-AES")
            KeyMethod.UNKNOWN -> EncryptionStatus.Unsupported("METHOD=${key.rawMethod}")
        }
    }

    private fun drmSystem(key: HlsKey): String? {
        val format = key.keyFormat.lowercase()
        return when {
            format == WIDEVINE_UUID -> "Widevine"
            format == PLAYREADY_UUID || format == PLAYREADY_FORMAT -> "PlayReady"
            format == FAIRPLAY_FORMAT || key.uri?.startsWith("skd:", ignoreCase = true) == true -> "FairPlay"
            // Any other non-identity key system is a DRM scheme iptvdler must not touch.
            key.method != KeyMethod.NONE && !key.isIdentity -> "Unknown DRM (${key.keyFormat})"
            else -> null
        }
    }

    private fun worst(statuses: List<EncryptionStatus>): EncryptionStatus =
        statuses.firstOrNull { it is EncryptionStatus.Drm }
            ?: statuses.firstOrNull { it is EncryptionStatus.Unsupported }
            ?: statuses.firstOrNull { it is EncryptionStatus.Aes128 }
            ?: EncryptionStatus.Clear
}

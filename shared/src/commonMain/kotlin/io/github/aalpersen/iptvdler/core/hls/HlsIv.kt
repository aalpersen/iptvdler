package io.github.aalpersen.iptvdler.core.hls

/** AES-128 initialization vectors for HLS segments (RFC 8216 §5.2). */
object HlsIv {

    /** The key's explicit IV if present, otherwise the segment's media sequence number. */
    fun forSegment(key: HlsKey, mediaSequence: Long): ByteArray =
        key.ivHex?.let(::fromHex) ?: fromSequence(mediaSequence)

    /** The media sequence number as a 16-byte big-endian value (upper 8 bytes zero). */
    fun fromSequence(mediaSequence: Long): ByteArray {
        val iv = ByteArray(16)
        for (i in 0 until 8) {
            iv[15 - i] = (mediaSequence ushr (8 * i)).toByte()
        }
        return iv
    }

    fun fromHex(hex: String): ByteArray {
        val padded = hex.padStart(32, '0')
        require(padded.length == 32) { "IV must be at most 16 bytes" }
        return ByteArray(16) { i -> padded.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}

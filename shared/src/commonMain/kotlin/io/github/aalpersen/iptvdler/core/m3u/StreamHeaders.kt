package io.github.aalpersen.iptvdler.core.m3u

import okio.Buffer

object StreamHeaders {
    const val USER_AGENT = "User-Agent"
    const val REFERER = "Referer"
    const val ORIGIN = "Origin"
    const val COOKIE = "Cookie"

    /** Canonical spelling for common header names; other names are returned trimmed. */
    fun canonicalName(raw: String): String = when (raw.trim().lowercase()) {
        "user-agent", "useragent" -> USER_AGENT
        "referer", "referrer" -> REFERER
        "origin" -> ORIGIN
        "cookie" -> COOKIE
        else -> raw.trim()
    }

    /** Maps a `#EXTVLCOPT` option name to a header name, or null if it isn't an HTTP header option. */
    fun fromVlcOption(option: String): String? = when (option.trim().lowercase()) {
        "http-user-agent" -> USER_AGENT
        "http-referrer", "http-referer" -> REFERER
        "http-origin" -> ORIGIN
        "http-cookie" -> COOKIE
        else -> null
    }

    /**
     * Parses `Name=value&Name2=value2` as used after `url|` and in
     * `#KODIPROP:inputstream.adaptive.stream_headers=`. Values are percent-decoded.
     */
    fun parseHeaderString(text: String): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        for (part in text.split('&')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val name = part.substring(0, eq).trim()
            if (name.isEmpty()) continue
            headers[canonicalName(percentDecode(name))] = percentDecode(part.substring(eq + 1).trim())
        }
        return headers
    }

    /** Decodes `%XX` escapes as UTF-8; malformed escapes are kept literally. `+` is not treated as space. */
    fun percentDecode(text: String): String {
        if ('%' !in text) return text
        val out = Buffer()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '%' && i + 2 < text.length) {
                val hi = text[i + 1].digitToIntOrNull(16)
                val lo = text[i + 2].digitToIntOrNull(16)
                if (hi != null && lo != null) {
                    out.writeByte(hi * 16 + lo)
                    i += 3
                    continue
                }
            }
            val codePoint = text.codePointAt(i)
            out.writeUtf8CodePoint(codePoint)
            i += if (codePoint > 0xFFFF) 2 else 1
        }
        return out.readUtf8()
    }

    private fun String.codePointAt(index: Int): Int {
        val high = this[index]
        if (high.isHighSurrogate() && index + 1 < length) {
            val low = this[index + 1]
            if (low.isLowSurrogate()) return 0x10000 + ((high.code - 0xD800) shl 10) + (low.code - 0xDC00)
        }
        return high.code
    }
}

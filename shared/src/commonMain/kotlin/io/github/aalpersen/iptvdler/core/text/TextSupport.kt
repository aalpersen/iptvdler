package io.github.aalpersen.iptvdler.core.text

/** Unicode canonical decomposition (NFD): "ü" → "u" + U+0308. */
internal expect fun decomposeUnicode(text: String): String

/** Decodes [bytes] with a named charset (e.g. "windows-1254"). */
internal expect fun decodeCharset(bytes: ByteArray, charsetName: String): String

/**
 * Accent-, case- and dot-insensitive form for searching: "İSTANBUL", "istanbul" and "Istanbul" all
 * become "istanbul"; "Türkçe" and "turkce" both become "turkce". Locale-independent (no Turkish-I bug).
 */
object SearchText {
    private val combiningMarks = Regex("\\p{Mn}+")

    fun fold(text: String): String =
        decomposeUnicode(text)
            .replace(combiningMarks, "")
            .lowercase()
            .replace('ı', 'i') // dotless i (Turkish) has no decomposition
            .replace("ß", "ss")
}

/**
 * Decodes subtitle files that may not be UTF-8. Order: BOM (UTF-8/UTF-16), valid UTF-8, then the
 * legacy Windows code page usual for the subtitle's [language] (Turkish SRTs are often windows-1254).
 */
object SubtitleText {

    fun decode(bytes: ByteArray, language: String?): String {
        when {
            bytes.startsWith(0xEF, 0xBB, 0xBF) -> return bytes.decodeToString(3, bytes.size)
            bytes.startsWith(0xFF, 0xFE) -> return decodeCharset(bytes.copyOfRange(2, bytes.size), "UTF-16LE")
            bytes.startsWith(0xFE, 0xFF) -> return decodeCharset(bytes.copyOfRange(2, bytes.size), "UTF-16BE")
        }
        runCatching { return bytes.decodeToString(throwOnInvalidSequence = true) }
        return decodeCharset(bytes, legacyCodePage(language))
    }

    fun legacyCodePage(language: String?): String = when (language?.lowercase()?.substringBefore('-')?.substringBefore('_')) {
        "tr", "tur", "az" -> "windows-1254"
        "ru", "rus", "uk", "ukr", "bg", "bul", "sr", "srp", "mk", "mkd", "be", "bel" -> "windows-1251"
        "el", "gre", "ell" -> "windows-1253"
        "he", "heb" -> "windows-1255"
        "ar", "ara", "fa", "fas", "per", "ur", "urd" -> "windows-1256"
        "pl", "pol", "cs", "ces", "cze", "sk", "slk", "hu", "hun", "ro", "ron", "rum", "hr", "hrv", "sl", "slv", "sq", "sqi" ->
            "windows-1250"
        "lt", "lit", "lv", "lav", "et", "est" -> "windows-1257"
        "vi", "vie" -> "windows-1258"
        else -> "windows-1252"
    }

    private fun ByteArray.startsWith(vararg prefix: Int) =
        size >= prefix.size && prefix.indices.all { (this[it].toInt() and 0xFF) == prefix[it] }
}

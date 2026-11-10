package io.github.aalpersen.iptvdler.core.text

import java.nio.charset.Charset
import java.text.Normalizer

internal actual fun decomposeUnicode(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD)

internal actual fun decodeCharset(bytes: ByteArray, charsetName: String): String = String(bytes, Charset.forName(charsetName))

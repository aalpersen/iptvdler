package io.github.aalpersen.iptvdler.core.text

import kotlin.test.Test
import kotlin.test.assertEquals

class TextSupportTest {

    @Test
    fun foldsTurkishAndAccentsLocaleIndependently() {
        assertEquals("istanbul", SearchText.fold("İSTANBUL"))
        assertEquals("istanbul", SearchText.fold("istanbul"))
        assertEquals("isik", SearchText.fold("IŞIK"))
        assertEquals("isik", SearchText.fold("ışık"))
        assertEquals("turkce haber", SearchText.fold("TÜRKÇE Haber"))
        assertEquals("cagri", SearchText.fold("Çağrı"))
        assertEquals("cafe strasse", SearchText.fold("Café Straße"))
        assertEquals("fr24 hd", SearchText.fold("FR24 HD"))
    }

    // "Türkçe altyazı: ğüşıöç İĞÜŞÖÇ" encoded as windows-1254
    private val turkishCp1254 = byteArrayOf(
        0x54, 0xFC.toByte(), 0x72, 0x6B, 0xE7.toByte(), 0x65, 0x20, 0x61, 0x6C, 0x74, 0x79, 0x61, 0x7A, 0xFD.toByte(), 0x3A, 0x20,
        0xF0.toByte(), 0xFC.toByte(), 0xFE.toByte(), 0xFD.toByte(), 0xF6.toByte(), 0xE7.toByte(), 0x20,
        0xDD.toByte(), 0xD0.toByte(), 0xDC.toByte(), 0xDE.toByte(), 0xD6.toByte(), 0xC7.toByte(),
    )
    private val expected = "Türkçe altyazı: ğüşıöç İĞÜŞÖÇ"

    @Test
    fun decodesLegacyTurkishSubtitlesByLanguage() {
        assertEquals(expected, SubtitleText.decode(turkishCp1254, "tr"))
        assertEquals("windows-1252", SubtitleText.legacyCodePage(null))
        assertEquals("windows-1251", SubtitleText.legacyCodePage("ru-RU"))
    }

    @Test
    fun prefersBomAndValidUtf8() {
        val utf8 = expected.encodeToByteArray()
        assertEquals(expected, SubtitleText.decode(utf8, "tr"))
        assertEquals(expected, SubtitleText.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + utf8, "en"))

        val utf16le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + expected.flatMap { listOf(it.code.toByte(), (it.code shr 8).toByte()) }
        assertEquals(expected, SubtitleText.decode(utf16le, null))
    }
}

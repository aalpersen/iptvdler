package io.github.aalpersen.iptvdler.i18n

import io.github.aalpersen.iptvdler.core.error.UserMessages
import io.github.aalpersen.iptvdler.core.hls.DRM_DOWNLOAD_REFUSED_MESSAGE
import io.github.aalpersen.iptvdler.ui.formatBitrate
import io.github.aalpersen.iptvdler.ui.formatBytes
import io.github.aalpersen.iptvdler.ui.formatCount
import io.github.aalpersen.iptvdler.ui.formatEta
import io.github.aalpersen.iptvdler.ui.formatRelative
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class I18nTest {

    @AfterTest
    fun reset() = I18n.apply(AppLanguage.ENGLISH)

    @Test
    fun systemLanguageAndTags() {
        I18n.apply(AppLanguage.SYSTEM, systemLanguage = "tr")
        assertSame(TrStrings, I18n.strings)
        I18n.apply(AppLanguage.SYSTEM, systemLanguage = "de")
        assertSame(EnStrings, I18n.strings) // unsupported languages fall back to English
        I18n.apply(AppLanguage.ENGLISH, systemLanguage = "tr")
        assertSame(EnStrings, I18n.strings) // an explicit choice wins over the system
        assertSame(TrStrings, I18n.forTag("tr-TR"))
        assertSame(TrStrings, I18n.forTag("TR_tr"))
    }

    @Test
    fun turkishNumberFormatting() {
        I18n.apply(AppLanguage.TURKISH)
        assertEquals("1,5 KB", formatBytes(1536))
        assertEquals("12.345", formatCount(12_345))
        assertEquals("5,2 Mbps", formatBitrate(5_200_000))
        assertEquals("1 sa 20 dk", formatEta(4_800))
        assertEquals("5 dk önce", formatRelative(0, 5 * 60_000))
        assertEquals("%45", TrStrings.percent(45))
    }

    @Test
    fun englishStaysTheDefault() {
        assertEquals("1.5 KB", formatBytes(1536))
        assertEquals("12,345", formatCount(12_345))
        assertEquals("This stream is DRM-protected and can't be downloaded.", DRM_DOWNLOAD_REFUSED_MESSAGE)
    }

    @Test
    fun messagesFollowTheLanguage() {
        I18n.apply(AppLanguage.TURKISH)
        assertEquals("Bu yayın DRM korumalı ve indirilemez.", DRM_DOWNLOAD_REFUSED_MESSAGE)
        assertEquals("Sunucuda bulunamadı (HTTP 404). Bağlantının süresi dolmuş olabilir.", UserMessages.httpStatus(404))
    }

    @Test
    fun noBlankTranslations() {
        listOf(EnStrings, TrStrings).forEach { strings ->
            // Every text property (functions are covered by the tests above and the UI tests).
            Strings::class.java.methods
                .filter { it.parameterCount == 0 && it.returnType == String::class.java }
                .forEach { getter ->
                    val value = getter.invoke(strings) as String
                    kotlin.test.assertTrue(value.isNotBlank(), "${strings::class.simpleName}.${getter.name} is blank")
                }
        }
    }
}

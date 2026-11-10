package io.github.aalpersen.iptvdler.player

import kotlin.test.Test
import kotlin.test.assertEquals

class VlcTrackLabelsTest {

    @Test
    fun cleansVlcDescriptionsLikeTheScreenshot() {
        assertEquals(
            listOf("Türkçe", "English"),
            VlcTrackLabels.clean(listOf("Türkçe - [Türkçe]", "English - [İngilizce]")),
        )
        assertEquals(
            listOf("English", "Türkçe", "Türkçe (2)"),
            VlcTrackLabels.clean(listOf("English - [İngilizce]", "Türkçe - [Türkçe]", "Türkçe - [Türkçe]")),
        )
    }

    @Test
    fun genericTrackNamesFallBackToTheLanguage() {
        assertEquals(
            listOf("Türkçe", "English", "Deutsch", "Kayıt 1"),
            VlcTrackLabels.clean(listOf("Kayıt 1 - [Türkçe]", "Track 2 - [English]", "Spur 3 - [Deutsch]", "Kayıt 1")),
        )
    }

    @Test
    fun keepsUnusualNames() {
        assertEquals(
            listOf("Director's commentary", "5.1 Surround"),
            VlcTrackLabels.clean(listOf("Director's commentary - [English]", "5.1 Surround")),
        )
    }
}

package io.github.aalpersen.iptvdler.data.settings

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppSettingsTest {

    @Test
    fun disclaimerIsNotAcceptedByDefault() {
        assertFalse(AppSettings(MapSettings()).disclaimerAccepted.value)
    }

    @Test
    fun acceptingDisclaimerPersists() {
        val store = MapSettings()
        AppSettings(store).acceptDisclaimer()

        assertTrue(AppSettings(store).disclaimerAccepted.value)
    }

    @Test
    fun wifiOnlyDefaultsPerPlatformButTheUsersChoiceWins() {
        assertTrue(AppSettings(MapSettings(), defaultWifiOnly = true).wifiOnly.value) // phones
        assertFalse(AppSettings(MapSettings()).wifiOnly.value) // desktop

        val stored = MapSettings()
        AppSettings(stored, defaultWifiOnly = true).setWifiOnly(false)
        assertFalse(AppSettings(stored, defaultWifiOnly = true).wifiOnly.value)
    }
}

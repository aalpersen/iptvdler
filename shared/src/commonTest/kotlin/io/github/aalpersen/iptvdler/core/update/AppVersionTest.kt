package io.github.aalpersen.iptvdler.core.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppVersionTest {

    @Test
    fun parsesPlainPrefixedAndPreReleaseVersions() {
        assertEquals(AppVersion(listOf(1, 2, 0), null), AppVersion.parse("1.2.0"))
        assertEquals(AppVersion(listOf(1, 2, 0), null), AppVersion.parse("v1.2.0"))
        assertEquals(AppVersion(listOf(0, 0, 0), "test"), AppVersion.parse("v0.0.0-test"))
        assertNull(AppVersion.parse("latest"))
        assertNull(AppVersion.parse("1..2"))
        assertNull(AppVersion.parse(""))
    }

    @Test
    fun comparesNumericallyNotAsText() {
        assertTrue(AppVersion.isNewer("0.10.0", "0.9.0"))
        assertTrue(AppVersion.isNewer("1.0.0", "0.99.99"))
        assertTrue(AppVersion.isNewer("0.1.1", "0.1.0"))
        assertFalse(AppVersion.isNewer("0.1.0", "0.1.0"))
        assertFalse(AppVersion.isNewer("0.1.0", "0.2.0"))
    }

    @Test
    fun missingPartsCountAsZero() {
        assertFalse(AppVersion.isNewer("1.2", "1.2.0"))
        assertTrue(AppVersion.isNewer("1.2.1", "1.2"))
    }

    @Test
    fun preReleaseIsOlderThanTheRelease() {
        assertTrue(AppVersion.isNewer("1.0.0", "1.0.0-beta"))
        assertFalse(AppVersion.isNewer("1.0.0-beta", "1.0.0"))
        assertTrue(AppVersion.isNewer("1.0.0-beta2", "1.0.0-beta1"))
    }

    @Test
    fun unparseableVersionsAreNeverNewer() {
        assertFalse(AppVersion.isNewer("nightly", "0.1.0"))
        assertFalse(AppVersion.isNewer("0.2.0", "dev"))
    }
}

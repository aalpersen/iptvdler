package io.github.aalpersen.iptvdler.core.m3u

import kotlin.test.Test
import kotlin.test.assertEquals

class StreamHeadersTest {

    @Test
    fun percentDecodesUtf8AndKeepsMalformedEscapes() {
        assertEquals("a b/c", StreamHeaders.percentDecode("a%20b%2Fc"))
        assertEquals("✓ ş", StreamHeaders.percentDecode("%E2%9C%93%20%C5%9F"))
        assertEquals("100%", StreamHeaders.percentDecode("100%"))
        assertEquals("%zz ok", StreamHeaders.percentDecode("%zz%20ok"))
        assertEquals("plus+stays", StreamHeaders.percentDecode("plus+stays"))
    }

    @Test
    fun canonicalizesCommonHeaderNames() {
        assertEquals(
            mapOf("User-Agent" to "ua", "Referer" to "r", "Origin" to "o", "X-Token" to "t"),
            StreamHeaders.parseHeaderString("user-agent=ua&referrer=r&ORIGIN=o&X-Token=t&=ignored&novalue"),
        )
    }

    @Test
    fun mapsOnlyHttpVlcOptions() {
        assertEquals("User-Agent", StreamHeaders.fromVlcOption("http-user-agent"))
        assertEquals("Referer", StreamHeaders.fromVlcOption("http-referrer"))
        assertEquals(null, StreamHeaders.fromVlcOption("network-caching"))
    }
}

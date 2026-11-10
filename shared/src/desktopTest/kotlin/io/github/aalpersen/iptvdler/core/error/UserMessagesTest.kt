package io.github.aalpersen.iptvdler.core.error

import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UserMessagesTest {

    @Test
    fun httpStatuses() {
        assertTrue(UserMessages.httpStatus(403).startsWith("Access denied"))
        assertTrue(UserMessages.httpStatus(404).contains("HTTP 404"))
        assertTrue(UserMessages.httpStatus(503).startsWith("The server had a problem"))
        assertEquals("Server returned HTTP 418", UserMessages.httpStatus(418))
    }

    @Test
    fun platformExceptions() {
        assertEquals(UserMessages.NO_HOST, UserMessages.describe(UnknownHostException("iptv.example: nodename nor servname")))
        assertEquals(UserMessages.NO_CONNECTION, UserMessages.describe(ConnectException("Connection refused")))
        assertEquals(UserMessages.STORAGE_FULL, UserMessages.describe(IOException("There is not enough space on the disk")))
        val denied = IOException("failed to create directory: /storage/emulated/0/Android/data/x/files/.iptvdler-tmp")
        assertEquals(UserMessages.STORAGE_ACCESS, UserMessages.describe(denied))
        assertTrue(UserMessages.isStorageError(denied))
        assertTrue(!UserMessages.isStorageError(ConnectException("Connection refused")))
        // HTTPS to a plain-HTTP port or an ISP block page (Android and desktop wording).
        assertEquals(UserMessages.NOT_TLS, UserMessages.describe(javax.net.ssl.SSLHandshakeException("Unable to parse TLS packet header")))
        assertEquals(UserMessages.NOT_TLS, UserMessages.describe(javax.net.ssl.SSLException("Unsupported or unrecognized SSL message")))
        assertEquals(UserMessages.TLS, UserMessages.describe(javax.net.ssl.SSLHandshakeException("PKIX path building failed")))
                // Wrapped causes are found too.
        assertEquals(UserMessages.NO_HOST, UserMessages.describe(IllegalStateException("x", UnknownHostException("h"))))
    }

    @Test
    fun ownMessagesAreKept() {
        assertEquals("This stream is DRM-protected and can't be downloaded.", UserMessages.describe(IllegalStateException("This stream is DRM-protected and can't be downloaded.")))
        assertEquals("Download failed", UserMessages.describe(RuntimeException(), "Download failed"))
    }
}

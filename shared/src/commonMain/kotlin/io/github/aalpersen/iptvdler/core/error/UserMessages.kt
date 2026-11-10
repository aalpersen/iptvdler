package io.github.aalpersen.iptvdler.core.error

import io.github.aalpersen.iptvdler.i18n.strings
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException

/** Turns HTTP statuses and exceptions into short messages a user can act on. */
object UserMessages {

    fun httpStatus(status: Int): String = when (status) {
        401, 403 -> strings.httpAccessDenied(status)
        404, 410 -> strings.httpNotFound(status)
        429 -> strings.httpRateLimited
        in 500..599 -> strings.httpServerError(status)
        else -> strings.httpOther(status)
    }

    /**
     * A message for [error]. Messages iptvdler wrote itself are kept; network and storage failures from the
     * platform get a plain explanation instead of the raw exception text.
     */
    fun describe(error: Throwable, fallback: String = strings.somethingWentWrong): String {
        for (e in generateSequence(error) { it.cause }.take(MAX_CAUSES)) {
            known(e)?.let { return it }
        }
        return error.message?.takeIf { it.isNotBlank() } ?: fallback
    }

    private fun known(e: Throwable): String? {
        if (e is HttpRequestTimeoutException || e is ConnectTimeoutException || e is SocketTimeoutException) return TIMEOUT
        val message = e.message.orEmpty()
        // HTTPS to something that answers in plain HTTP: an http-only port, or a network/ISP block page.
        if (NOT_TLS_TEXT.any { message.contains(it, ignoreCase = true) }) return NOT_TLS
        // Platform exceptions (java.net, javax.net.ssl) aren't visible from common code; match by name.
        return when (e::class.simpleName) {
            "UnknownHostException", "UnresolvedAddressException" -> NO_HOST
            "ConnectException", "NoRouteToHostException", "PortUnreachableException" -> NO_CONNECTION
            "SocketTimeoutException", "InterruptedIOException" -> TIMEOUT.takeIf { message.contains("timeout", ignoreCase = true) }
            "SSLHandshakeException", "SSLPeerUnverifiedException", "CertPathValidatorException" -> TLS
            else -> when {
                DISK_FULL.any { message.contains(it, ignoreCase = true) } -> STORAGE_FULL
                isStorageAccess(e) -> STORAGE_ACCESS
                else -> null
            }
        }
    }

    /**
     * True for local file problems (disk full, no permission, missing folder). They are IOExceptions like
     * network errors, but retrying won't help, so downloads fail right away instead.
     */
    fun isStorageError(error: Throwable): Boolean = generateSequence(error) { it.cause }.take(MAX_CAUSES).any { e ->
        DISK_FULL.any { e.message.orEmpty().contains(it, ignoreCase = true) } || isStorageAccess(e)
    }

    private fun isStorageAccess(e: Throwable): Boolean =
        e::class.simpleName in FILE_EXCEPTIONS || STORAGE_ACCESS_TEXT.any { e.message.orEmpty().contains(it, ignoreCase = true) }

    val NO_HOST: String get() = strings.errorNoHost
    val NO_CONNECTION: String get() = strings.errorNoConnection
    val TIMEOUT: String get() = strings.errorTimeout
    val TLS: String get() = strings.errorTls
    val NOT_TLS: String get() = strings.errorNotTls
    val STORAGE_FULL: String get() = strings.errorStorageFull
    val STORAGE_ACCESS: String get() = strings.errorStorageAccess

    private val DISK_FULL = listOf("No space left on device", "not enough space on the disk", "ENOSPC")
    private val FILE_EXCEPTIONS = setOf("FileNotFoundException", "AccessDeniedException", "NoSuchFileException", "FileSystemException", "NotDirectoryException")
    private val STORAGE_ACCESS_TEXT = listOf("failed to create directory", "Permission denied", "EACCES", "EROFS", "Read-only file system")
    private val NOT_TLS_TEXT = listOf(
        "Unable to parse TLS packet header", // Android (Conscrypt)
        "Unsupported or unrecognized SSL message", // desktop JVM
        "WRONG_VERSION_NUMBER",
        "plaintext connection",
    )
    private const val MAX_CAUSES = 5
}

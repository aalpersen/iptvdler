package io.github.aalpersen.iptvdler.platform

/**
 * Diagnostics for failures the user only sees as a short message (logcat tag "iptvdler" on Android,
 * stderr on desktop). Callers must mask credentials in what they log (see `withoutCredentials`).
 */
expect object AppLog {
    fun warn(message: String, error: Throwable? = null)
}

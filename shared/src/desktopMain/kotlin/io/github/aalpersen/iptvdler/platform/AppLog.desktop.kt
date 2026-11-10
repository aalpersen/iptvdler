package io.github.aalpersen.iptvdler.platform

actual object AppLog {
    actual fun warn(message: String, error: Throwable?) {
        System.err.println("iptvdler: $message")
        error?.printStackTrace()
    }
}

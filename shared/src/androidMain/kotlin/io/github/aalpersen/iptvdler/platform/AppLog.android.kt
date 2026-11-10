package io.github.aalpersen.iptvdler.platform

import android.util.Log

actual object AppLog {
    actual fun warn(message: String, error: Throwable?) {
        Log.w(TAG, message, error)
    }

    private const val TAG = "iptvdler"
}

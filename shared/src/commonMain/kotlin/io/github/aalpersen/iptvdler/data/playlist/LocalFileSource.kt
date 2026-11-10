package io.github.aalpersen.iptvdler.data.playlist

import okio.Source

/**
 * Opens a playlist the user picked from local storage: a file path on desktop,
 * a `content://` URI (or file path) on Android.
 */
fun interface LocalFileSource {
    fun open(location: String): Source
}

package io.github.aalpersen.iptvdler.ui.platform

import androidx.compose.runtime.Composable

/** A file the user picked; [location] is what [io.github.aalpersen.iptvdler.data.playlist.LocalFileSource] opens. */
data class PickedFile(val location: String, val displayName: String)

/** Returns a launcher that shows the platform file picker for playlist files; null result = cancelled. */
@Composable
expect fun rememberPlaylistFilePicker(onResult: (PickedFile?) -> Unit): () -> Unit

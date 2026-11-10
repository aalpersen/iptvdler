package io.github.aalpersen.iptvdler.ui.platform

import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState

/** Desktop window host: fullscreen via window placement; no picture-in-picture. */
class WindowPlayerHost(private val windowState: WindowState) : PlayerHost {
    private var placementBeforeFullscreen = WindowPlacement.Floating

    override val isFullscreen: State<Boolean> = derivedStateOf { windowState.placement == WindowPlacement.Fullscreen }

    override fun setFullscreen(fullscreen: Boolean) {
        if (fullscreen == isFullscreen.value) return
        if (fullscreen) {
            placementBeforeFullscreen = windowState.placement
            windowState.placement = WindowPlacement.Fullscreen
        } else {
            windowState.placement = placementBeforeFullscreen
        }
    }

    override val supportsPictureInPicture = false
    override val isInPictureInPicture: State<Boolean> = mutableStateOf(false)
    override fun enterPictureInPicture(aspectRatio: Float?) = Unit
    override fun setAutoPictureInPicture(enabled: Boolean, aspectRatio: Float?) = Unit
}

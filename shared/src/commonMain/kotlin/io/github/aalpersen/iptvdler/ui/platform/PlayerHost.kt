package io.github.aalpersen.iptvdler.ui.platform

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf

/** Window-level capabilities the player screen needs from the hosting Activity / desktop window. */
interface PlayerHost {
    val isFullscreen: State<Boolean>
    fun setFullscreen(fullscreen: Boolean)

    val supportsPictureInPicture: Boolean
    val isInPictureInPicture: State<Boolean>

    /** Enters picture-in-picture now. [aspectRatio] = width / height of the video, if known. */
    fun enterPictureInPicture(aspectRatio: Float?)

    /** Lets the host enter picture-in-picture by itself when the user leaves the app during playback. */
    fun setAutoPictureInPicture(enabled: Boolean, aspectRatio: Float?)
}

/** Host that supports nothing; used in tests and previews. */
object NoOpPlayerHost : PlayerHost {
    override val isFullscreen: State<Boolean> = mutableStateOf(false)
    override fun setFullscreen(fullscreen: Boolean) = Unit
    override val supportsPictureInPicture = false
    override val isInPictureInPicture: State<Boolean> = mutableStateOf(false)
    override fun enterPictureInPicture(aspectRatio: Float?) = Unit
    override fun setAutoPictureInPicture(enabled: Boolean, aspectRatio: Float?) = Unit
}

val LocalPlayerHost = staticCompositionLocalOf<PlayerHost> { NoOpPlayerHost }

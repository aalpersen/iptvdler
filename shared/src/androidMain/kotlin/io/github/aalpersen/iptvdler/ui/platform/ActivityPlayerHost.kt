package io.github.aalpersen.iptvdler.ui.platform

import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresApi
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * [PlayerHost] backed by an Activity. The Activity must forward [onUserLeaveHint] and
 * [onPictureInPictureModeChanged], and declare `android:supportsPictureInPicture="true"`.
 */
class ActivityPlayerHost(private val activity: ComponentActivity) : PlayerHost {

    private val fullscreen: MutableState<Boolean> = mutableStateOf(false)
    private val inPictureInPicture: MutableState<Boolean> = mutableStateOf(false)

    override val isFullscreen: State<Boolean> = fullscreen
    override val isInPictureInPicture: State<Boolean> = inPictureInPicture

    override val supportsPictureInPicture: Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private var autoPictureInPicture = false
    private var autoAspectRatio: Float? = null

    override fun setFullscreen(fullscreen: Boolean) {
        this.fullscreen.value = fullscreen
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (fullscreen) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    override fun enterPictureInPicture(aspectRatio: Float?) {
        if (!supportsPictureInPicture || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching { activity.enterPictureInPictureMode(pictureInPictureParams(aspectRatio, autoEnter = autoPictureInPicture)) }
    }

    override fun setAutoPictureInPicture(enabled: Boolean, aspectRatio: Float?) {
        autoPictureInPicture = enabled
        autoAspectRatio = aspectRatio
        if (!supportsPictureInPicture || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching { activity.setPictureInPictureParams(pictureInPictureParams(aspectRatio, autoEnter = enabled)) }
    }

    /** Android 8–11 have no auto-enter flag; enter PiP manually when the user leaves the app. */
    fun onUserLeaveHint() {
        if (autoPictureInPicture && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) enterPictureInPicture(autoAspectRatio)
    }

    fun onPictureInPictureModeChanged(isInPictureInPicture: Boolean) {
        inPictureInPicture.value = isInPictureInPicture
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun pictureInPictureParams(aspectRatio: Float?, autoEnter: Boolean): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
        // Android rejects ratios outside roughly 1:2.39 .. 2.39:1.
        aspectRatio?.coerceIn(1 / 2.39f, 2.39f)?.let { builder.setAspectRatio(Rational((it * 1000).toInt(), 1000)) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(autoEnter)
            builder.setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }
}

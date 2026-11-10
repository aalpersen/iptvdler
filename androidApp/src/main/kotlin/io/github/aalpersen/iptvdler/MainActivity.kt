package io.github.aalpersen.iptvdler

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import io.github.aalpersen.iptvdler.ui.App
import io.github.aalpersen.iptvdler.ui.platform.ActivityPlayerHost
import io.github.aalpersen.iptvdler.ui.platform.LocalPlayerHost

class MainActivity : ComponentActivity() {

    private val playerHost by lazy { ActivityPlayerHost(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            CompositionLocalProvider(LocalPlayerHost provides playerHost) { App() }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        playerHost.onUserLeaveHint()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        playerHost.onPictureInPictureModeChanged(isInPictureInPictureMode)
    }
}

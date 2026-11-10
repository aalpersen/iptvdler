package io.github.aalpersen.iptvdler.ui.player

import android.graphics.Color
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import io.github.aalpersen.iptvdler.core.player.AspectMode
import io.github.aalpersen.iptvdler.core.player.VideoPlayer
import io.github.aalpersen.iptvdler.player.ExoVideoPlayer

@OptIn(UnstableApi::class)
@Composable
actual fun VideoSurface(player: VideoPlayer, aspectMode: AspectMode, modifier: Modifier) {
    val exoPlayer = (player as ExoVideoPlayer).exoPlayer
    val state by player.state.collectAsStateWithLifecycle()

    AndroidView(
        factory = { context ->
            // Media3's view renders video and subtitles; iptvdler draws its own controls on top.
            PlayerView(context).apply {
                useController = false
                setShutterBackgroundColor(Color.BLACK)
                setBackgroundColor(Color.BLACK)
                this.player = exoPlayer
            }
        },
        update = { view ->
            view.resizeMode = when (aspectMode) {
                AspectMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                AspectMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                AspectMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            }
            view.keepScreenOn = state.isPlaying
        },
        onRelease = { view -> view.player = null },
        modifier = modifier,
    )
}

package io.github.aalpersen.iptvdler.ui.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aalpersen.iptvdler.core.player.AspectMode
import io.github.aalpersen.iptvdler.core.player.VideoPlayer
import io.github.aalpersen.iptvdler.player.VlcjVideoPlayer

@Composable
actual fun VideoSurface(player: VideoPlayer, aspectMode: AspectMode, modifier: Modifier) {
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        val vlcPlayer = player as? VlcjVideoPlayer ?: return@Box
        val frame by vlcPlayer.frames.collectAsStateWithLifecycle()
        frame?.let {
            Image(
                bitmap = it.image,
                contentDescription = null,
                contentScale = when (aspectMode) {
                    AspectMode.FIT -> ContentScale.Fit
                    AspectMode.FILL -> ContentScale.Crop
                    AspectMode.STRETCH -> ContentScale.FillBounds
                },
                filterQuality = FilterQuality.Medium,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

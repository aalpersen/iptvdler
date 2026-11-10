package io.github.aalpersen.iptvdler.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.aalpersen.iptvdler.core.player.AspectMode
import io.github.aalpersen.iptvdler.core.player.VideoPlayer

/** Renders [player]'s video (and embedded subtitles where the platform draws them). */
@Composable
expect fun VideoSurface(player: VideoPlayer, aspectMode: AspectMode, modifier: Modifier = Modifier)

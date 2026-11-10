package io.github.aalpersen.iptvdler.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aalpersen.iptvdler.core.player.AspectMode
import io.github.aalpersen.iptvdler.core.player.PlaybackStatus
import io.github.aalpersen.iptvdler.core.player.PlayerState
import io.github.aalpersen.iptvdler.core.player.VideoPlayer
import io.github.aalpersen.iptvdler.i18n.strings
import io.github.aalpersen.iptvdler.ui.downloads.DownloadDialog
import io.github.aalpersen.iptvdler.ui.downloads.RecordDialog
import io.github.aalpersen.iptvdler.ui.downloads.RecordRed
import io.github.aalpersen.iptvdler.ui.formatDuration
import io.github.aalpersen.iptvdler.ui.navigation.PlayerRoute
import io.github.aalpersen.iptvdler.ui.platform.LocalPlayerHost
import kotlinx.coroutines.delay
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

private const val CONTROLS_HIDE_DELAY_MILLIS = 3_000L
private const val KEY_SEEK_STEP_MILLIS = 10_000L
private const val VOLUME_STEP = 0.1f

@Composable
fun PlayerScreen(
    route: PlayerRoute,
    onBack: () -> Unit,
    onOpenDownloads: () -> Unit,
    viewModel: PlayerViewModel = koinViewModel { parametersOf(route) },
) {
    val player = viewModel.player
    val channel by viewModel.channel.collectAsStateWithLifecycle()
    val title by viewModel.title.collectAsStateWithLifecycle()
    var showDownload by remember { mutableStateOf(false) }
    var showRecord by remember { mutableStateOf(false) }
    val state by player.state.collectAsStateWithLifecycle()
    val host = LocalPlayerHost.current
    val isFullscreen by host.isFullscreen
    val isInPictureInPicture by host.isInPictureInPicture

    var aspectMode by rememberSaveable { mutableStateOf(AspectMode.FIT) }
    var controlsVisible by remember { mutableStateOf(true) }
    var interactions by remember { mutableIntStateOf(0) }
    var showTracks by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    fun showControls() {
        controlsVisible = true
        interactions++
    }

    LaunchedEffect(state.isPlaying, interactions, showTracks) {
        if (state.isPlaying && !showTracks) {
            delay(CONTROLS_HIDE_DELAY_MILLIS)
            controlsVisible = false
        }
    }
    LaunchedEffect(state.isPlaying, state.videoAspectRatio) {
        host.setAutoPictureInPicture(state.isPlaying, state.videoAspectRatio)
    }
    DisposableEffect(host) {
        onDispose {
            host.setAutoPictureInPicture(false, null)
            host.setFullscreen(false)
        }
    }
    // Stop, not pause: in picture-in-picture the Activity is paused but should keep playing.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { player.pause() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                showControls()
                when (event.key) {
                    Key.Spacebar, Key.MediaPlayPause -> { togglePlay(player, state); true }
                    Key.DirectionLeft -> seekBy(player, state, -KEY_SEEK_STEP_MILLIS)
                    Key.DirectionRight -> seekBy(player, state, KEY_SEEK_STEP_MILLIS)
                    Key.F -> { host.setFullscreen(!isFullscreen); true }
                    Key.M -> { player.setMuted(!state.isMuted); true }
                    Key.DirectionUp -> { player.setVolume(state.volume + VOLUME_STEP); player.setMuted(false); true }
                    Key.DirectionDown -> { player.setVolume(state.volume - VOLUME_STEP); true }
                    Key.Escape -> if (isFullscreen) { host.setFullscreen(false); true } else false
                    else -> false
                }
            }
            .pointerInput(Unit) {
                // Any mouse movement reveals the controls (desktop); taps are handled below.
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Move) showControls()
                    }
                }
            },
    ) {
        VideoSurface(
            player = player,
            aspectMode = aspectMode,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            if (controlsVisible) controlsVisible = false else showControls()
                        },
                        onDoubleTap = { host.setFullscreen(!isFullscreen) },
                    )
                },
        )

        if (state.status == PlaybackStatus.BUFFERING || (state.status == PlaybackStatus.IDLE && title.isNotEmpty())) {
            CircularProgressIndicator(color = Color.White, modifier = Modifier.align(Alignment.Center))
        }

        if (state.status == PlaybackStatus.ERROR && !isInPictureInPicture) {
            ErrorPanel(
                message = state.errorMessage ?: strings.playbackFailed,
                onRetry = player::retry,
                onBack = onBack,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        AnimatedVisibility(
            visible = controlsVisible && !isInPictureInPicture && state.status != PlaybackStatus.ERROR,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            PlayerControls(
                title = title,
                state = state,
                player = player,
                isFullscreen = isFullscreen,
                aspectMode = aspectMode,
                supportsPictureInPicture = host.supportsPictureInPicture,
                onBack = onBack,
                onInteraction = ::showControls,
                onToggleFullscreen = { host.setFullscreen(!isFullscreen) },
                onCycleAspect = { aspectMode = AspectMode.entries[(aspectMode.ordinal + 1) % AspectMode.entries.size] },
                onPictureInPicture = { host.enterPictureInPicture(state.videoAspectRatio) },
                onShowTracks = { showTracks = true },
                onDownload = if (channel != null && !state.isLive) ({ showDownload = true }) else null,
                onRecord = if (channel != null && state.isLive) ({ showRecord = true }) else null,
            )
        }
    }

    if (showTracks) {
        TrackSelectionDialog(state = state, player = player, onDismiss = { showTracks = false })
    }
    channel?.takeIf { showRecord }?.let {
        RecordDialog(channel = it, onDismiss = { showRecord = false }, onOpenDownloads = {
            showRecord = false
            onOpenDownloads()
        })
    }
    channel?.takeIf { showDownload }?.let {
        DownloadDialog(channel = it, onDismiss = { showDownload = false }, onOpenDownloads = {
            showDownload = false
            onOpenDownloads()
        })
    }
}

private fun togglePlay(player: VideoPlayer, state: PlayerState) {
    if (state.isPlaying) player.pause() else player.play()
}

private fun seekBy(player: VideoPlayer, state: PlayerState, deltaMs: Long): Boolean {
    if (!state.isSeekable) return false
    val duration = state.durationMs ?: return false
    player.seekTo((state.positionMs + deltaMs).coerceIn(0, duration))
    return true
}

@Composable
private fun PlayerControls(
    title: String,
    state: PlayerState,
    player: VideoPlayer,
    isFullscreen: Boolean,
    aspectMode: AspectMode,
    supportsPictureInPicture: Boolean,
    onBack: () -> Unit,
    onInteraction: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onCycleAspect: () -> Unit,
    onPictureInPicture: () -> Unit,
    onShowTracks: () -> Unit,
    onDownload: (() -> Unit)?,
    onRecord: (() -> Unit)?,
) {
    val scrim = Color.Black.copy(alpha = 0.6f)
    Box(Modifier.fillMaxSize()) {
        // Top bar
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(scrim, Color.Transparent)))
                .safeDrawingPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = strings.back, tint = Color.White)
            }
            Text(
                title,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (state.isLive) LiveBadge()
            onDownload?.let { ControlButton(PlayerIcons.Download, strings.download, it) }
            onRecord?.let {
                IconButton(onClick = it) { Icon(PlayerIcons.Record, contentDescription = strings.recordAction, tint = RecordRed) }
            }
        }

        // Center play/pause
        if (state.status != PlaybackStatus.BUFFERING) {
            FilledIconButton(
                onClick = {
                    togglePlay(player, state)
                    onInteraction()
                },
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = scrim, contentColor = Color.White),
                modifier = Modifier.align(Alignment.Center).size(72.dp),
            ) {
                Icon(
                    if (state.isPlaying) PlayerIcons.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (state.isPlaying) strings.pause else strings.play,
                    modifier = Modifier.size(40.dp),
                )
            }
        }

        // Bottom bar
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, scrim)))
                .safeDrawingPadding()
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            val duration = state.durationMs
            if (state.isSeekable && duration != null) {
                SeekBar(positionMs = state.positionMs, durationMs = duration, onSeek = player::seekTo, onInteraction = onInteraction)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                ControlButton(
                    if (state.isMuted || state.volume == 0f) PlayerIcons.VolumeOff else PlayerIcons.VolumeUp,
                    if (state.isMuted) strings.unmute else strings.mute,
                ) { player.setMuted(!state.isMuted) }
                Slider(
                    value = if (state.isMuted) 0f else state.volume,
                    onValueChange = {
                        player.setMuted(false)
                        player.setVolume(it)
                        onInteraction()
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                    ),
                    modifier = Modifier.width(120.dp),
                )
                if (state.isLive) {
                    Text(strings.live, color = Color.White, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 12.dp))
                }
                Spacer(Modifier.weight(1f))
                ControlButton(PlayerIcons.Subtitles, strings.qualityAndTracks, onShowTracks)
                ControlButton(PlayerIcons.AspectRatio, strings.aspectRatio(aspectMode.label()), onCycleAspect)
                Text(aspectMode.label(), color = Color.White, style = MaterialTheme.typography.labelMedium)
                if (supportsPictureInPicture) {
                    ControlButton(PlayerIcons.PictureInPicture, strings.pictureInPicture, onPictureInPicture)
                }
                ControlButton(
                    if (isFullscreen) PlayerIcons.FullscreenExit else PlayerIcons.Fullscreen,
                    if (isFullscreen) strings.exitFullscreen else strings.fullscreen,
                    onToggleFullscreen,
                )
            }
        }
    }
}

private fun AspectMode.label() = when (this) {
    AspectMode.FIT -> strings.aspectFit
    AspectMode.FILL -> strings.aspectFill
    AspectMode.STRETCH -> strings.aspectStretch
}

@Composable
private fun SeekBar(positionMs: Long, durationMs: Long, onSeek: (Long) -> Unit, onInteraction: () -> Unit) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val fraction = dragFraction ?: (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    val shownPosition = dragFraction?.let { (it * durationMs).toLong() } ?: positionMs

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(formatDuration(shownPosition), color = Color.White, style = MaterialTheme.typography.labelMedium)
        Slider(
            value = fraction,
            onValueChange = {
                dragFraction = it
                onInteraction()
            },
            onValueChangeFinished = {
                dragFraction?.let { onSeek((it * durationMs).toLong()) }
                dragFraction = null
            },
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color.White.copy(alpha = 0.3f),
            ),
            modifier = Modifier.weight(1f),
        )
        Text(formatDuration(durationMs), color = Color.White, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ControlButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(icon, contentDescription = description, tint = Color.White)
    }
}

@Composable
private fun LiveBadge() {
    Text(
        strings.liveBadge,
        color = Color.White,
        fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier
            .padding(end = 12.dp)
            .background(Color(0xFFD32F2F), RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun ErrorPanel(message: String, onRetry: () -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier.widthIn(max = 480.dp).padding(24.dp),
    ) {
        Text(message, color = Color.White, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onBack) { Text(strings.back, color = Color.White) }
            Button(onClick = onRetry) {
                Icon(Icons.Filled.Refresh, contentDescription = null)
                Text(strings.retry, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

package io.github.aalpersen.iptvdler.ui.player

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.aalpersen.iptvdler.core.player.PlayerState
import io.github.aalpersen.iptvdler.core.player.TrackInfo
import io.github.aalpersen.iptvdler.core.player.TrackType
import io.github.aalpersen.iptvdler.core.player.VideoPlayer
import io.github.aalpersen.iptvdler.i18n.strings
import io.github.aalpersen.iptvdler.ui.formatBitrate

@Composable
internal fun TrackSelectionDialog(state: PlayerState, player: VideoPlayer, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.qualityAndTracks) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                if (state.videoTracks.size > 1) {
                    item { SectionTitle(strings.quality) }
                    item {
                        OptionRow(strings.auto, state.selectedVideoTrackId == null) { player.selectVideoTrack(null) }
                    }
                    val sorted = state.videoTracks.sortedWith(compareByDescending<TrackInfo> { it.height ?: 0 }.thenByDescending { it.bitrate ?: 0 })
                    sorted.forEach { track ->
                        item(key = "v" + track.id) {
                            OptionRow(track.displayLabel(), state.selectedVideoTrackId == track.id) {
                                player.selectVideoTrack(track.id)
                            }
                        }
                    }
                }
                if (state.audioTracks.isNotEmpty()) {
                    item { SectionTitle(strings.audio) }
                    state.audioTracks.forEach { track ->
                        item(key = "a" + track.id) {
                            OptionRow(track.displayLabel(), state.selectedAudioTrackId == track.id) {
                                player.selectAudioTrack(track.id)
                            }
                        }
                    }
                }
                item { SectionTitle(strings.subtitles) }
                item { OptionRow(strings.off, state.selectedSubtitleTrackId == null) { player.selectSubtitleTrack(null) } }
                state.subtitleTracks.forEach { track ->
                    item(key = "s" + track.id) {
                        OptionRow(track.displayLabel(), state.selectedSubtitleTrackId == track.id) {
                            player.selectSubtitleTrack(track.id)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(strings.done) } },
    )
}

internal fun TrackInfo.displayLabel(): String = when (type) {
    TrackType.VIDEO -> listOfNotNull(height?.let { "${it}p" } ?: label, bitrate?.let(::formatBitrate)).joinToString(" · ")
    TrackType.AUDIO, TrackType.SUBTITLE -> label
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun OptionRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}

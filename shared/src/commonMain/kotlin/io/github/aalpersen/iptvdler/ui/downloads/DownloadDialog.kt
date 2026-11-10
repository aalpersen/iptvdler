package io.github.aalpersen.iptvdler.ui.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.aalpersen.iptvdler.core.download.DownloadManager
import io.github.aalpersen.iptvdler.core.download.DownloadPlanner
import io.github.aalpersen.iptvdler.core.download.DownloadRefusedException
import io.github.aalpersen.iptvdler.core.download.LIVE_DOWNLOAD_REFUSED_MESSAGE
import io.github.aalpersen.iptvdler.core.error.UserMessages
import io.github.aalpersen.iptvdler.core.hls.DRM_DOWNLOAD_REFUSED_MESSAGE
import io.github.aalpersen.iptvdler.core.hls.Rendition
import io.github.aalpersen.iptvdler.core.hls.RenditionType
import io.github.aalpersen.iptvdler.core.hls.Variant
import io.github.aalpersen.iptvdler.core.hls.VariantSelector
import io.github.aalpersen.iptvdler.core.model.Channel
import io.github.aalpersen.iptvdler.data.hls.HlsClient
import io.github.aalpersen.iptvdler.data.hls.StreamProbe
import io.github.aalpersen.iptvdler.i18n.strings
import io.github.aalpersen.iptvdler.ui.formatBytes
import io.github.aalpersen.iptvdler.ui.formatDuration
import io.github.aalpersen.iptvdler.ui.platform.rememberNotificationPermissionRequest
import io.github.aalpersen.iptvdler.ui.screens.rememberComposableViewModelStoreOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

data class QualityOption(val variant: Variant?, val label: String, val estimatedBytes: Long?)

sealed interface DownloadDialogState {
    data object Loading : DownloadDialogState
    data class HlsOptions(
        val qualities: List<QualityOption>,
        val selectedQuality: Int,
        val audio: List<Rendition>,
        val selectedAudio: Int,
        val durationSeconds: Double,
        val subtitles: List<Rendition> = emptyList(),
        val selectedSubtitles: Set<Int> = emptySet(),
    ) : DownloadDialogState {
        val separateAudio: Boolean get() = audio.isNotEmpty()
    }
    data class DirectOptions(val sizeBytes: Long) : DownloadDialogState
    data class Refused(val message: String) : DownloadDialogState
    data class Error(val message: String) : DownloadDialogState
    data object Starting : DownloadDialogState
    data object Started : DownloadDialogState
}

class DownloadDialogViewModel(
    private val channel: Channel,
    private val hlsClient: HlsClient,
    private val planner: DownloadPlanner,
    private val manager: DownloadManager,
) : ViewModel() {

    private val _state = MutableStateFlow<DownloadDialogState>(DownloadDialogState.Loading)
    val state: StateFlow<DownloadDialogState> = _state.asStateFlow()

    private var probe: StreamProbe? = null

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        _state.value = try {
            when (val result = hlsClient.probe(channel.url, channel.headers).also { probe = it }) {
                is StreamProbe.Direct -> result.contentLength?.let { DownloadDialogState.DirectOptions(it) }
                    ?: DownloadDialogState.Refused(LIVE_DOWNLOAD_REFUSED_MESSAGE)
                is StreamProbe.Hls -> when {
                    !result.encryption.downloadable -> DownloadDialogState.Refused(DRM_DOWNLOAD_REFUSED_MESSAGE)
                    result.media.isLive -> DownloadDialogState.Refused(LIVE_DOWNLOAD_REFUSED_MESSAGE)
                    else -> hlsOptions(result)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DownloadDialogState.Error(strings.readStreamFailed(UserMessages.describe(e)))
        }
    }

    private fun hlsOptions(probe: StreamProbe.Hls): DownloadDialogState.HlsOptions {
        val duration = probe.media.totalDurationSeconds
        val master = probe.master
        val qualities = if (master == null) {
            listOf(QualityOption(null, strings.original, null))
        } else {
            val options = VariantSelector.qualityOptions(master)
            options.map { variant ->
                QualityOption(variant, VariantSelector.label(variant, options), VariantSelector.estimateBytes(variant, duration))
            }
        }
        val selected = qualities.indexOfFirst { it.variant == probe.selection?.variant }.coerceAtLeast(0)
        val variant = qualities.getOrNull(selected)?.variant
        val audio = audioOptions(variant)
        val subtitles = subtitleOptions(variant)
        return DownloadDialogState.HlsOptions(
            qualities = qualities,
            selectedQuality = selected,
            audio = audio,
            selectedAudio = audio.indexOf(probe.selection?.audio).coerceAtLeast(0),
            durationSeconds = duration,
            subtitles = subtitles,
            // Preselect the stream's default subtitles; the user can tick more.
            selectedSubtitles = subtitles.indices.filter { subtitles[it].isDefault }.toSet(),
        )
    }

    private fun audioOptions(variant: Variant?): List<Rendition> {
        val master = (probe as? StreamProbe.Hls)?.master ?: return emptyList()
        return master.renditions(RenditionType.AUDIO, variant?.audioGroup).filter { it.uri != null }
    }

    private fun subtitleOptions(variant: Variant?): List<Rendition> {
        val master = (probe as? StreamProbe.Hls)?.master ?: return emptyList()
        return master.renditions(RenditionType.SUBTITLES, variant?.subtitlesGroup).filter { it.uri != null }
    }

    fun selectQuality(index: Int) = _state.update { state ->
        if (state !is DownloadDialogState.HlsOptions) return@update state
        val variant = state.qualities[index].variant
        val audio = audioOptions(variant)
        val keepLanguage = state.audio.getOrNull(state.selectedAudio)?.language
        val subtitles = subtitleOptions(variant)
        val keepSubtitles = state.selectedSubtitles.mapNotNull { state.subtitles.getOrNull(it)?.language }.toSet()
        state.copy(
            selectedQuality = index,
            audio = audio,
            selectedAudio = audio.indexOfFirst { it.language == keepLanguage }.coerceAtLeast(0),
            subtitles = subtitles,
            selectedSubtitles = subtitles.indices.filter { subtitles[it].language in keepSubtitles }.toSet(),
        )
    }

    fun toggleSubtitle(index: Int) = _state.update { state ->
        if (state !is DownloadDialogState.HlsOptions) return@update state
        state.copy(selectedSubtitles = if (index in state.selectedSubtitles) state.selectedSubtitles - index else state.selectedSubtitles + index)
    }

    fun selectAudio(index: Int) = _state.update { state ->
        if (state is DownloadDialogState.HlsOptions) state.copy(selectedAudio = index) else state
    }

    fun start() {
        val current = _state.value
        val probe = probe ?: return
        _state.value = DownloadDialogState.Starting
        viewModelScope.launch {
            _state.value = try {
                val plan = when {
                    current is DownloadDialogState.HlsOptions && probe is StreamProbe.Hls -> planner.planHls(
                        title = channel.name,
                        sourceUrl = channel.url,
                        headers = channel.headers,
                        master = probe.master,
                        variant = current.qualities[current.selectedQuality].variant,
                        audio = current.audio.getOrNull(current.selectedAudio),
                        media = probe.media,
                        subtitles = current.selectedSubtitles.sorted().map { current.subtitles[it] },
                    )
                    current is DownloadDialogState.DirectOptions && probe is StreamProbe.Direct -> planner.planDirect(
                        title = channel.name,
                        sourceUrl = channel.url,
                        headers = channel.headers,
                        contentType = probe.contentType,
                        contentLength = probe.contentLength,
                    )
                    else -> return@launch
                }
                manager.enqueue(plan)
                DownloadDialogState.Started
            } catch (e: CancellationException) {
                throw e
            } catch (e: DownloadRefusedException) {
                DownloadDialogState.Refused(e.message.orEmpty())
            } catch (e: Exception) {
                DownloadDialogState.Error(strings.startDownloadFailed(UserMessages.describe(e)))
            }
        }
    }
}

@Composable
fun DownloadDialog(
    channel: Channel,
    onDismiss: () -> Unit,
    onOpenDownloads: () -> Unit,
    // Scoped to the dialog: reopening it starts over instead of showing the last result.
    viewModel: DownloadDialogViewModel = koinViewModel(viewModelStoreOwner = rememberComposableViewModelStoreOwner()) {
        parametersOf(channel)
    },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val requestNotifications = rememberNotificationPermissionRequest()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (state == DownloadDialogState.Started) strings.downloadStarted else strings.download) },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(channel.name, style = MaterialTheme.typography.titleSmall)
                when (val s = state) {
                    DownloadDialogState.Loading, DownloadDialogState.Starting ->
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    is DownloadDialogState.Refused -> Text(s.message, color = MaterialTheme.colorScheme.error)
                    is DownloadDialogState.Error -> Text(s.message, color = MaterialTheme.colorScheme.error)
                    DownloadDialogState.Started -> Text(strings.followInDownloads)
                    is DownloadDialogState.DirectOptions -> Text(strings.singleFile(formatBytes(s.sizeBytes)))
                    is DownloadDialogState.HlsOptions -> HlsOptionsContent(s, viewModel)
                }
            }
        },
        confirmButton = {
            when (state) {
                is DownloadDialogState.HlsOptions, is DownloadDialogState.DirectOptions -> TextButton(onClick = {
                    requestNotifications()
                    viewModel.start()
                }) { Text(strings.download) }
                DownloadDialogState.Started -> TextButton(onClick = onOpenDownloads) { Text(strings.openDownloads) }
                else -> Unit
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(if (state == DownloadDialogState.Started) strings.close else strings.cancel) }
        },
    )
}

@Composable
private fun HlsOptionsContent(state: DownloadDialogState.HlsOptions, viewModel: DownloadDialogViewModel) {
    Text(strings.length(formatDuration((state.durationSeconds * 1000).toLong())), style = MaterialTheme.typography.bodySmall)
    Text(strings.quality, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    state.qualities.forEachIndexed { index, option ->
        val size = option.estimatedBytes?.let { " · ~${formatBytes(it)}" }.orEmpty()
        Choice(option.label + size, index == state.selectedQuality) { viewModel.selectQuality(index) }
    }
    if (state.audio.size > 1) {
        Text(strings.audio, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        state.audio.forEachIndexed { index, rendition ->
            Choice(rendition.name, index == state.selectedAudio) { viewModel.selectAudio(index) }
        }
    }
    if (state.subtitles.isNotEmpty()) {
        Text(strings.subtitles, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        state.subtitles.forEachIndexed { index, rendition ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
                    .toggleable(value = index in state.selectedSubtitles, role = Role.Checkbox) { viewModel.toggleSubtitle(index) },
            ) {
                Checkbox(checked = index in state.selectedSubtitles, onCheckedChange = null)
                val detail = listOfNotNull(rendition.language, strings.forced.takeIf { rendition.forced }).joinToString(", ")
                Text(if (detail.isEmpty()) rendition.name else "${rendition.name} ($detail)", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
    if (state.separateAudio) {
        Text(
            strings.separateAudioNote,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}

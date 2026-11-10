package io.github.aalpersen.iptvdler.ui.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.aalpersen.iptvdler.core.download.DownloadManager
import io.github.aalpersen.iptvdler.core.download.DownloadPlanner
import io.github.aalpersen.iptvdler.core.download.DownloadRefusedException
import io.github.aalpersen.iptvdler.core.download.NOT_LIVE_RECORDING_MESSAGE
import io.github.aalpersen.iptvdler.core.download.RecordingNames
import io.github.aalpersen.iptvdler.core.error.UserMessages
import io.github.aalpersen.iptvdler.core.hls.DRM_DOWNLOAD_REFUSED_MESSAGE
import io.github.aalpersen.iptvdler.core.hls.RenditionType
import io.github.aalpersen.iptvdler.core.hls.Variant
import io.github.aalpersen.iptvdler.core.hls.VariantSelector
import io.github.aalpersen.iptvdler.core.model.Channel
import io.github.aalpersen.iptvdler.core.model.EpochClock
import io.github.aalpersen.iptvdler.data.hls.HlsClient
import io.github.aalpersen.iptvdler.data.hls.StreamProbe
import io.github.aalpersen.iptvdler.i18n.strings
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

enum class StopMode { MANUAL, DURATION, UNTIL }

val recordingDurations = listOf(30, 60, 120, 180)

sealed interface RecordDialogState {
    data object Loading : RecordDialogState
    data class Options(
        val qualities: List<QualityOption>,
        val selectedQuality: Int,
        val stopMode: StopMode = StopMode.MANUAL,
        val durationMinutes: Int = 60,
        val untilText: String = "",
        val untilError: String? = null,
    ) : RecordDialogState
    data class Refused(val message: String) : RecordDialogState
    data class Error(val message: String) : RecordDialogState
    data object Starting : RecordDialogState
    data object Started : RecordDialogState
}

class RecordDialogViewModel(
    private val channel: Channel,
    private val hlsClient: HlsClient,
    private val planner: DownloadPlanner,
    private val manager: DownloadManager,
    private val clock: EpochClock,
) : ViewModel() {

    private val _state = MutableStateFlow<RecordDialogState>(RecordDialogState.Loading)
    val state: StateFlow<RecordDialogState> = _state.asStateFlow()
    private var probe: StreamProbe? = null

    init {
        viewModelScope.launch {
            _state.value = try {
                when (val result = hlsClient.probe(channel.url, channel.headers).also { probe = it }) {
                    is StreamProbe.Direct ->
                        if (result.contentLength != null) RecordDialogState.Refused(NOT_LIVE_RECORDING_MESSAGE)
                        else RecordDialogState.Options(listOf(QualityOption(null, strings.originalStream, null)), 0)
                    is StreamProbe.Hls -> when {
                        !result.encryption.downloadable -> RecordDialogState.Refused(DRM_DOWNLOAD_REFUSED_MESSAGE)
                        !result.media.isLive -> RecordDialogState.Refused(NOT_LIVE_RECORDING_MESSAGE)
                        else -> {
                            val options = result.master?.let { master ->
                                VariantSelector.qualityOptions(master).let { variants ->
                                    variants.map { QualityOption(it, VariantSelector.label(it, variants), null) }
                                }
                            } ?: listOf(QualityOption(null, strings.originalStream, null))
                            RecordDialogState.Options(options, options.indexOfFirst { it.variant == result.selection?.variant }.coerceAtLeast(0))
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RecordDialogState.Error(strings.readStreamFailed(UserMessages.describe(e)))
            }
        }
    }

    private fun options(transform: RecordDialogState.Options.() -> RecordDialogState.Options) =
        _state.update { if (it is RecordDialogState.Options) it.transform() else it }

    fun selectQuality(index: Int) = options { copy(selectedQuality = index) }
    fun setStopMode(mode: StopMode) = options { copy(stopMode = mode, untilError = null) }
    fun setDuration(minutes: Int) = options { copy(stopMode = StopMode.DURATION, durationMinutes = minutes) }
    fun setUntil(text: String) = options { copy(stopMode = StopMode.UNTIL, untilText = text, untilError = null) }

    /** "today 21:30" / "tomorrow 06:00" for the typed time, or null if invalid. */
    fun untilPreview(text: String): String? {
        val (h, m) = RecordingNames.parseClock(text) ?: return null
        val now = clock.nowMillis()
        val at = RecordingNames.nextOccurrence(h, m, now)
        val clock = RecordingNames.clock(at)
        return if (RecordingNames.timestamp(at).take(10) == RecordingNames.timestamp(now).take(10)) strings.today(clock) else strings.tomorrow(clock)
    }

    fun start() {
        val current = _state.value as? RecordDialogState.Options ?: return
        val now = clock.nowMillis()
        val stopAt = when (current.stopMode) {
            StopMode.MANUAL -> null
            StopMode.DURATION -> now + current.durationMinutes * 60_000L
            StopMode.UNTIL -> {
                val parsed = RecordingNames.parseClock(current.untilText)
                    ?: return options { copy(untilError = strings.enterTimeLike) }
                RecordingNames.nextOccurrence(parsed.first, parsed.second, now)
            }
        }
        val probe = probe ?: return
        _state.value = RecordDialogState.Starting
        viewModelScope.launch {
            _state.value = try {
                val plan = when (probe) {
                    is StreamProbe.Hls -> {
                        val variant = current.qualities[current.selectedQuality].variant
                        planner.planHlsRecording(
                            title = channel.name,
                            sourceUrl = channel.url,
                            headers = channel.headers,
                            master = probe.master,
                            variant = variant,
                            audio = defaultAudio(probe, variant),
                            media = probe.media,
                            stopAt = stopAt,
                            startedAt = now,
                        )
                    }
                    is StreamProbe.Direct -> planner.planDirectRecording(
                        channel.name, channel.url, channel.headers, probe.contentType, probe.contentLength, stopAt, now,
                    )
                }
                manager.enqueue(plan)
                RecordDialogState.Started
            } catch (e: CancellationException) {
                throw e
            } catch (e: DownloadRefusedException) {
                RecordDialogState.Refused(e.message.orEmpty())
            } catch (e: Exception) {
                RecordDialogState.Error(strings.startRecordingFailed(UserMessages.describe(e)))
            }
        }
    }

    private fun defaultAudio(probe: StreamProbe.Hls, variant: Variant?) =
        probe.master?.renditions(RenditionType.AUDIO, variant?.audioGroup)?.filter { it.uri != null }
            ?.let { list -> list.firstOrNull { it.isDefault } ?: list.firstOrNull() }
}

@Composable
fun RecordDialog(
    channel: Channel,
    onDismiss: () -> Unit,
    onOpenDownloads: () -> Unit,
    viewModel: RecordDialogViewModel = koinViewModel(viewModelStoreOwner = rememberComposableViewModelStoreOwner()) {
        parametersOf(channel)
    },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val requestNotifications = rememberNotificationPermissionRequest()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (state == RecordDialogState.Started) strings.recordingStarted else strings.recordTitle) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(channel.name, style = MaterialTheme.typography.titleSmall)
                when (val s = state) {
                    RecordDialogState.Loading, RecordDialogState.Starting ->
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    is RecordDialogState.Refused -> Text(s.message, color = MaterialTheme.colorScheme.error)
                    is RecordDialogState.Error -> Text(s.message, color = MaterialTheme.colorScheme.error)
                    RecordDialogState.Started -> Text(strings.followAndStopInDownloads)
                    is RecordDialogState.Options -> OptionsContent(s, viewModel)
                }
            }
        },
        confirmButton = {
            when (state) {
                is RecordDialogState.Options -> TextButton(onClick = {
                    requestNotifications()
                    viewModel.start()
                }) { Text(strings.recordConfirm) }
                RecordDialogState.Started -> TextButton(onClick = onOpenDownloads) { Text(strings.openDownloads) }
                else -> Unit
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(if (state == RecordDialogState.Started) strings.close else strings.cancel) }
        },
    )
}

@Composable
private fun OptionsContent(state: RecordDialogState.Options, viewModel: RecordDialogViewModel) {
    if (state.qualities.size > 1) {
        SectionLabel(strings.quality)
        state.qualities.forEachIndexed { index, option ->
            RadioRow(option.label, index == state.selectedQuality) { viewModel.selectQuality(index) }
        }
    }
    SectionLabel(strings.stopRecording)
    RadioRow(strings.whenIStopIt, state.stopMode == StopMode.MANUAL) { viewModel.setStopMode(StopMode.MANUAL) }
    RadioRow(strings.after, state.stopMode == StopMode.DURATION) { viewModel.setStopMode(StopMode.DURATION) }
    Row(Modifier.padding(start = 40.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        recordingDurations.forEach { minutes ->
            FilterChip(
                selected = state.stopMode == StopMode.DURATION && state.durationMinutes == minutes,
                onClick = { viewModel.setDuration(minutes) },
                label = { Text(if (minutes < 60) strings.minutesShort(minutes) else strings.hoursShort(minutes / 60)) },
            )
        }
    }
    RadioRow(strings.at, state.stopMode == StopMode.UNTIL) { viewModel.setStopMode(StopMode.UNTIL) }
    Row(Modifier.padding(start = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = state.untilText,
            onValueChange = viewModel::setUntil,
            placeholder = { Text("21:30") },
            singleLine = true,
            isError = state.untilError != null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(110.dp),
        )
        val preview = viewModel.untilPreview(state.untilText)
        Text(
            state.untilError ?: preview.orEmpty(),
            color = if (state.untilError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}

package io.github.aalpersen.iptvdler.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aalpersen.iptvdler.BuildInfo
import io.github.aalpersen.iptvdler.core.storage.DownloadStorage
import io.github.aalpersen.iptvdler.core.subtitle.SubtitleOutput
import io.github.aalpersen.iptvdler.data.settings.AppSettings
import io.github.aalpersen.iptvdler.i18n.AppLanguage
import io.github.aalpersen.iptvdler.i18n.strings
import io.github.aalpersen.iptvdler.ui.platform.rememberFolderPicker
import io.github.aalpersen.iptvdler.ui.platform.rememberOpenLogFolder
import io.github.aalpersen.iptvdler.ui.platform.supportsWifiOnlySetting
import io.github.aalpersen.iptvdler.data.update.UpdateChecker
import io.github.aalpersen.iptvdler.ui.screens.DisclaimerText
import io.github.aalpersen.iptvdler.ui.screens.IptvdlerScaffold
import org.koin.compose.viewmodel.koinViewModel

class SettingsViewModel(
    val settings: AppSettings,
    private val storage: DownloadStorage,
    private val updateChecker: UpdateChecker,
) : ViewModel() {
    fun downloadFolder(): String = storage.downloadsDirectory().toString()

    fun setUpdateCheckEnabled(enabled: Boolean) {
        settings.setUpdateCheckEnabled(enabled)
        if (enabled) updateChecker.checkInBackground()
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = koinViewModel()) {
    val settings = viewModel.settings
    val parallel by settings.parallelSegments.collectAsStateWithLifecycle()
    val concurrent by settings.maxConcurrentDownloads.collectAsStateWithLifecycle()
    val wifiOnly by settings.wifiOnly.collectAsStateWithLifecycle()
    val updateCheck by settings.updateCheckEnabled.collectAsStateWithLifecycle()
    val customFolder by settings.downloadFolder.collectAsStateWithLifecycle()
    val subtitleOutput by settings.subtitleOutput.collectAsStateWithLifecycle()
    val pickFolder = rememberFolderPicker { settings.setDownloadFolder(it) }
    val openLogFolder = rememberOpenLogFolder()
    var showDisclaimer by remember { mutableStateOf(false) }
    val language by settings.language.collectAsStateWithLifecycle()

    IptvdlerScaffold(title = strings.settings, onBack = onBack) { modifier ->
        Column(modifier.verticalScroll(rememberScrollState())) {
            SectionTitle(strings.sectionLanguage)
            AppLanguage.entries.forEach { option ->
                ListItem(
                    headlineContent = { Text(option.displayName()) },
                    leadingContent = { RadioButton(selected = language == option, onClick = null) },
                    modifier = Modifier.selectable(selected = language == option, role = Role.RadioButton) {
                        settings.setLanguage(option)
                    },
                )
            }
            HorizontalDivider()

            SectionTitle(strings.sectionDownloads)

            ListItem(
                headlineContent = { Text(strings.downloadFolder) },
                supportingContent = {
                    Column {
                        // Recomputed when the custom folder changes.
                        Text(customFolder ?: viewModel.downloadFolder())
                        if (pickFolder != null) {
                            OutlinedButton(onClick = pickFolder, modifier = Modifier.padding(top = 8.dp)) { Text(strings.changeFolder) }
                            if (customFolder != null) {
                                TextButton(onClick = { settings.setDownloadFolder(null) }) { Text(strings.useDefault) }
                            }
                        }
                    }
                },
            )

            SliderSetting(
                title = strings.parallelSegments,
                description = strings.parallelSegmentsDescription,
                value = parallel,
                range = AppSettings.PARALLEL_RANGE,
                onChange = settings::setParallelSegments,
            )
            SliderSetting(
                title = strings.simultaneousDownloads,
                description = strings.simultaneousDownloadsDescription,
                value = concurrent,
                range = AppSettings.CONCURRENT_RANGE,
                onChange = settings::setMaxConcurrentDownloads,
            )

            if (supportsWifiOnlySetting) {
                ListItem(
                    headlineContent = { Text(strings.wifiOnly) },
                    supportingContent = { Text(strings.wifiOnlyDescription) },
                    trailingContent = { Switch(checked = wifiOnly, onCheckedChange = settings::setWifiOnly) },
                )
            }
            SectionTitle(strings.sectionSubtitles)
            ListItem(
                headlineContent = { Text(strings.subtitleFormat) },
                supportingContent = {
                    Column {
                        Text(strings.subtitleFormatDescription)
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            SubtitleOutput.entries.forEachIndexed { index, option ->
                                SegmentedButton(
                                    selected = subtitleOutput == option,
                                    onClick = { settings.setSubtitleOutput(option) },
                                    shape = SegmentedButtonDefaults.itemShape(index, SubtitleOutput.entries.size),
                                ) { Text(if (option == SubtitleOutput.BOTH) strings.both else option.name) }
                            }
                        }
                    }
                },
            )
            HorizontalDivider()

            SectionTitle(strings.sectionAbout)
            ListItem(
                headlineContent = { Text("iptvdler ${BuildInfo.VERSION_NAME}") },
                supportingContent = { Text(strings.aboutDescription) },
            )
            ListItem(
                headlineContent = { Text(strings.checkForUpdates) },
                supportingContent = { Text(strings.checkForUpdatesDescription) },
                trailingContent = { Switch(checked = updateCheck, onCheckedChange = viewModel::setUpdateCheckEnabled) },
            )
            ListItem(
                headlineContent = { Text(strings.disclaimer) },
                supportingContent = { Text(strings.disclaimerDescription) },
                modifier = Modifier.clickable { showDisclaimer = true },
            )
            if (openLogFolder != null) {
                ListItem(
                    headlineContent = { Text(strings.crashLogs) },
                    supportingContent = { Text(strings.crashLogsDescription) },
                    modifier = Modifier.clickable(onClick = openLogFolder),
                )
            }
        }
    }

    if (showDisclaimer) {
        AlertDialog(
            onDismissRequest = { showDisclaimer = false },
            title = { Text(strings.disclaimer) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { DisclaimerText() } },
            confirmButton = { TextButton(onClick = { showDisclaimer = false }) { Text(strings.close) } },
        )
    }
}

/** Languages are listed in their own language, so users can find theirs whatever is showing. */
@Composable
private fun AppLanguage.displayName(): String = when (this) {
    AppLanguage.SYSTEM -> strings.languageSystem
    AppLanguage.ENGLISH -> "English"
    AppLanguage.TURKISH -> "Türkçe"
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun SliderSetting(title: String, description: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    ListItem(
        headlineContent = { Text("$title: $value") },
        supportingContent = {
            Column {
                Text(description)
                Slider(
                    value = value.toFloat(),
                    onValueChange = { onChange(it.toInt()) },
                    valueRange = range.first.toFloat()..range.last.toFloat(),
                    steps = (range.last - range.first - 1).coerceAtLeast(0),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )
}

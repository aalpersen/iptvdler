package io.github.aalpersen.iptvdler.data.settings

import io.github.aalpersen.iptvdler.core.subtitle.SubtitleOutput
import io.github.aalpersen.iptvdler.data.update.AvailableUpdate
import io.github.aalpersen.iptvdler.data.update.UpdateChecker
import io.github.aalpersen.iptvdler.i18n.AppLanguage
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Typed access to user preferences, backed by a platform [Settings] store.
 * [defaultWifiOnly]: downloads wait for Wi-Fi unless the user turns it off (on for phones).
 */
class AppSettings(private val settings: Settings, defaultWifiOnly: Boolean = false) {

    private val _disclaimerAccepted = MutableStateFlow(settings.getBoolean(KEY_DISCLAIMER_ACCEPTED, false))
    val disclaimerAccepted: StateFlow<Boolean> = _disclaimerAccepted.asStateFlow()

    fun acceptDisclaimer() {
        settings.putBoolean(KEY_DISCLAIMER_ACCEPTED, true)
        _disclaimerAccepted.value = true
    }

    private val _parallelSegments = MutableStateFlow(settings.getInt(KEY_PARALLEL_SEGMENTS, 4).coerceIn(PARALLEL_RANGE))

    /** Segments downloaded at the same time per task. */
    val parallelSegments: StateFlow<Int> = _parallelSegments.asStateFlow()

    fun setParallelSegments(value: Int) {
        val v = value.coerceIn(PARALLEL_RANGE)
        settings.putInt(KEY_PARALLEL_SEGMENTS, v)
        _parallelSegments.value = v
    }

    private val _maxConcurrentDownloads = MutableStateFlow(settings.getInt(KEY_MAX_DOWNLOADS, 2).coerceIn(CONCURRENT_RANGE))

    /** Tasks running at the same time; the rest wait in the queue. */
    val maxConcurrentDownloads: StateFlow<Int> = _maxConcurrentDownloads.asStateFlow()

    fun setMaxConcurrentDownloads(value: Int) {
        val v = value.coerceIn(CONCURRENT_RANGE)
        settings.putInt(KEY_MAX_DOWNLOADS, v)
        _maxConcurrentDownloads.value = v
    }

    private val _wifiOnly = MutableStateFlow(settings.getBoolean(KEY_WIFI_ONLY, defaultWifiOnly))
    val wifiOnly: StateFlow<Boolean> = _wifiOnly.asStateFlow()

    fun setWifiOnly(value: Boolean) {
        settings.putBoolean(KEY_WIFI_ONLY, value)
        _wifiOnly.value = value
    }

    private val _downloadFolder = MutableStateFlow(settings.getStringOrNull(KEY_DOWNLOAD_FOLDER))

    /** User-chosen download folder (desktop); null = platform default. */
    val downloadFolder: StateFlow<String?> = _downloadFolder.asStateFlow()

    fun setDownloadFolder(path: String?) {
        if (path == null) settings.remove(KEY_DOWNLOAD_FOLDER) else settings.putString(KEY_DOWNLOAD_FOLDER, path)
        _downloadFolder.value = path
    }

    private val _subtitleOutput = MutableStateFlow(
        settings.getStringOrNull(KEY_SUBTITLE_OUTPUT)?.let { v -> SubtitleOutput.entries.firstOrNull { it.name == v } } ?: SubtitleOutput.SRT,
    )

    /** Subtitle file format(s) written next to downloads. */
    val subtitleOutput: StateFlow<SubtitleOutput> = _subtitleOutput.asStateFlow()

    fun setSubtitleOutput(value: SubtitleOutput) {
        settings.putString(KEY_SUBTITLE_OUTPUT, value.name)
        _subtitleOutput.value = value
    }

    private val _language = MutableStateFlow(
        settings.getStringOrNull(KEY_LANGUAGE)?.let { v -> AppLanguage.entries.firstOrNull { it.name == v } } ?: AppLanguage.SYSTEM,
    )

    /** UI language; [AppLanguage.SYSTEM] follows the device. */
    val language: StateFlow<AppLanguage> = _language.asStateFlow()

    fun setLanguage(value: AppLanguage) {
        settings.putString(KEY_LANGUAGE, value.name)
        _language.value = value
    }

    private val _updateCheckEnabled = MutableStateFlow(settings.getBoolean(KEY_UPDATE_CHECK, true))

    /** Whether the app asks GitHub (at most daily) if a newer release exists. */
    val updateCheckEnabled: StateFlow<Boolean> = _updateCheckEnabled.asStateFlow()

    fun setUpdateCheckEnabled(value: Boolean) {
        settings.putBoolean(KEY_UPDATE_CHECK, value)
        _updateCheckEnabled.value = value
    }

    /** Time of the last completed update check (0 = never). */
    val lastUpdateCheckMillis: Long get() = settings.getLong(KEY_UPDATE_LAST_CHECK, 0L)

    fun setLastUpdateCheckMillis(value: Long) = settings.putLong(KEY_UPDATE_LAST_CHECK, value)

    private val _latestRelease = MutableStateFlow(
        settings.getStringOrNull(KEY_UPDATE_LATEST_VERSION)?.let { version ->
            AvailableUpdate(version, settings.getString(KEY_UPDATE_LATEST_URL, UpdateChecker.RELEASES_PAGE))
        },
    )

    /** Newest published release seen by the last check (may equal the running version). */
    val latestRelease: StateFlow<AvailableUpdate?> = _latestRelease.asStateFlow()

    fun setLatestRelease(value: AvailableUpdate) {
        settings.putString(KEY_UPDATE_LATEST_VERSION, value.version)
        settings.putString(KEY_UPDATE_LATEST_URL, value.pageUrl)
        _latestRelease.value = value
    }

    private val _dismissedUpdate = MutableStateFlow(settings.getStringOrNull(KEY_UPDATE_DISMISSED))

    /** Version whose "update available" banner the user closed. */
    val dismissedUpdate: StateFlow<String?> = _dismissedUpdate.asStateFlow()

    fun setDismissedUpdate(version: String) {
        settings.putString(KEY_UPDATE_DISMISSED, version)
        _dismissedUpdate.value = version
    }

    /** One-time data fix-ups already applied (by name). */
    fun isMaintenanceDone(name: String): Boolean = settings.getBoolean("maintenance_$name", false)

    fun markMaintenanceDone(name: String) = settings.putBoolean("maintenance_$name", true)

    companion object {
        val PARALLEL_RANGE = 1..16
        val CONCURRENT_RANGE = 1..5
        private const val KEY_DISCLAIMER_ACCEPTED = "disclaimer_accepted"
        private const val KEY_PARALLEL_SEGMENTS = "parallel_segments"
        private const val KEY_MAX_DOWNLOADS = "max_concurrent_downloads"
        private const val KEY_WIFI_ONLY = "wifi_only"
        private const val KEY_DOWNLOAD_FOLDER = "download_folder"
        private const val KEY_SUBTITLE_OUTPUT = "subtitle_output"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_UPDATE_CHECK = "update_check"
        private const val KEY_UPDATE_LAST_CHECK = "update_last_check"
        private const val KEY_UPDATE_LATEST_VERSION = "update_latest_version"
        private const val KEY_UPDATE_LATEST_URL = "update_latest_url"
        private const val KEY_UPDATE_DISMISSED = "update_dismissed"
    }
}

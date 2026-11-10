package io.github.aalpersen.iptvdler.i18n

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.intl.Locale

/** Language choice in Settings. [tag] is the ISO 639-1 code; null follows the system language. */
enum class AppLanguage(val tag: String?) {
    SYSTEM(null),
    ENGLISH("en"),
    TURKISH("tr"),
}

/**
 * The current UI language. [strings] is Compose snapshot state: composables that read it recompose when
 * the language changes, and non-UI code (download engine, notifications, dialogs) can read it too.
 */
object I18n {
    var strings: Strings by mutableStateOf(EnStrings)
        private set

    fun apply(language: AppLanguage, systemLanguage: String = Locale.current.language) {
        strings = forTag(language.tag ?: systemLanguage)
    }

    fun forTag(tag: String): Strings = when (tag.lowercase().substringBefore('-').substringBefore('_')) {
        "tr" -> TrStrings
        else -> EnStrings
    }
}

/**
 * Every user-facing text. Each language implements all of it, so a missing translation is a compile error.
 * Add a language: implement this interface, add it to [AppLanguage] and [I18n.forTag].
 */
interface Strings {
    // Number formatting
    val decimalSeparator: Char
    val groupSeparator: Char
    fun percent(value: Int): String
    fun seconds(n: Long): String
    fun minutes(n: Long): String
    fun hoursMinutes(h: Long, m: Long): String
    val justNow: String
    fun minutesAgo(n: Long): String
    fun hoursAgo(n: Long): String
    fun daysAgo(n: Long): String
    fun perSecond(size: String): String

    // Common
    val back: String
    val cancel: String
    val close: String
    val delete: String
    val add: String
    val save: String
    val retry: String
    val play: String
    val pause: String
    val resume: String
    val download: String
    val recordAction: String
    val openDownloads: String
    val ungrouped: String
    val nothingHere: String
    val search: String
    val live: String
    val liveBadge: String
    val movie: String
    val series: String
    val quality: String
    val audio: String
    val subtitles: String
    val off: String
    val auto: String
    val done: String

    // Disclaimer
    val welcome: String
    val disclaimerPoints: List<String>
    val iUnderstand: String
    val disclaimer: String

    // Playlists
    val playlists: String
    val downloads: String
    val settings: String
    val addPlaylist: String
    val noPlaylistsTitle: String
    val noPlaylistsMessage: String
    val deletePlaylistTitle: String
    fun deletePlaylistMessage(name: String): String
    fun loadingEntries(count: String): String
    fun channelsUpdated(count: Long, countText: String, ago: String): String
    val notLoadedYet: String
    fun refreshFailed(error: String): String
    val playlistActions: String
    val refresh: String
    val edit: String

    // Playlist dialog
    val editPlaylist: String
    val name: String
    val url: String
    val file: String
    val m3uUrl: String
    val urlInvalid: String
    val chooseFile: String
    val changeFile: String
    val manualOnly: String
    val every6Hours: String
    val every12Hours: String
    val everyDay: String
    val every3Days: String
    val everyWeek: String
    fun everyMinutes(n: Long): String
    fun autoRefresh(label: String): String

    // Groups and channels
    val groups: String
    val searchChannels: String
    val all: String
    val movies: String
    val seriesPlural: String
    val favorites: String
    val recentlyWatched: String
    val allChannels: String
    val playlistHasNoChannels: String
    val noChannelsOfType: String
    val clearSearch: String
    val noMatches: String
    fun noChannelsMatch(query: String): String
    val favoritesEmpty: String
    val recentEmpty: String
    val noChannels: String
    val addToFavorites: String
    val removeFromFavorites: String

    // Player
    val playbackFailed: String
    val mute: String
    val unmute: String
    val qualityAndTracks: String
    fun aspectRatio(mode: String): String
    val aspectFit: String
    val aspectFill: String
    val aspectStretch: String
    val pictureInPicture: String
    val fullscreen: String
    val exitFullscreen: String

    // Download dialog
    val original: String
    fun readStreamFailed(error: String): String
    fun startDownloadFailed(error: String): String
    val downloadStarted: String
    val followInDownloads: String
    fun singleFile(size: String): String
    fun length(duration: String): String
    val forced: String
    val separateAudioNote: String

    // Record dialog
    val recordTitle: String
    val recordConfirm: String
    val originalStream: String
    fun startRecordingFailed(error: String): String
    val recordingStarted: String
    val followAndStopInDownloads: String
    val stopRecording: String
    val whenIStopIt: String
    val after: String
    val at: String
    fun minutesShort(n: Int): String
    fun hoursShort(n: Int): String
    val enterTimeLike: String
    fun today(clock: String): String
    fun tomorrow(clock: String): String

    // Downloads screen
    fun addedSubtitles(language: String): String
    fun addSubtitlesFailed(error: String): String
    val sectionRecording: String
    val sectionDownloading: String
    val sectionQueued: String
    val sectionPaused: String
    val sectionFailed: String
    val sectionCompleted: String
    val noDownloads: String
    val noDownloadsMessage: String
    val audioSavedSeparately: String
    val downloadActions: String
    val addSubtitlesFromUrl: String
    val deleteDownloadTitle: String
    val cancelDownloadTitle: String
    val alsoDeleteFile: String
    val partialDataRemoved: String
    val keep: String
    val addSubtitles: String
    val addSubtitlesHint: String
    val subtitleUrl: String
    val languageCodeHint: String
    fun subtitlesList(languages: String): String
    fun subtitlesFailed(languages: String): String
    fun recIndicator(length: String): String
    fun recordingUntil(clock: String): String
    val startingRecording: String
    val recordingLabel: String
    fun recordingStoppedWithError(error: String, length: String): String
    fun recordingStopped(length: String): String
    val failedLower: String
    fun sizeOf(downloaded: String, total: String, approximate: Boolean): String
    fun timeLeft(eta: String): String
    val waitingToStart: String
    fun pausedWithSize(size: String): String
    val failed: String

    // Download engine
    val noteRecording: String
    fun segmentsMissed(n: Int): String
    val noteReconnecting: String
    val noteAssembling: String
    val noteConnectionLost: String
    val noteWaitingForWifi: String
    val noteRetryingSoon: String
    fun networkProblemRetrying(error: String): String
    val nothingRecorded: String
    val downloadFailed: String
    fun notEnoughSpace(neededMb: Long, availableMb: Long?): String
    val liveDownloadRefused: String
    val notLiveRecording: String
    val drmDownloadRefused: String
    val streamKeepsEnding: String
    val subtitleSegmentsMissing: String
    val somethingWentWrong: String

    // Error messages
    fun httpAccessDenied(status: Int): String
    fun httpNotFound(status: Int): String
    val httpRateLimited: String
    fun httpServerError(status: Int): String
    fun httpOther(status: Int): String
    val errorNoHost: String
    val errorNoConnection: String
    val errorTimeout: String
    val errorTls: String
    val errorNotTls: String
    val errorDetails: String
    fun redirectedTo(url: String): String
    val errorStorageFull: String
    val errorStorageAccess: String

    // Playlist refresh
    fun readPlaylistFailed(error: String): String
    val notAnM3u: String
    val playlistHasNoEntries: String
    val playlistNotFound: String
    val playlistTooLarge: String
    val masterHasNoVariants: String

    // Settings
    val sectionDownloads: String
    val downloadFolder: String
    val changeFolder: String
    val useDefault: String
    val parallelSegments: String
    val parallelSegmentsDescription: String
    val simultaneousDownloads: String
    val simultaneousDownloadsDescription: String
    val wifiOnly: String
    val wifiOnlyDescription: String
    val sectionSubtitles: String
    val subtitleFormat: String
    val subtitleFormatDescription: String
    val both: String
    val sectionLanguage: String
    val languageSystem: String
    val sectionAbout: String
    val aboutDescription: String
    val disclaimerDescription: String
    val crashLogs: String
    val crashLogsDescription: String
    val checkForUpdates: String
    val checkForUpdatesDescription: String
    fun updateAvailable(version: String): String
    val updateAvailableDescription: String
    val updateOpen: String
    val updateDismiss: String

    // Platform
    val showInFolder: String
    val exportFile: String
    val exported: String
    val exportFailed: String
    val chooseM3uFile: String
    val chooseDownloadFolder: String
    val quitTitle: String
    fun quitDownloadsInProgress(n: Int): String
    fun quitRecordingsRunning(n: Int): String
    val quit: String
    val keepRunning: String
    val alreadyRunning: String
    val crashMessage: String
    fun crashSavedTo(path: String): String
    val vlcMissing: String
    val vlc32Bit: String
    val preparingDownloads: String
    fun downloadsCount(n: Int): String
    val notificationRecording: String
    val waiting: String
    val notificationChannel: String
    val notificationChannelDescription: String
    fun playerHttpError(code: Int): String
    val playerDrm: String
    val playerConnection: String
    val playerFormat: String
    fun playerFailed(code: String): String

    // Xtream Codes
    val xtream: String
    val xtreamServer: String
    val xtreamServerInvalid: String
    val username: String
    val password: String
    val showPassword: String
    val hidePassword: String
    val xtreamPasteHint: String
    val xtreamLinkDetected: String
    val useAsXtream: String
    val xtreamAuthFailed: String
    fun xtreamAccountNotActive(status: String): String
    val xtreamNotAServer: String
    fun accountActiveUntil(date: String): String
    val accountActiveNoExpiry: String
    fun accountExpiredOn(date: String): String
    fun date(year: Int, month: Int, day: Int): String
    fun season(n: Int): String
    fun episodesLoadFailed(error: String): String
    val noEpisodes: String
}

/** Shorthand for [I18n.strings]; reading it in a composable recomposes on language change. */
val strings: Strings get() = I18n.strings

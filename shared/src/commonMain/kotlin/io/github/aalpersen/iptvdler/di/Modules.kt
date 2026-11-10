package io.github.aalpersen.iptvdler.di

import io.github.aalpersen.iptvdler.core.crypto.createAes128CbcDecryptor
import io.github.aalpersen.iptvdler.core.download.DownloadManager
import io.github.aalpersen.iptvdler.core.download.DownloadPlanner
import io.github.aalpersen.iptvdler.core.model.EpochClock
import io.github.aalpersen.iptvdler.data.db.DriverFactory
import io.github.aalpersen.iptvdler.data.db.createDatabase
import io.github.aalpersen.iptvdler.data.download.DownloadRepository
import io.github.aalpersen.iptvdler.data.hls.HlsClient
import io.github.aalpersen.iptvdler.data.network.createHttpClient
import io.github.aalpersen.iptvdler.data.playlist.PlaylistRefresher
import io.github.aalpersen.iptvdler.data.playlist.PlaylistRepository
import io.github.aalpersen.iptvdler.data.settings.AppSettings
import io.github.aalpersen.iptvdler.data.update.UpdateChecker
import io.github.aalpersen.iptvdler.data.xtream.XtreamClient
import io.github.aalpersen.iptvdler.i18n.I18n
import io.github.aalpersen.iptvdler.ui.channels.ChannelsViewModel
import io.github.aalpersen.iptvdler.ui.channels.GroupsViewModel
import io.github.aalpersen.iptvdler.ui.channels.SeriesViewModel
import io.github.aalpersen.iptvdler.ui.downloads.DownloadDialogViewModel
import io.github.aalpersen.iptvdler.ui.downloads.DownloadsViewModel
import io.github.aalpersen.iptvdler.ui.downloads.RecordDialogViewModel
import io.github.aalpersen.iptvdler.ui.platform.supportsWifiOnlySetting
import io.github.aalpersen.iptvdler.ui.player.PlayerViewModel
import io.github.aalpersen.iptvdler.ui.playlists.PlaylistsViewModel
import io.github.aalpersen.iptvdler.ui.screens.DisclaimerViewModel
import io.github.aalpersen.iptvdler.ui.settings.SettingsViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.core.qualifier.named
import org.koin.dsl.KoinAppDeclaration
import org.koin.dsl.module

val IoDispatcher = named("io")

/** Process-wide scope for work that must outlive a screen (refreshes, later downloads). */
val AppScope = named("appScope")

/**
 * Platform bindings every target must provide:
 * [com.russhwolf.settings.Settings], [DriverFactory],
 * [io.github.aalpersen.iptvdler.data.playlist.LocalFileSource], [io.github.aalpersen.iptvdler.core.player.VideoPlayerFactory],
 * [io.github.aalpersen.iptvdler.core.storage.DownloadStorage], [io.github.aalpersen.iptvdler.core.storage.NetworkMonitor]
 * and [io.github.aalpersen.iptvdler.core.download.DownloadForegroundController].
 */
expect val platformModule: Module

val dataModule = module {
    single<CoroutineDispatcher>(IoDispatcher) { Dispatchers.IO }
    single<CoroutineScope>(AppScope) { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    single<EpochClock> { EpochClock.System }
    single { createHttpClient() }
    single { get<DriverFactory>().createDatabase() }
    // Mobile data costs money: on phones, downloads wait for Wi-Fi by default.
    single { AppSettings(get(), defaultWifiOnly = supportsWifiOnlySetting) }
    single { PlaylistRepository(get(), get(IoDispatcher), get()) }
    single { HlsClient(get()) }
    single { XtreamClient(get()) }
    single { DownloadRepository(get(), get(IoDispatcher), get()) }
    single { DownloadPlanner(get(), get(), get()) }
    single {
        DownloadManager(
            repository = get(),
            httpClient = get(),
            storage = get(),
            settings = get(),
            networkMonitor = get(),
            foreground = get(),
            decryptor = createAes128CbcDecryptor(),
            scope = get(AppScope),
            clock = get(),
        )
    }
    single { PlaylistRefresher(get(), get(), get(), get(IoDispatcher), get(AppScope), get(), get()) }
    single { UpdateChecker(get(), get(), get(), get(AppScope)) }
}

val viewModelModule = module {
    viewModelOf(::DisclaimerViewModel)
    viewModelOf(::PlaylistsViewModel)
    viewModel { params -> GroupsViewModel(params.get(), get()) }
    viewModel { params -> ChannelsViewModel(params.get(), get()) }
    viewModel { params -> SeriesViewModel(params.get(), get(), get()) }
    viewModel { params -> PlayerViewModel(params.get(), get(), get(), get(), get()) }
    viewModel { params -> DownloadDialogViewModel(params.get(), get(), get(), get()) }
    viewModel { params -> RecordDialogViewModel(params.get(), get(), get(), get(), get()) }
    viewModelOf(::DownloadsViewModel)
    viewModelOf(::SettingsViewModel)
}

fun initKoin(appDeclaration: KoinAppDeclaration = {}) = startKoin {
    appDeclaration()
    modules(platformModule, dataModule, viewModelModule)
}.also {
    bindLanguage(it.koin.get(), it.koin.get(AppScope))
    runStartupMaintenance(it.koin)
    it.koin.get<PlaylistRefresher>().startAutoRefresh()
    it.koin.get<DownloadManager>().start()
    it.koin.get<UpdateChecker>().checkInBackground()
}

/** Applies the saved UI language now (before the first frame) and whenever it changes. */
fun bindLanguage(settings: AppSettings, scope: CoroutineScope) {
    I18n.apply(settings.language.value)
    scope.launch { settings.language.collect { I18n.apply(it) } }
}

/** One-time data fix-ups, run in the background on the first start after an upgrade. */
private fun runStartupMaintenance(koin: org.koin.core.Koin) {
    val settings = koin.get<AppSettings>()
    val key = "search_names_v4"
    if (settings.isMaintenanceDone(key)) return
    koin.get<CoroutineScope>(AppScope).launch {
        runCatching { koin.get<PlaylistRepository>().backfillSearchNames() }
            .onSuccess { settings.markMaintenanceDone(key) }
    }
}

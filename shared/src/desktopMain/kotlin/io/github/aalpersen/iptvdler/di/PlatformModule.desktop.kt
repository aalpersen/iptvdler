package io.github.aalpersen.iptvdler.di

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.aalpersen.iptvdler.core.download.DownloadForegroundController
import io.github.aalpersen.iptvdler.core.player.VideoPlayerFactory
import io.github.aalpersen.iptvdler.core.storage.DownloadStorage
import io.github.aalpersen.iptvdler.core.storage.NetworkMonitor
import io.github.aalpersen.iptvdler.platform.DesktopDownloadStorage
import io.github.aalpersen.iptvdler.ui.platform.QuitGuard
import io.github.aalpersen.iptvdler.data.db.IptvdlerDatabase
import io.github.aalpersen.iptvdler.player.VlcjVideoPlayerFactory
import io.github.aalpersen.iptvdler.data.db.DriverFactory
import io.github.aalpersen.iptvdler.data.playlist.LocalFileSource
import io.github.aalpersen.iptvdler.platform.AppDirs
import com.russhwolf.settings.PreferencesSettings
import com.russhwolf.settings.Settings
import java.io.File
import java.util.Properties
import java.util.prefs.Preferences
import okio.FileSystem
import okio.Path.Companion.toPath
import org.koin.core.module.Module
import org.koin.dsl.module

actual val platformModule: Module = module {
    single<Settings> { PreferencesSettings(Preferences.userRoot().node("io/github/aalpersen/iptvdler")) }
    single<DriverFactory> {
        DriverFactory {
            val dbFile = File(AppDirs.dataDir, "iptvdler.db")
            JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}", Properties(), IptvdlerDatabase.Schema)
        }
    }
    single<LocalFileSource> { LocalFileSource { location -> FileSystem.SYSTEM.source(location.toPath()) } }
    single<VideoPlayerFactory> { VlcjVideoPlayerFactory() }
    single<DownloadStorage> { DesktopDownloadStorage(get()) }
    single<NetworkMonitor> { NetworkMonitor { true } }
    single<DownloadForegroundController> { DownloadForegroundController { } }
    single { QuitGuard(get(), get(AppScope)) }
}

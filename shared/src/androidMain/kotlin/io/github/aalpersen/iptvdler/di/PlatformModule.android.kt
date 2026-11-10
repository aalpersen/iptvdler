package io.github.aalpersen.iptvdler.di

import android.content.Context
import android.net.Uri
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import io.github.aalpersen.iptvdler.core.download.DownloadForegroundController
import io.github.aalpersen.iptvdler.core.player.VideoPlayerFactory
import io.github.aalpersen.iptvdler.core.storage.DownloadStorage
import io.github.aalpersen.iptvdler.core.storage.NetworkMonitor
import io.github.aalpersen.iptvdler.platform.AndroidDownloadForegroundController
import io.github.aalpersen.iptvdler.platform.AndroidDownloadStorage
import io.github.aalpersen.iptvdler.platform.AndroidNetworkMonitor
import io.github.aalpersen.iptvdler.data.db.IptvdlerDatabase
import io.github.aalpersen.iptvdler.data.db.DriverFactory
import io.github.aalpersen.iptvdler.data.playlist.LocalFileSource
import io.github.aalpersen.iptvdler.player.ExoVideoPlayer
import com.russhwolf.settings.Settings
import com.russhwolf.settings.SharedPreferencesSettings
import okio.FileNotFoundException
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.source
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

actual val platformModule: Module = module {
    single<Settings> {
        SharedPreferencesSettings(androidContext().getSharedPreferences("iptvdler_settings", Context.MODE_PRIVATE))
    }
    single<DriverFactory> {
        val context = androidContext()
        DriverFactory { AndroidSqliteDriver(IptvdlerDatabase.Schema, context, "iptvdler.db") }
    }
    single<LocalFileSource> {
        val resolver = androidContext().contentResolver
        LocalFileSource { location ->
            if (location.startsWith("content://")) {
                resolver.openInputStream(Uri.parse(location))?.source()
                    ?: throw FileNotFoundException("Can't open $location")
            } else {
                FileSystem.SYSTEM.source(location.toPath())
            }
        }
    }
    single<DownloadStorage> { AndroidDownloadStorage(androidContext()) }
    single<NetworkMonitor> { AndroidNetworkMonitor(androidContext()) }
    single<DownloadForegroundController> { AndroidDownloadForegroundController(androidContext()) }
    single<VideoPlayerFactory> {
        val context = androidContext()
        VideoPlayerFactory { ExoVideoPlayer(context) }
    }
}

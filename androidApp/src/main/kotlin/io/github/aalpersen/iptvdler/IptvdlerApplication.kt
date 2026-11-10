package io.github.aalpersen.iptvdler

import android.app.Application
import io.github.aalpersen.iptvdler.di.initKoin
import io.github.aalpersen.iptvdler.platform.CrashReporter
import org.koin.android.ext.koin.androidContext

class IptvdlerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        initKoin {
            androidContext(this@IptvdlerApplication)
        }
    }
}

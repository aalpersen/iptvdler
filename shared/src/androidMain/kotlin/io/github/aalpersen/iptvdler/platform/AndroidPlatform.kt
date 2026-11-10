package io.github.aalpersen.iptvdler.platform

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Environment
import androidx.core.content.ContextCompat
import io.github.aalpersen.iptvdler.core.download.DownloadForegroundController
import io.github.aalpersen.iptvdler.core.storage.DownloadStorage
import io.github.aalpersen.iptvdler.core.storage.NetworkMonitor
import io.github.aalpersen.iptvdler.download.DownloadService
import java.io.File
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath

/** App-specific storage (no permission needed): Android/data/<app>/files/Movies/iptvdler. */
class AndroidDownloadStorage(private val context: Context) : DownloadStorage {
    override val fileSystem: FileSystem = FileSystem.SYSTEM

    override fun downloadsDirectory(): Path {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: File(context.filesDir, "movies")
        val dir = File(base, "iptvdler")
        dir.mkdirs()
        return dir.toOkioPath()
    }

    override fun freeBytes(path: Path): Long? =
        generateSequence(path.toFile()) { it.parentFile }.firstOrNull(File::exists)?.usableSpace?.takeIf { it > 0 }
}

class AndroidNetworkMonitor(context: Context) : NetworkMonitor {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    override fun isOnUnmeteredNetwork(): Boolean {
        val caps = connectivity?.getNetworkCapabilities(connectivity.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}

/** Starts the foreground service when downloads become active; the service stops itself when they end. */
class AndroidDownloadForegroundController(private val context: Context) : DownloadForegroundController {
    @Volatile private var active = false

    override fun onActiveDownloadsChanged(active: Boolean) {
        if (active && !this.active) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java)) }
        }
        this.active = active
    }
}

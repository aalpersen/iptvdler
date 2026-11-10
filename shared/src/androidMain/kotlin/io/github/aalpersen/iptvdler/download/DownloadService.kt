package io.github.aalpersen.iptvdler.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.aalpersen.iptvdler.core.download.DownloadItem
import io.github.aalpersen.iptvdler.core.download.DownloadManager
import io.github.aalpersen.iptvdler.core.download.DownloadStatus
import io.github.aalpersen.iptvdler.i18n.strings
import io.github.aalpersen.iptvdler.ui.formatBytes
import io.github.aalpersen.iptvdler.ui.formatDuration
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Keeps downloads alive while the app is in the background (foregroundServiceType="dataSync") and
 * shows their progress. Started by AndroidDownloadForegroundController; stops itself when idle.
 */
@OptIn(FlowPreview::class)
class DownloadService : Service(), KoinComponent {

    private val manager: DownloadManager by inject()
    private val scope = MainScope()
    private var collecting = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        // Every startForegroundService() must be answered with startForeground() within a few seconds.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(emptyList()),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        if (!collecting) {
            collecting = true
            scope.launch {
                manager.items.sample(1_000).collect { items ->
                    val active = items.filter { it.task.status == DownloadStatus.RUNNING || it.task.status == DownloadStatus.QUEUED }
                    if (active.isEmpty()) {
                        ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(active))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(active: List<DownloadItem>): Notification {
        val running = active.filter { it.task.status == DownloadStatus.RUNNING }
        val first = running.firstOrNull() ?: active.firstOrNull()
        val progress = first?.progress
        val percent = progress?.fraction?.let { (it * 100).toInt() }

        val title = when {
            first == null -> strings.preparingDownloads
            active.size == 1 -> first.task.title
            else -> strings.downloadsCount(active.size)
        }
        val text = when {
            first == null -> null
            first.task.isRecording && first.task.status == DownloadStatus.RUNNING -> buildString {
                append(strings.notificationRecording).append(" · ")
                append(formatDuration(((first.task.durationSeconds ?: 0.0) * 1000).toLong()))
                append(" · ").append(formatBytes(progress?.downloadedBytes ?: first.task.downloadedBytes))
            }
            progress?.note != null -> progress.note
            progress != null -> buildString {
                percent?.let { append(strings.percent(it)).append(" · ") }
                append(formatBytes(progress.downloadedBytes))
                if (progress.bytesPerSecond > 0) append(" · ").append(strings.perSecond(formatBytes(progress.bytesPerSecond)))
            }
            else -> strings.waiting
        }

        val openApp = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(100, percent ?: 0, percent == null && first?.task?.isRecording != true)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, strings.notificationChannel, NotificationManager.IMPORTANCE_LOW).apply {
            description = strings.notificationChannelDescription
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private companion object {
        const val CHANNEL_ID = "downloads"
        const val NOTIFICATION_ID = 1001
    }
}

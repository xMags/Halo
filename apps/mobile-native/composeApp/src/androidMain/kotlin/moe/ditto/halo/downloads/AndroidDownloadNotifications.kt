package moe.ditto.halo.downloads

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ForegroundInfo
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import moe.ditto.halo.HaloApplication
import moe.ditto.halo.MainActivity
import moe.ditto.halo.R

internal class AndroidDownloadNotifications(private val context: Context) {
    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    ProgressChannel,
                    "Background downloads",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Progress for media saved for offline playback"
                    lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
                },
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    ResultChannel,
                    "Download results",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "Completion and failure of offline downloads"
                    lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
                },
            )
        }
    }

    fun foreground(jobId: String, downloadedBytes: Long, totalBytes: Long): ForegroundInfo {
        val notification = NotificationCompat.Builder(context, ProgressChannel)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Downloading for offline playback")
            .setContentText(progressText(downloadedBytes, totalBytes))
            .setContentIntent(openDownloadsIntent(jobId))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setProgress(100, progressPercent(downloadedBytes, totalBytes), totalBytes <= 0)
            .addAction(
                0,
                "Pause",
                actionIntent(DownloadActionReceiver.ActionPause, jobId, 1),
            )
            .addAction(
                0,
                "Cancel",
                actionIntent(DownloadActionReceiver.ActionCancel, jobId, 2),
            )
            .build()
        val notificationId = notificationId(jobId)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    fun updateProgress(jobId: String, downloadedBytes: Long, totalBytes: Long) {
        val notification = foreground(jobId, downloadedBytes, totalBytes).notification
        runCatching { NotificationManagerCompat.from(context).notify(notificationId(jobId), notification) }
    }

    fun complete(jobId: String) {
        notifyResult(jobId, "Download complete", "Ready to watch offline")
    }

    fun failed(jobId: String, failure: DownloadFailure) {
        notifyResult(jobId, "Download stopped", failure.message)
    }

    private fun notifyResult(jobId: String, title: String, message: String) {
        if (!notificationsAllowed()) return
        val notification = NotificationCompat.Builder(context, ResultChannel)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(openDownloadsIntent(jobId))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(notificationId(jobId), notification) }
    }

    private fun notificationsAllowed(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun openDownloadsIntent(jobId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(OpenDownloadsAction)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            notificationId(jobId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun actionIntent(action: String, jobId: String, offset: Int): PendingIntent {
        val intent = Intent(context, DownloadActionReceiver::class.java)
            .setAction(action)
            .putExtra(AndroidDownloadWorkContract.JobId, jobId)
        return PendingIntent.getBroadcast(
            context,
            notificationId(jobId) + offset,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun notificationId(jobId: String): Int = jobId.hashCode() and Int.MAX_VALUE

    private fun progressPercent(downloadedBytes: Long, totalBytes: Long): Int {
        if (totalBytes <= 0) return 0
        return ((downloadedBytes.coerceIn(0, totalBytes) * 100) / totalBytes).toInt()
    }

    private fun progressText(downloadedBytes: Long, totalBytes: Long): String =
        if (totalBytes > 0) "${progressPercent(downloadedBytes, totalBytes)}%" else "Downloading"

    companion object {
        const val OpenDownloadsAction = "moe.ditto.halo.action.OPEN_DOWNLOADS"
        private const val ProgressChannel = "halo-download-progress"
        private const val ResultChannel = "halo-download-results"
    }
}

/** Explicit, non-exported notification controls. Extras contain only a job ID. */
class DownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val jobId = intent.getStringExtra(AndroidDownloadWorkContract.JobId)
            ?.takeIf(::isValidJobId)
            ?: return
        val application = context.applicationContext as? HaloApplication ?: return
        val pending = goAsync()
        ReceiverScope.launch {
            try {
                when (intent.action) {
                    ActionPause -> application.downloadRuntime.pauseJob(jobId)
                    ActionCancel -> application.downloadRuntime.cancelJob(jobId)
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        internal const val ActionPause = "moe.ditto.halo.action.PAUSE_DOWNLOAD"
        internal const val ActionCancel = "moe.ditto.halo.action.CANCEL_DOWNLOAD"
        private val ReceiverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        private fun isValidJobId(value: String): Boolean =
            runCatching { UUID.fromString(value).toString().equals(value, ignoreCase = true) }.getOrDefault(false)
    }
}

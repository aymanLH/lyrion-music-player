package com.dn0ne.player.app.data.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.dn0ne.player.MainActivity
import com.dn0ne.player.NotificationActionReceiver
import com.dn0ne.player.R
import com.dn0ne.player.core.data.Settings
import java.util.concurrent.ConcurrentHashMap

/**
 * Download notifications.
 *
 * Deliberately plain `NotificationCompat` (no `MediaStyle`, no media session): the
 * playback service owns its own notification channel and ID, and downloads must not
 * interfere with it.
 *
 * Per-task notifications are suppressed when [Settings.notificationsEnabled] is off. The
 * foreground-service notification is always built because Android requires one for the
 * lifetime of a foreground service.
 */
class DownloadNotifications(
    context: Context,
    private val settings: Settings,
) {
    private val appContext = context.applicationContext
    private val lastProgressUpdate = ConcurrentHashMap<String, Long>()

    init {
        createChannels()
    }

    fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return

        val progressChannel = NotificationChannel(
            PROGRESS_CHANNEL_ID,
            appContext.getString(R.string.download_channel_progress),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = appContext.getString(R.string.download_channel_progress_explain)
            setShowBadge(false)
        }

        val statusChannel = NotificationChannel(
            STATUS_CHANNEL_ID,
            appContext.getString(R.string.download_channel_status),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = appContext.getString(R.string.download_channel_status_explain)
        }

        manager.createNotificationChannels(listOf(progressChannel, statusChannel))
    }

    fun notificationId(taskId: String): Int =
        BASE_NOTIFICATION_ID + 1 + ((taskId.hashCode() and 0x7FFFFFFF) % ID_RANGE)

    fun notifyProgress(
        taskId: String,
        title: String,
        progress: Float,
        format: String?,
        duration: Int,
    ) {
        if (!settings.notificationsEnabled.value) return
        if (!shouldPostProgress(taskId)) return

        val notificationId = notificationId(taskId)
        val indeterminate = progress <= 0f
        val percent = (progress.coerceIn(0f, 1f) * 100f).toInt()

        val notification = NotificationCompat.Builder(appContext, PROGRESS_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title.ifBlank { appContext.getString(R.string.download_notification_title) })
            .setContentText(buildList { format?.takeIf { it.isNotBlank() }?.let(::add); duration.takeIf { it > 0 }?.let { add(formatDuration(it)) }; if (progress > 0f) add("${(progress.coerceIn(0f, 1f) * 100).toInt()}%") }.joinToString(" · ").takeIf { it.isNotBlank() })
            .setContentIntent(openAppPendingIntent(notificationId))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(100, percent, indeterminate)
            .addAction(
                0,
                appContext.getString(R.string.notification_cancel),
                cancelPendingIntent(notificationId, taskId),
            )
            .build()

        post(notificationId, notification)
    }

    fun notifyFinished(
        taskId: String,
        title: String,
        mediaStoreUri: String?,
        format: String?,
        duration: Int,
    ) {
        if (!settings.notificationsEnabled.value) return

        val notificationId = notificationId(taskId)
        lastProgressUpdate.remove(taskId)

        val builder = NotificationCompat.Builder(appContext, STATUS_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title.ifBlank { appContext.getString(R.string.download_notification_title) })
            .setContentText((listOfNotNull(appContext.getString(R.string.download_notification_downloaded), format?.takeIf { it.isNotBlank() }, duration.takeIf { it > 0 }?.let(::formatDuration)).joinToString(" · ")))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        val openAudio = openAudioPendingIntent(notificationId, mediaStoreUri)
        builder.setContentIntent(openAudio ?: openAppPendingIntent(notificationId))

        post(notificationId, builder.build())
    }

    fun notifyError(
        taskId: String,
        title: String,
        message: String,
        report: String,
    ) {
        if (!settings.notificationsEnabled.value) return

        val notificationId = notificationId(taskId)
        lastProgressUpdate.remove(taskId)

        val notification = NotificationCompat.Builder(appContext, STATUS_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title.ifBlank { appContext.getString(R.string.download_notification_title) })
            .setContentText("${appContext.getString(R.string.download_notification_failed)} · $message")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openAppPendingIntent(notificationId))
            .addAction(
                0,
                appContext.getString(R.string.notification_copy_report),
                copyReportPendingIntent(notificationId, report),
            )
            .build()

        post(notificationId, notification)
    }

    fun cancel(taskId: String) {
        lastProgressUpdate.remove(taskId)
        manager().cancel(notificationId(taskId))
    }

    fun buildServiceNotification(
        runningCount: Int,
        totalCount: Int = runningCount,
        progress: Int? = null,
        title: String? = null,
    ): Notification = NotificationCompat.Builder(appContext, PROGRESS_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle(
            appContext.getString(R.string.download_notification_service_title, runningCount, totalCount)
        )
        .setContentText(title?.takeIf { !it.contains("http", true) && !it.contains('/') && !it.contains('\\') })
        .setContentIntent(openAppPendingIntent(SERVICE_NOTIFICATION_ID))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setProgress(100, progress ?: 0, progress == null)
        .build()

    /** Updates the foreground-service notification after it has been started. */
    fun updateServiceNotification(
        runningCount: Int,
        totalCount: Int = runningCount,
        progress: Int? = null,
        title: String? = null,
    ) {
        post(
            SERVICE_NOTIFICATION_ID,
            buildServiceNotification(runningCount = runningCount, totalCount = totalCount, progress = progress, title = title),
        )
    }

    private fun formatDuration(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)

    private fun shouldPostProgress(taskId: String): Boolean {
        val now = System.currentTimeMillis()
        val last = lastProgressUpdate[taskId] ?: 0L
        if (now - last < PROGRESS_THROTTLE_MILLIS) return false
        lastProgressUpdate[taskId] = now
        return true
    }

    private fun post(notificationId: Int, notification: Notification) {
        if (!canPostNotifications()) return
        manager().notify(notificationId, notification)
    }

    private fun manager(): NotificationManagerCompat =
        NotificationManagerCompat.from(appContext)

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

    private fun openAppPendingIntent(notificationId: Int): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return PendingIntent.getActivity(
            appContext,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openAudioPendingIntent(
        notificationId: Int,
        mediaStoreUri: String?,
    ): PendingIntent? {
        val uri = mediaStoreUri?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return null
        // `file://` URIs cannot be handed to another app on modern Android.
        if (uri.scheme != "content") return null

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "audio/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return PendingIntent.getActivity(
            appContext,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun cancelPendingIntent(notificationId: Int, taskId: String): PendingIntent {
        val intent = Intent(appContext, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_CANCEL
            setPackage(appContext.packageName)
            putExtra(NotificationActionReceiver.EXTRA_TASK_ID, taskId)
        }
        return PendingIntent.getBroadcast(
            appContext,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun copyReportPendingIntent(notificationId: Int, report: String): PendingIntent {
        val intent = Intent(appContext, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_COPY_REPORT
            setPackage(appContext.packageName)
            putExtra(NotificationActionReceiver.EXTRA_REPORT, report)
        }
        return PendingIntent.getBroadcast(
            appContext,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val SERVICE_NOTIFICATION_ID = 2000
        const val BASE_NOTIFICATION_ID = 2000
        private const val ID_RANGE = 100_000
        private const val PROGRESS_THROTTLE_MILLIS = 400L

        const val PROGRESS_CHANNEL_ID = "downloads_progress"
        const val STATUS_CHANNEL_ID = "downloads_status"
    }
}

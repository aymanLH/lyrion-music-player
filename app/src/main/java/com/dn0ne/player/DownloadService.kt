package com.dn0ne.player

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.dn0ne.player.app.data.notification.DownloadNotifications
import com.dn0ne.player.app.data.repository.DownloadRepository
import com.dn0ne.player.app.domain.download.DownloadState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.android.ext.android.get

/**
 * Foreground service that keeps downloads alive while the queue has running work.
 *
 * It is separate from [PlaybackService] and has its own notification channel and ID. It is
 * started from the queue right after a download action and stops itself as soon as no task
 * is fetching info or downloading.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var repository: DownloadRepository
    private lateinit var notifications: DownloadNotifications
    private var isObserving = false

    override fun onCreate() {
        super.onCreate()
        notifications = get()
        notifications.createChannels()
        // Post the mandatory FGS notification in onCreate, before resolving the
        // repository or registering collectors. Android's FGS start deadline covers
        // all of onCreate as well as onStartCommand.
        startForeground(
            DownloadNotifications.SERVICE_NOTIFICATION_ID,
            notifications.buildServiceNotification(runningCount = 0),
        )
        repository = get()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        notifications.updateServiceNotification(runningCount = activeCount())

        if (!isObserving) {
            isObserving = true
            scope.launch {
                repository.tasks
                    .map { taskMap ->
                        val active = taskMap.values.filter {
                            it.downloadState is DownloadState.Running ||
                                    it.downloadState is DownloadState.FetchingInfo
                        }
                        ServiceState(
                            activeCount = active.size,
                            totalCount = taskMap.values.count { it.downloadState !is DownloadState.Completed && it.downloadState !is DownloadState.Canceled && it.downloadState !is DownloadState.Error },
                            progress = active.mapNotNull { (it.downloadState as? DownloadState.Running)?.progress?.takeIf { p -> p > 0f } }.takeIf { it.size == active.size && it.isNotEmpty() }?.average()?.times(100)?.toInt(),
                            title = active.firstOrNull {
                                it.downloadState is DownloadState.Running
                            }?.viewState?.title,
                        )
                    }
                    .distinctUntilChanged()
                    .collect { state ->
                        if (state.activeCount <= 0) {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        } else {
                            notifications.updateServiceNotification(
                                runningCount = state.activeCount,
                                totalCount = state.totalCount,
                                progress = state.progress,
                                title = state.title,
                            )
                        }
                    }
            }
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun activeCount(): Int = repository.countRunning()

    private data class ServiceState(
        val activeCount: Int,
        val totalCount: Int,
        val progress: Int?,
        val title: String?,
    )

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}

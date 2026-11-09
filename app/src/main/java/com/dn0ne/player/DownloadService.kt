package com.dn0ne.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.dn0ne.player.app.data.notification.DownloadNotifications
import com.dn0ne.player.app.data.repository.DownloadRepository
import com.dn0ne.player.app.domain.download.DownloadState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.android.ext.android.get

/** Keeps active download work alive and exposes it as an Android data-sync foreground service. */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var repository: DownloadRepository
    private lateinit var notifications: DownloadNotifications
    private var isObserving = false
    private var foregroundStarted = false
    private var startPending = false
    private var lastStartId = 0
    private var idleStopJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        logLifecycle("onCreate")
        ensureForegroundChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val entryWallMs = System.currentTimeMillis()
        val entryElapsedMs = SystemClock.elapsedRealtime()
        val action = intent?.action ?: "<null>"

        // Promote synchronously for every start request, before touching Koin, the queue,
        // notification settings, or any task artwork.
        try {
            ServiceCompat.startForeground(
                this,
                DownloadNotifications.SERVICE_NOTIFICATION_ID,
                buildMinimalForegroundNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                } else {
                    0
                },
            )
            foregroundStarted = true
        } catch (exception: Exception) {
            Log.e(
                TAG,
                "wallMs=${System.currentTimeMillis()} elapsedMs=${SystemClock.elapsedRealtime()} " +
                        "startForeground failed startId=$startId action=$action " +
                        "error=${exception.message ?: exception::class.java.simpleName}",
                exception,
            )
            // Cancel this failed service start so Android does not later report a missing
            // foreground promotion. The repository's independent queue keeps running.
            val stopped = stopSelfResult(startId)
            logLifecycle("stopSelfResult after failed promotion startId=$startId stopped=$stopped")
            return START_NOT_STICKY
        }

        lastStartId = startId
        startPending = true
        idleStopJob?.cancel()
        idleStopJob = null
        logLifecycle(
            "onStartCommand startId=$startId action=$action flags=$flags " +
                    "enteredWallMs=$entryWallMs enteredElapsedMs=$entryElapsedMs startForegroundReturned",
        )
        logLifecycle("startForeground returned startId=$startId action=$action")
        startPending = false

        try {
            if (!::notifications.isInitialized) notifications = get()
            if (!::repository.isInitialized) repository = get()
        } catch (exception: Exception) {
            Log.e(
                TAG,
                "Dependency resolution failed after foreground promotion startId=$startId: " +
                        "${exception.message ?: exception::class.java.simpleName}",
                exception,
            )
            return START_NOT_STICKY
        }

        notifications.updateServiceNotification(runningCount = repository.countRunning())

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
                            unfinishedCount = taskMap.values.count {
                                it.downloadState !is DownloadState.Completed &&
                                        it.downloadState !is DownloadState.Canceled &&
                                        it.downloadState !is DownloadState.Error
                            },
                            totalCount = taskMap.values.count {
                                it.downloadState !is DownloadState.Completed &&
                                        it.downloadState !is DownloadState.Canceled &&
                                        it.downloadState !is DownloadState.Error
                            },
                            progress = active.mapNotNull {
                                (it.downloadState as? DownloadState.Running)?.progress
                                    ?.takeIf { progress -> progress > 0f }
                            }.takeIf { it.size == active.size && it.isNotEmpty() }
                                ?.average()?.times(100)?.toInt(),
                            title = active.firstOrNull {
                                it.downloadState is DownloadState.Running
                            }?.viewState?.title,
                        )
                    }
                    .distinctUntilChanged()
                    .collect { state ->
                        if (state.unfinishedCount <= 0) {
                            scheduleIdleStop()
                        } else {
                            cancelIdleStop("unfinishedCount=${state.unfinishedCount}")
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

        logLifecycle("onStartCommand setup complete startId=$startId")
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        logLifecycle("onDestroy enter lastStartId=$lastStartId foregroundStarted=$foregroundStarted")
        idleStopJob?.cancel()
        scope.cancel()
        super.onDestroy()
        logLifecycle("onDestroy returned lastStartId=$lastStartId")
    }

    private fun scheduleIdleStop() {
        idleStopJob?.cancel()
        val requestedStartId = lastStartId
        logLifecycle("idle stop scheduled startId=$requestedStartId debounceMs=$IDLE_STOP_DEBOUNCE_MS")
        idleStopJob = scope.launch {
            delay(IDLE_STOP_DEBOUNCE_MS)
            val stoppedAtomically = if (::repository.isInitialized) {
                repository.runIfQueueIdle {
                    val latestStartId = lastStartId
                    if (startPending || latestStartId != requestedStartId) {
                        logLifecycle(
                            "idle stop canceled scheduledStartId=$requestedStartId " +
                                    "latestStartId=$latestStartId startPending=$startPending",
                        )
                    } else {
                        if (foregroundStarted) {
                            logLifecycle("stopForeground requested startId=$latestStartId queueIdle=true")
                            ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                            foregroundStarted = false
                            logLifecycle("stopForeground returned startId=$latestStartId")
                        }

                        // runIfQueueIdle holds the queue-state lock through this call, so a
                        // task cannot transition active between the idle check and stopSelfResult.
                        logLifecycle("stopSelfResult requested startId=$latestStartId queueIdle=true")
                        val stopped = stopSelfResult(latestStartId)
                        logLifecycle("stopSelfResult returned startId=$latestStartId stopped=$stopped")
                    }
                }
            } else {
                false
            }
            if (!stoppedAtomically) {
                logLifecycle(
                    "idle stop skipped scheduledStartId=$requestedStartId " +
                            "latestStartId=$lastStartId startPending=$startPending " +
                            "runningCount=${if (::repository.isInitialized) repository.countRunning() else "unknown"}",
                )
            }
        }
    }

    private fun cancelIdleStop(reason: String) {
        if (idleStopJob?.isActive == true) {
            logLifecycle("idle stop canceled reason=$reason")
            idleStopJob?.cancel()
        }
        idleStopJob = null
    }

    private fun buildMinimalForegroundNotification() =
        NotificationCompat.Builder(this, DownloadNotifications.PROGRESS_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.download_notification_service_title, 1, 1))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(0, 0, true)
            .build()

    private fun ensureForegroundChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(DownloadNotifications.PROGRESS_CHANNEL_ID) != null) return

        manager.createNotificationChannel(
            NotificationChannel(
                DownloadNotifications.PROGRESS_CHANNEL_ID,
                getString(R.string.download_channel_progress),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.download_channel_progress_explain)
                setShowBadge(false)
            },
        )
        logLifecycle("foreground notification channel ensured")
    }

    private fun logLifecycle(event: String) {
        Log.i(TAG, "wallMs=${System.currentTimeMillis()} elapsedMs=${SystemClock.elapsedRealtime()} $event")
    }

    private data class ServiceState(
        val activeCount: Int,
        val unfinishedCount: Int,
        val totalCount: Int,
        val progress: Int?,
        val title: String?,
    )

    companion object {
        private const val TAG = "YT_DEBUG"
        private const val IDLE_STOP_DEBOUNCE_MS = 1_000L

        fun start(context: Context): Boolean {
            val intent = Intent(context, DownloadService::class.java)
            if (Looper.myLooper() != Looper.getMainLooper()) {
                Log.e(
                    TAG,
                    "wallMs=${System.currentTimeMillis()} elapsedMs=${SystemClock.elapsedRealtime()} " +
                            "DownloadService.start refused off the UI thread; queue item must remain resumable",
                )
                return false
            }
            Log.i(
                TAG,
                "wallMs=${System.currentTimeMillis()} elapsedMs=${SystemClock.elapsedRealtime()} " +
                        "DownloadService.start requested sdk=${Build.VERSION.SDK_INT}",
            )
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                Log.i(
                    TAG,
                    "wallMs=${System.currentTimeMillis()} elapsedMs=${SystemClock.elapsedRealtime()} " +
                            "DownloadService.start returned",
                )
                return true
            } catch (exception: Exception) {
                Log.e(
                    TAG,
                    "wallMs=${System.currentTimeMillis()} elapsedMs=${SystemClock.elapsedRealtime()} " +
                            "DownloadService.start failed error=${exception.message ?: exception::class.java.simpleName}; " +
                            "download queue remains active",
                    exception,
                )
                return false
            }
        }
    }
}

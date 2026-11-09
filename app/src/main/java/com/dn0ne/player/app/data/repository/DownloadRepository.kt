package com.dn0ne.player.app.data.repository

import com.dn0ne.player.app.domain.download.DownloadPreferences
import com.dn0ne.player.app.domain.download.DownloadTask
import com.dn0ne.player.app.domain.download.DownloadTaskState
import com.dn0ne.player.app.domain.youtube.PlaylistResult
import com.dn0ne.player.app.domain.youtube.YoutubeSearchItem
import kotlinx.coroutines.flow.StateFlow

/**
 * V2 download queue, ported from Seal's `DownloaderV2`.
 *
 * The queue is the single source of truth for pending/running tasks. Finished history is
 * a separate concern (Room, phase 8).
 */
interface DownloadRepository {
    val tasks: StateFlow<Map<DownloadTask, DownloadTaskState>>

    fun enqueue(task: DownloadTask)

    fun enqueue(task: DownloadTask, state: DownloadTaskState)

    fun enqueue(taskWithState: DownloadTaskFactory.TaskWithState)

    fun enqueueAll(taskWithStates: List<DownloadTaskFactory.TaskWithState>)

    fun enqueueSearchItem(item: YoutubeSearchItem)

    /** Enqueue only when a foreground service was successfully requested by the UI. */
    fun enqueueSearchItem(item: YoutubeSearchItem, serviceStartSucceeded: Boolean)

    fun enqueueUrl(url: String)

    fun enqueueUrl(url: String, serviceStartSucceeded: Boolean)

    fun enqueuePlaylist(
        playlistUrl: String,
        selectedIndices: List<Int>,
        playlistResult: PlaylistResult,
    ): List<DownloadTask>

    fun enqueuePlaylist(
        playlistUrl: String,
        selectedIndices: List<Int>,
        playlistResult: PlaylistResult,
        serviceStartSucceeded: Boolean,
    ): List<DownloadTask>

    fun cancel(task: DownloadTask): Boolean

    fun cancel(taskId: String): Boolean

    fun restart(task: DownloadTask)

    fun restart(task: DownloadTask, serviceStartSucceeded: Boolean)

    fun remove(task: DownloadTask): Boolean

    fun countRunning(): Int

    fun hasRunningTasks(): Boolean

    fun hasUnfinishedTasks(): Boolean

    /** Runs [stopAction] atomically with the queue's no-unfinished-work check. */
    fun runIfQueueIdle(stopAction: () -> Unit): Boolean

    /** Resume only eligible interrupted tasks after the foreground UI has requested the service. */
    suspend fun resumeInterruptedTasks(startServiceFromUi: () -> Boolean): Int

    fun currentPreferences(): DownloadPreferences
}

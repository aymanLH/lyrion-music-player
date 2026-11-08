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

    fun enqueueUrl(url: String)

    fun enqueuePlaylist(
        playlistUrl: String,
        selectedIndices: List<Int>,
        playlistResult: PlaylistResult,
    ): List<DownloadTask>

    fun cancel(task: DownloadTask): Boolean

    fun cancel(taskId: String): Boolean

    fun restart(task: DownloadTask)

    fun remove(task: DownloadTask): Boolean

    fun countRunning(): Int

    fun hasRunningTasks(): Boolean

    fun currentPreferences(): DownloadPreferences
}

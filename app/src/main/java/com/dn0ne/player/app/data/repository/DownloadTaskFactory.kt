package com.dn0ne.player.app.data.repository

import com.dn0ne.player.app.domain.download.DownloadPreferences
import com.dn0ne.player.app.domain.download.DownloadState
import com.dn0ne.player.app.domain.download.DownloadTask
import com.dn0ne.player.app.domain.download.DownloadTaskState
import com.dn0ne.player.app.domain.download.DownloadType
import com.dn0ne.player.app.domain.download.DownloadViewState
import com.dn0ne.player.app.domain.youtube.PlaylistResult
import com.dn0ne.player.app.domain.youtube.YoutubeSearchItem
import kotlin.math.roundToInt

/** Port of Seal's `TaskFactory`, limited to URL and playlist tasks. */
object DownloadTaskFactory {
    fun createForSearchItem(
        item: YoutubeSearchItem,
        preferences: DownloadPreferences,
    ): TaskWithState {
        val task = DownloadTask(
            url = item.url,
            type = DownloadType.Url,
            preferences = preferences,
        )

        return TaskWithState(
            task = task,
            state = DownloadTaskState(
                downloadState = DownloadState.Idle,
                viewState = DownloadViewState(
                    videoId = item.id,
                    url = item.url,
                    sourceUrl = item.url,
                    title = item.title,
                    uploader = item.uploader.orEmpty(),
                    duration = item.durationSeconds ?: 0,
                    thumbnailUrl = item.thumbnailUrl,
                    format = preferences.audioFormat.takeIf { it != com.dn0ne.player.app.domain.download.DownloadAudioFormat.ORIGINAL }?.name,
                )
            )
        )
    }

    fun createForUrl(
        url: String,
        preferences: DownloadPreferences,
    ): TaskWithState {
        val task = DownloadTask(
            url = url,
            type = DownloadType.Url,
            preferences = preferences,
        )

        return TaskWithState(
            task = task,
            state = DownloadTaskState(
                downloadState = DownloadState.Idle,
                viewState = DownloadViewState(
                    url = url,
                    sourceUrl = url,
                    title = "",
                )
            )
        )
    }

    /** One [DownloadTask] per selected 1-based playlist index. */
    fun createWithPlaylistResult(
        playlistUrl: String,
        indexList: List<Int>,
        playlistResult: PlaylistResult,
        preferences: DownloadPreferences,
    ): List<TaskWithState> {
        val entries = playlistResult.entries.orEmpty()

        return indexList.mapNotNull { index ->
            val entry = entries.getOrNull(index - 1) ?: return@mapNotNull null
            val videoId = entry.id.orEmpty()
            val pageUrl = entry.url
                ?.takeIf { it.startsWith("http") }
                ?: "https://www.youtube.com/watch?v=$videoId".takeIf { videoId.isNotBlank() }
                ?: entry.url.orEmpty()

            val viewState = DownloadViewState(
                videoId = videoId,
                url = pageUrl,
                sourceUrl = pageUrl,
                title = entry.title ?: "${playlistResult.title} - $index",
                uploader = entry.uploader
                    ?: entry.channel
                    ?: playlistResult.channel
                    .orEmpty(),
                duration = entry.duration?.roundToInt() ?: 0,
                thumbnailUrl = entry.thumbnails?.lastOrNull()?.url
                    ?: "https://i.ytimg.com/vi/$videoId/mqdefault.jpg".takeIf {
                        videoId.isNotBlank()
                    },
            )

            TaskWithState(
                task = DownloadTask(
                    url = playlistUrl,
                    type = DownloadType.Playlist(index),
                    preferences = preferences,
                ),
                state = DownloadTaskState(
                    downloadState = DownloadState.Idle,
                    viewState = viewState,
                )
            )
        }
    }

    data class TaskWithState(
        val task: DownloadTask,
        val state: DownloadTaskState,
    )
}

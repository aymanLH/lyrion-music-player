package com.dn0ne.player.app.domain.download

import com.dn0ne.player.app.domain.youtube.VideoInfo
import kotlinx.coroutines.Job
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.math.roundToInt

@Serializable
enum class DownloadAudioFormat {
    ORIGINAL,
    OPUS,
    M4A,
    MP3,
}

@Serializable
enum class DownloadAudioQuality {
    HIGH,
    MEDIUM,
    LOW,
}

/** Small, serializable subset of Seal's 50-field `DownloadPreferences`. */
@Serializable
data class DownloadPreferences(
    val audioFormat: DownloadAudioFormat = DownloadAudioFormat.OPUS,
    val audioQuality: DownloadAudioQuality = DownloadAudioQuality.HIGH,
    val embedMetadata: Boolean = true,
    val embedThumbnail: Boolean = true,
    val outputTemplate: String = "%(title).200B.%(ext)s",
)

@Serializable
sealed interface DownloadType {
    @Serializable
    data object Url : DownloadType

    @Serializable
    data class Playlist(val index: Int = 0) : DownloadType
}

private val DownloadType.idSuffix: String
    get() = when (this) {
        is DownloadType.Playlist -> index.toString()
        DownloadType.Url -> ""
    }

private fun makeTaskId(
    url: String,
    type: DownloadType,
    preferences: DownloadPreferences,
): String = "${url}_${type.idSuffix}_${preferences.hashCode()}"

@Serializable
data class DownloadTask(
    val url: String,
    val type: DownloadType = DownloadType.Url,
    val preferences: DownloadPreferences,
    val id: String = makeTaskId(url, type, preferences),
    val timeCreated: Long = System.currentTimeMillis(),
) : Comparable<DownloadTask> {
    override fun compareTo(other: DownloadTask): Int =
        timeCreated.compareTo(other.timeCreated)
}

@Serializable
data class DownloadTaskState(
    val downloadState: DownloadState,
    val videoInfo: VideoInfo? = null,
    val viewState: DownloadViewState = DownloadViewState(),
)

@Serializable
sealed interface RestartableAction {
    @Serializable
    data object FetchInfo : RestartableAction

    @Serializable
    data object Download : RestartableAction
}

@Serializable
sealed interface DownloadState : Comparable<DownloadState> {
    interface Cancelable {
        val job: Job
        val taskId: String
        val action: RestartableAction
    }

    interface Restartable {
        val action: RestartableAction
    }

    @Serializable
    data object Idle : DownloadState

    @Serializable
    data class FetchingInfo(
        @Transient override val job: Job = Job(),
        override val taskId: String,
    ) : DownloadState, Cancelable {
        override val action: RestartableAction = RestartableAction.FetchInfo
    }

    @Serializable
    data object ReadyWithInfo : DownloadState

    @Serializable
    data class Running(
        @Transient override val job: Job = Job(),
        override val taskId: String,
        val progress: Float = PROGRESS_INDETERMINATE,
        val progressText: String = "",
    ) : DownloadState, Cancelable {
        override val action: RestartableAction = RestartableAction.Download
    }

    @Serializable
    data class Canceled(
        override val action: RestartableAction,
        val progress: Float? = null,
    ) : DownloadState, Restartable

    @Serializable
    data class Error(
        val message: String,
        override val action: RestartableAction,
        val report: String = message,
    ) : DownloadState, Restartable

    @Serializable
    data class Completed(
        val filePath: String? = null,
        val fileSize: Long = 0L,
        val mediaStoreUri: String? = null,
    ) : DownloadState

    override fun compareTo(other: DownloadState): Int = ordinal - other.ordinal

    private val ordinal: Int
        get() = when (this) {
            is Running -> 0
            ReadyWithInfo -> 1
            is FetchingInfo -> 2
            Idle -> 3
            is Canceled -> 4
            is Error -> 5
            is Completed -> 6
        }

    companion object {
        const val PROGRESS_INDETERMINATE = -1f
    }
}

@Serializable
data class DownloadViewState(
    val videoId: String = "",
    val url: String = "",
    val sourceUrl: String = "",
    val title: String = "",
    val uploader: String = "",
    val duration: Int = 0,
    val thumbnailUrl: String? = null,
    val format: String? = null,
) {
    companion object {
        fun fromVideoInfo(info: VideoInfo, fallbackUrl: String = ""): DownloadViewState {
            val pageUrl = info.webpageUrl ?: info.originalUrl ?: fallbackUrl
            return DownloadViewState(
                videoId = info.id,
                url = pageUrl,
                sourceUrl = pageUrl,
                title = info.title,
                uploader = info.uploader
                    ?: info.channel
                    ?: info.uploaderId.orEmpty(),
                duration = info.duration?.roundToInt() ?: 0,
                thumbnailUrl = info.thumbnail,
            )
        }
    }
}

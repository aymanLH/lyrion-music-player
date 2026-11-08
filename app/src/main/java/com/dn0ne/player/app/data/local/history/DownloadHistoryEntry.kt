package com.dn0ne.player.app.data.local.history

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter

enum class DownloadHistoryStatus {
    COMPLETED,
    FAILED,
}

/**
 * One row per download attempt that reached a terminal state.
 *
 * `sourceUrl` is the individual item URL, so playlist entries get their own rows. A task is
 * deduplicated by `videoId` (falling back to `sourceUrl` when yt-dlp did not expose an id),
 * which lets a failed entry be upgraded instead of duplicated when the user retries.
 */
@Entity(
    tableName = "download_history",
    indices = [
        Index("timestamp"),
        Index("videoId"),
        Index("sourceUrl"),
    ],
)
data class DownloadHistoryEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val videoId: String,
    val title: String,
    val uploader: String,
    val thumbnailUrl: String?,
    val sourceUrl: String,
    /** `opus`, `m4a`, `mp3`, or an empty string when the format is unknown. */
    val format: String,
    val fileSize: Long,
    val mediaStoreUri: String?,
    val status: DownloadHistoryStatus,
    val errorMessage: String?,
    val timestamp: Long,
)

class DownloadHistoryConverters {
    @TypeConverter
    fun fromStatus(status: DownloadHistoryStatus): String = status.name

    @TypeConverter
    fun toStatus(value: String): DownloadHistoryStatus =
        runCatching { DownloadHistoryStatus.valueOf(value) }
            .getOrDefault(DownloadHistoryStatus.FAILED)
}

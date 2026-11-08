package com.dn0ne.player.app.data.repository

import com.dn0ne.player.app.data.local.history.DownloadHistoryEntry
import kotlinx.coroutines.flow.Flow

/**
 * Download history. Unlike the queue this only holds terminal states, so it survives
 * app restarts and is never touched for restored/canceled tasks.
 */
interface DownloadHistoryRepository {
    /** Newest first. */
    val entries: Flow<List<DownloadHistoryEntry>>

    /** Case-insensitive-ish match on title or uploader, newest first. */
    fun search(query: String): Flow<List<DownloadHistoryEntry>>

    /** Inserts or updates the entry for the same task. */
    suspend fun recordCompleted(entry: DownloadHistoryEntry)

    /** Inserts or updates the entry for the same task. */
    suspend fun recordFailed(entry: DownloadHistoryEntry)

    suspend fun delete(entry: DownloadHistoryEntry)

    suspend fun deleteAll()

    suspend fun deleteOlderThan(days: Int)

    suspend fun checkFileAvailability(entry: DownloadHistoryEntry): HistoryFileAvailability
}

enum class HistoryFileAvailability {
    AVAILABLE,
    DELETED,
    UNKNOWN,
}

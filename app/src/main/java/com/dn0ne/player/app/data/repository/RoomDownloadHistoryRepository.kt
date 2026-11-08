package com.dn0ne.player.app.data.repository

import android.content.Context
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.dn0ne.player.app.data.local.history.DownloadHistoryDao
import com.dn0ne.player.app.data.local.history.DownloadHistoryEntry
import com.dn0ne.player.app.data.local.history.DownloadHistoryStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

class RoomDownloadHistoryRepository(
    context: Context,
    private val dao: DownloadHistoryDao,
) : DownloadHistoryRepository {
    private val appContext = context.applicationContext

    override val entries: Flow<List<DownloadHistoryEntry>> = dao.observeAll()

    override fun search(query: String): Flow<List<DownloadHistoryEntry>> =
        dao.search(query.trim())

    override suspend fun recordCompleted(entry: DownloadHistoryEntry) {
        upsert(
            entry.copy(
                status = DownloadHistoryStatus.COMPLETED,
                errorMessage = null,
            )
        )
    }

    override suspend fun recordFailed(entry: DownloadHistoryEntry) {
        upsert(entry.copy(status = DownloadHistoryStatus.FAILED))
    }

    override suspend fun delete(entry: DownloadHistoryEntry) {
        dao.deleteById(entry.id)
    }

    override suspend fun deleteAll() {
        dao.deleteAll()
    }

    override suspend fun deleteOlderThan(days: Int) {
        val cutoff = System.currentTimeMillis() - days.coerceAtLeast(0) * DAY_MILLIS
        dao.deleteOlderThan(cutoff)
    }

    override suspend fun checkFileAvailability(entry: DownloadHistoryEntry): HistoryFileAvailability {
        val uriString = entry.mediaStoreUri?.takeIf { it.isNotBlank() }
            ?: return HistoryFileAvailability.DELETED
        val uri = runCatching { Uri.parse(uriString) }.getOrNull()
            ?: return HistoryFileAvailability.UNKNOWN

        return withContext(Dispatchers.IO) {
            val resolver = appContext.contentResolver
            val result = runCatching {
                if (uri.scheme == "file") {
                    val path = uri.path ?: return@runCatching HistoryFileAvailability.UNKNOWN
                    val cursor = resolver.query(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        arrayOf(MediaStore.Audio.Media._ID),
                        "${MediaStore.Audio.Media.DATA} = ?",
                        arrayOf(path),
                        null,
                    )
                    if (cursor == null) HistoryFileAvailability.UNKNOWN
                    else cursor.use {
                        when {
                            it.moveToFirst() -> HistoryFileAvailability.AVAILABLE
                            File(path).exists() -> HistoryFileAvailability.AVAILABLE
                            else -> HistoryFileAvailability.DELETED
                        }
                    }
                } else {
                    val cursor = resolver.query(uri, arrayOf(MediaStore.Audio.Media._ID), null, null, null)
                        ?: return@runCatching HistoryFileAvailability.UNKNOWN
                    val foundAtSavedUri = cursor.use { it.moveToFirst() }
                    if (foundAtSavedUri) {
                        HistoryFileAvailability.AVAILABLE
                    } else {
                        val mediaId = runCatching { ContentUris.parseId(uri) }.getOrNull()
                            ?: return@runCatching HistoryFileAvailability.DELETED
                        val canonicalUri = ContentUris.withAppendedId(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                            mediaId,
                        )
                        val canonicalCursor = resolver.query(
                            canonicalUri,
                            arrayOf(MediaStore.Audio.Media._ID),
                            null,
                            null,
                            null,
                        ) ?: return@runCatching HistoryFileAvailability.UNKNOWN
                        canonicalCursor.use {
                            if (it.moveToFirst()) HistoryFileAvailability.AVAILABLE
                            else HistoryFileAvailability.DELETED
                        }
                    }
                }
            }.getOrElse { HistoryFileAvailability.UNKNOWN }
            Log.d(TAG, "History URI check uri=$uriString availability=$result")
            result
        }
    }

    /**
     * Writes the entry, reusing the row of the same task. A failed row is therefore
     * upgraded to COMPLETED when the user restarts the task and it succeeds, and retries
     * update the error message instead of adding duplicates.
     */
    private suspend fun upsert(entry: DownloadHistoryEntry) {
        val existing = findExisting(entry)
        if (existing == null) {
            dao.insert(entry)
        } else {
            dao.update(entry.copy(id = existing.id))
        }

        if (dao.count() > MAX_ENTRIES) {
            dao.trimTo(MAX_ENTRIES)
        }
    }

    private suspend fun findExisting(entry: DownloadHistoryEntry): DownloadHistoryEntry? {
        val videoId = entry.videoId.takeIf { it.isNotBlank() }
        if (videoId != null) {
            dao.findLatestByVideoId(videoId)?.let { return it }
        }
        return dao.findLatestBySourceUrl(entry.sourceUrl)
    }

    private companion object {
        const val TAG = "YT_DEBUG"
        const val MAX_ENTRIES = 1000
        const val DAY_MILLIS = 24L * 60L * 60L * 1000L
    }
}

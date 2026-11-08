package com.dn0ne.player.app.data.local.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadHistoryDao {
    @Insert
    suspend fun insert(entry: DownloadHistoryEntry): Long

    @Update
    suspend fun update(entry: DownloadHistoryEntry)

    @Query("SELECT * FROM download_history ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<DownloadHistoryEntry>>

    @Query(
        "SELECT * FROM download_history " +
                "WHERE title LIKE '%' || :query || '%' " +
                "OR uploader LIKE '%' || :query || '%' " +
                "ORDER BY timestamp DESC"
    )
    fun search(query: String): Flow<List<DownloadHistoryEntry>>

    @Query("DELETE FROM download_history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM download_history")
    suspend fun deleteAll()

    @Query("DELETE FROM download_history WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    @Query(
        "SELECT * FROM download_history " +
                "WHERE videoId = :videoId AND videoId != '' " +
                "ORDER BY timestamp DESC LIMIT 1"
    )
    suspend fun findLatestByVideoId(videoId: String): DownloadHistoryEntry?

    @Query("SELECT * FROM download_history WHERE sourceUrl = :sourceUrl ORDER BY timestamp DESC LIMIT 1")
    suspend fun findLatestBySourceUrl(sourceUrl: String): DownloadHistoryEntry?

    @Query("SELECT COUNT(*) FROM download_history")
    suspend fun count(): Int

    /** Keeps the newest [limit] rows. */
    @Query(
        "DELETE FROM download_history WHERE id NOT IN " +
                "(SELECT id FROM download_history ORDER BY timestamp DESC LIMIT :limit)"
    )
    suspend fun trimTo(limit: Int)
}

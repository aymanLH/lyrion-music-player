package com.dn0ne.player.app.data.local.history

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [DownloadHistoryEntry::class],
    version = 1,
    exportSchema = false,
)
@TypeConverters(DownloadHistoryConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun downloadHistoryDao(): DownloadHistoryDao

    companion object {
        const val NAME = "download-history.db"
    }
}

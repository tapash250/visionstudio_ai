package com.visionstudio.core.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [GenerationHistoryEntity::class, VideoJobEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class VisionStudioDatabase : RoomDatabase() {
    abstract fun generationHistoryDao(): GenerationHistoryDao
    abstract fun videoJobDao(): VideoJobDao

    companion object {
        const val NAME: String = "visionstudio.db"
    }
}

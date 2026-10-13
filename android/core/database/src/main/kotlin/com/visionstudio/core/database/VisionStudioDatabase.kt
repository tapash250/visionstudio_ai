package com.visionstudio.core.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [GenerationHistoryEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class VisionStudioDatabase : RoomDatabase() {
    abstract fun generationHistoryDao(): GenerationHistoryDao

    companion object {
        const val NAME: String = "visionstudio.db"
    }
}

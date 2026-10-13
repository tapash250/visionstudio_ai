package com.visionstudio.core.database.di

import android.content.Context
import androidx.room.Room
import com.visionstudio.core.database.ALL_MIGRATIONS
import com.visionstudio.core.database.GenerationHistoryDao
import com.visionstudio.core.database.VideoJobDao
import com.visionstudio.core.database.VisionStudioDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): VisionStudioDatabase =
        Room.databaseBuilder(context, VisionStudioDatabase::class.java, VisionStudioDatabase.NAME)
            // Room runs queries on its own IO executor; WAL keeps Vault reads fast during writes.
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(*ALL_MIGRATIONS)
            .build()

    @Provides
    fun provideGenerationHistoryDao(database: VisionStudioDatabase): GenerationHistoryDao =
        database.generationHistoryDao()

    @Provides
    fun provideVideoJobDao(database: VisionStudioDatabase): VideoJobDao = database.videoJobDao()
}

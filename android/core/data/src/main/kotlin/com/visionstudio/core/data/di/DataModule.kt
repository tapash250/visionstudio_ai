package com.visionstudio.core.data.di

import com.visionstudio.core.data.generation.DefaultGenerationRepository
import com.visionstudio.core.data.generation.GenerationRepository
import com.visionstudio.core.data.media.MediaPersister
import com.visionstudio.core.data.media.MediaSink
import com.visionstudio.core.data.video.Clock
import com.visionstudio.core.data.video.DefaultVideoJobRepository
import com.visionstudio.core.data.video.EngineSource
import com.visionstudio.core.data.video.FactoryEngineSource
import com.visionstudio.core.data.video.VideoJobRepository
import dagger.Binds
import dagger.Provides
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class DataModule {

    @Binds
    @Singleton
    abstract fun bindGenerationRepository(impl: DefaultGenerationRepository): GenerationRepository

    @Binds
    @Singleton
    abstract fun bindMediaSink(impl: MediaPersister): MediaSink

    @Binds
    @Singleton
    abstract fun bindVideoJobRepository(impl: DefaultVideoJobRepository): VideoJobRepository

    @Binds
    abstract fun bindEngineSource(impl: FactoryEngineSource): EngineSource

    companion object {
        @Provides
        @Singleton
        fun provideClock(): Clock = Clock { System.currentTimeMillis() }
    }
}

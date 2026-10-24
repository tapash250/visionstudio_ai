package com.visionstudio.feature.video.di

import com.visionstudio.feature.video.work.SystemVideoNotifier
import com.visionstudio.feature.video.work.VideoJobLauncher
import com.visionstudio.feature.video.work.VideoNotifier
import com.visionstudio.feature.video.work.WorkVideoJobLauncher
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class VideoModule {

    @Binds
    @Singleton
    abstract fun bindNotifier(impl: SystemVideoNotifier): VideoNotifier

    @Binds
    @Singleton
    abstract fun bindLauncher(impl: WorkVideoJobLauncher): VideoJobLauncher
}

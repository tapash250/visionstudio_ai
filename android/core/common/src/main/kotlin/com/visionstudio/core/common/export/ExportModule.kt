package com.visionstudio.core.common.export

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class ExportModule {

    @Binds
    @Singleton
    abstract fun bindMediaExporter(impl: SystemMediaExporter): MediaExporter
}

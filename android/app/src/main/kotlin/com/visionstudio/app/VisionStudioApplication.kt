package com.visionstudio.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Supplies WorkManager with Hilt's worker factory so `@HiltWorker` classes (the video renderer)
 * receive their injected dependencies. The default auto-initializer is removed in the manifest;
 * WorkManager initialises on first use with this configuration.
 */
@HiltAndroidApp
class VisionStudioApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()
}

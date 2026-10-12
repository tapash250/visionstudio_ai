package com.visionstudio.core.common.dispatchers

import javax.inject.Qualifier

/** Keystore, Room, file and network work. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/** CPU-bound work: mask rasterization, JSON-RPC (de)serialization, image decoding. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

/** UI events only. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MainDispatcher

/** Process-lifetime [kotlinx.coroutines.CoroutineScope] for work that must outlive a screen. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

package com.visionstudio.core.security.di

import com.visionstudio.core.security.EncryptedPreferencesFactory
import com.visionstudio.core.security.EncryptedSecurePreferencesRepository
import com.visionstudio.core.security.KeystoreEncryptedPreferencesFactory
import com.visionstudio.core.security.SecurePreferencesRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class SecurityModule {

    @Binds
    @Singleton
    abstract fun bindSecurePreferencesRepository(
        impl: EncryptedSecurePreferencesRepository,
    ): SecurePreferencesRepository

    @Binds
    @Singleton
    abstract fun bindEncryptedPreferencesFactory(
        impl: KeystoreEncryptedPreferencesFactory,
    ): EncryptedPreferencesFactory
}

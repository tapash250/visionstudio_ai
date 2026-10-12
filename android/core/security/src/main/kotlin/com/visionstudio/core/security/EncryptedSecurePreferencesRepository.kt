package com.visionstudio.core.security

import android.content.SharedPreferences
import com.visionstudio.core.common.dispatchers.IoDispatcher
import com.visionstudio.core.common.error.VisionStudioException
import com.visionstudio.core.common.result.Result
import com.visionstudio.core.common.result.getOrNull
import com.visionstudio.core.model.EngineConfig
import com.visionstudio.core.model.EngineSlot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * [SecurePreferencesRepository] backed by EncryptedSharedPreferences (AES-256-SIV keys,
 * AES-256-GCM values) under an AndroidKeyStore AES-256-GCM master key.
 *
 * Corruption recovery: if opening, reading or writing fails with a Keystore or crypto error
 * (see [isKeystoreCorruption]), the store is purged (file, keysets and master key), reopened
 * empty, a [KeystoreRecovery] is emitted, and the operation is retried once. If it fails a
 * second time the caller gets a [VisionStudioException.SecureStorageException].
 *
 * Every Keystore and disk call runs on [ioDispatcher] behind a [Mutex], so recovery can never
 * race a concurrent read.
 */
@Singleton
internal class EncryptedSecurePreferencesRepository @Inject constructor(
    private val factory: EncryptedPreferencesFactory,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : SecurePreferencesRepository {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val mutex = Mutex()

    /** Guarded by [mutex]. Null until first use and after each purge. */
    private var preferences: SharedPreferences? = null

    /** Bumped after every mutation so [observeEngineConfigs] re-reads. */
    private val revision = MutableStateFlow(0L)

    private val recoveries = MutableSharedFlow<KeystoreRecovery>(replay = 1)
    override val recoveryEvents: SharedFlow<KeystoreRecovery> = recoveries.asSharedFlow()

    override suspend fun getApiKey(slot: EngineSlot): Result<String?> =
        access { prefs -> prefs.getString(apiKeyKey(slot), null) }

    override suspend fun hasApiKey(slot: EngineSlot): Result<Boolean> =
        access { prefs -> !prefs.getString(apiKeyKey(slot), null).isNullOrBlank() }

    override suspend fun setApiKey(slot: EngineSlot, apiKey: String): Result<Unit> {
        val trimmed = apiKey.trim()
        if (trimmed.isEmpty()) {
            return Result.Error(VisionStudioException.ValidationException("apiKey", "API key must not be blank"))
        }
        return mutate { editor -> editor.putString(apiKeyKey(slot), trimmed) }
    }

    override suspend fun clearApiKey(slot: EngineSlot): Result<Unit> =
        mutate { editor -> editor.remove(apiKeyKey(slot)) }

    override suspend fun getEngineConfig(slot: EngineSlot): Result<EngineConfig> =
        access { prefs -> prefs.readConfig(slot) }

    override suspend fun saveEngineConfig(config: EngineConfig): Result<Unit> {
        val encoded = json.encodeToString(EngineConfig.serializer(), config)
        return mutate { editor -> editor.putString(configKey(config.slot), encoded) }
    }

    override fun observeEngineConfigs(): Flow<Map<EngineSlot, EngineConfig>> =
        revision
            .map {
                access { prefs -> EngineSlot.entries.associateWith { slot -> prefs.readConfig(slot) } }
                    .getOrNull()
                    ?: UNCONFIGURED_SLOTS
            }
            .distinctUntilChanged()

    override suspend fun clearAll(): Result<Unit> = mutate { editor -> editor.clear() }

    private suspend fun mutate(edit: (SharedPreferences.Editor) -> Unit): Result<Unit> {
        val result = access { prefs ->
            val editor = prefs.edit()
            edit(editor)
            // commit() rather than apply(): we are already off the main thread and need to know
            // whether the encrypted write actually reached disk.
            if (!editor.commit()) {
                throw VisionStudioException.SecureStorageException("Encrypted preferences write was rejected")
            }
        }
        if (result is Result.Success) revision.update { it + 1 }
        return result
    }

    private suspend fun <T> access(block: (SharedPreferences) -> T): Result<T> =
        withContext(ioDispatcher) {
            mutex.withLock {
                try {
                    Result.Success(runWithRecoveryLocked(block))
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (domain: VisionStudioException) {
                    Result.Error(domain)
                } catch (other: Exception) {
                    Result.Error(
                        VisionStudioException.SecureStorageException(
                            other.message ?: "Secure storage failure",
                            other,
                        ),
                    )
                }
            }
        }

    private fun <T> runWithRecoveryLocked(block: (SharedPreferences) -> T): T {
        try {
            return block(openLocked())
        } catch (first: Exception) {
            if (!first.isKeystoreCorruption()) throw first
            recoverLocked(first)
            try {
                return block(openLocked())
            } catch (second: Exception) {
                second.addSuppressed(first)
                throw VisionStudioException.SecureStorageException(
                    "Secure storage is unavailable even after Keystore reinitialisation",
                    second,
                )
            }
        }
    }

    private fun openLocked(): SharedPreferences =
        preferences ?: factory.create().also { preferences = it }

    private fun recoverLocked(cause: Exception) {
        preferences = null
        try {
            factory.purge()
        } catch (purgeFailure: Exception) {
            purgeFailure.addSuppressed(cause)
            throw VisionStudioException.SecureStorageException("Unable to purge corrupted secure storage", purgeFailure)
        }
        recoveries.tryEmit(
            KeystoreRecovery(
                reason = cause.message ?: "Keystore signature mismatch",
                exceptionType = cause.javaClass.name,
            ),
        )
        revision.update { it + 1 }
    }

    /** Malformed (but decryptable) JSON is treated as absent and dropped, not as corruption. */
    private fun SharedPreferences.readConfig(slot: EngineSlot): EngineConfig {
        val raw = getString(configKey(slot), null) ?: return EngineConfig.unconfigured(slot)
        return try {
            json.decodeFromString(EngineConfig.serializer(), raw).takeIf { it.slot == slot }
                ?: EngineConfig.unconfigured(slot)
        } catch (malformed: SerializationException) {
            edit().remove(configKey(slot)).commit()
            EngineConfig.unconfigured(slot)
        } catch (invalid: IllegalArgumentException) {
            edit().remove(configKey(slot)).commit()
            EngineConfig.unconfigured(slot)
        }
    }

    internal companion object {
        fun apiKeyKey(slot: EngineSlot): String = "slot.${slot.name}.api_key"
        fun configKey(slot: EngineSlot): String = "slot.${slot.name}.config"

        private val UNCONFIGURED_SLOTS: Map<EngineSlot, EngineConfig> =
            EngineSlot.entries.associateWith(EngineConfig::unconfigured)
    }
}

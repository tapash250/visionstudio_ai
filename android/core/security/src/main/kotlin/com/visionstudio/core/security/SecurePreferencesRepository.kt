package com.visionstudio.core.security

import com.visionstudio.core.common.result.Result
import com.visionstudio.core.model.EngineConfig
import com.visionstudio.core.model.EngineSlot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Hardware-backed store for engine credentials and slot configuration.
 *
 * If the Keystore becomes unusable the store purges itself, reinitialises empty and reports
 * this on [recoveryEvents], so the UI can ask the user to re-enter their keys.
 */
interface SecurePreferencesRepository {

    /** Emits a [KeystoreRecovery] each time the store had to be wiped. Replays the latest one. */
    val recoveryEvents: SharedFlow<KeystoreRecovery>

    suspend fun getApiKey(slot: EngineSlot): Result<String?>

    suspend fun hasApiKey(slot: EngineSlot): Result<Boolean>

    /** Saves a key after trimming it. Blank keys are rejected with a ValidationException. */
    suspend fun setApiKey(slot: EngineSlot, apiKey: String): Result<Unit>

    suspend fun clearApiKey(slot: EngineSlot): Result<Unit>

    /** Returns the saved config, or [EngineConfig.unconfigured] if none has been saved. */
    suspend fun getEngineConfig(slot: EngineSlot): Result<EngineConfig>

    suspend fun saveEngineConfig(config: EngineConfig): Result<Unit>

    /** Hot view of every slot's config. Re-emits after each write, clear or recovery. */
    fun observeEngineConfigs(): Flow<Map<EngineSlot, EngineConfig>>

    /** Removes every stored secret and config. */
    suspend fun clearAll(): Result<Unit>
}

/** Describes a self-healing purge of the secure store. */
data class KeystoreRecovery(
    val reason: String,
    val exceptionType: String,
)

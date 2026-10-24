package com.visionstudio.core.security

import android.os.Build
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import androidx.annotation.RequiresApi
import java.security.GeneralSecurityException
import java.security.KeyStoreException
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.BadPaddingException

/**
 * Decides whether a secure-storage failure means the stored keys must be wiped.
 *
 * Wiping destroys every saved API key, so this only answers true when the error proves the
 * Keystore master key or the encrypted payload can never be read again: a backup restored onto
 * another device, a key invalidated by a lock-screen change, or a key blob broken by an OS
 * update. A locked device, a busy Keystore daemon or an unknown error never triggers a wipe;
 * those surface to the caller and the keys survive for the next attempt.
 */
internal object KeystoreCorruption {

    /** What the caller should do about a secure-storage failure. */
    enum class Action {
        /** The keys can never be read again: purge the store and start empty. */
        WIPE_AND_RESET,

        /** The key is intact but locked: ask the user to unlock the device, then retry. */
        REQUIRE_USER_AUTH,

        /** Anything else: keep the keys and let the caller retry later. */
        TRANSIENT_RETRY,
    }

    fun classify(error: Throwable): Action {
        val chain = causeChain(error)
        return when {
            chain.any { it.requiresUserAuthentication() } -> Action.REQUIRE_USER_AUTH
            // Keystore itself says to retry: never wipe, whatever wraps it.
            chain.any { it.isTransientPlatformFailure() } -> Action.TRANSIENT_RETRY
            chain.any { it.provesCorruption() } -> Action.WIPE_AND_RESET
            else -> Action.TRANSIENT_RETRY
        }
    }

    /**
     * Returns true only when [error] proves the Keystore key or encrypted payload is permanently
     * unrecoverable and must be wiped.
     */
    fun isPermanentCorruption(error: Throwable): Boolean = classify(error) == Action.WIPE_AND_RESET

    private fun Throwable.requiresUserAuthentication(): Boolean = when {
        this is UserNotAuthenticatedException -> true
        isPlatformKeystoreException() ->
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && platformRequiresUserAuthentication()) ||
                messageContainsAny(AUTH_REQUIRED_MESSAGES)
        else -> false
    }

    private fun Throwable.isTransientPlatformFailure(): Boolean =
        isPlatformKeystoreException() &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            platformIsTransient()

    private fun Throwable.provesCorruption(): Boolean = when {
        this is KeyPermanentlyInvalidatedException -> true
        // Also covers AEADBadTagException: the ciphertext was not produced by this key.
        this is BadPaddingException -> true
        this is UnrecoverableKeyException -> true
        isTinkDecryptionExhausted() -> true
        isPlatformKeystoreException() ->
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && isCorruptedPlatformKey()) ||
                messageContainsAny(CORRUPTED_KEY_BLOB_MESSAGES)
        this is KeyStoreException -> messageContainsAny(CORRUPTED_KEYSTORE_MESSAGES)
        this is ProviderException -> messageContainsAny(CORRUPTED_KEY_BLOB_MESSAGES)
        else -> false
    }

    /**
     * Tink's AEAD and deterministic-AEAD wrappers try every key in the keyset and then throw this
     * exact exception, dropping the underlying `AEADBadTagException`. EncryptedSharedPreferences
     * reports it as `SecurityException("Could not decrypt value.decryption failed")` with this as
     * the cause. Value decryption uses the keyset already unwrapped in memory, so this is a
     * ciphertext/keyset mismatch and never a transient Keystore failure.
     * (Checked against tink-android 1.8.0, used by security-crypto 1.1.0-alpha06.)
     */
    private fun Throwable.isTinkDecryptionExhausted(): Boolean =
        javaClass == GeneralSecurityException::class.java && message == TINK_DECRYPTION_FAILED

    /**
     * `android.security.KeyStoreException` is not a `java.security.KeyStoreException` and is only
     * public from API 33, although the hidden class exists on every supported release. Matching
     * by name keeps the check valid below API 33, where only its message can be read.
     */
    private fun Throwable.isPlatformKeystoreException(): Boolean =
        javaClass.name == PLATFORM_KEYSTORE_EXCEPTION

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun Throwable.platformRequiresUserAuthentication(): Boolean =
        (this as android.security.KeyStoreException).requiresUserAuthentication()

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun Throwable.platformIsTransient(): Boolean =
        (this as android.security.KeyStoreException).isTransientFailure

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun Throwable.isCorruptedPlatformKey(): Boolean =
        (this as android.security.KeyStoreException).numericErrorCode ==
            android.security.KeyStoreException.ERROR_KEY_CORRUPTED

    private fun Throwable.messageContainsAny(fragments: List<String>): Boolean {
        val text = message?.lowercase() ?: return false
        return fragments.any { it in text }
    }

    private fun causeChain(error: Throwable): List<Throwable> =
        generateSequence(error) { current -> current.cause?.takeIf { it !== current } }
            .take(MAX_CAUSE_DEPTH)
            .toList()

    private const val MAX_CAUSE_DEPTH = 8
    private const val PLATFORM_KEYSTORE_EXCEPTION = "android.security.KeyStoreException"
    private const val TINK_DECRYPTION_FAILED = "decryption failed"

    /**
     * Keystore messages for KeyMint INVALID_KEY_BLOB (-33) and Keystore KEY_CORRUPTED, for example
     * "Invalid key blob (internal Keystore code: -33 …)". Code -62 is KEY_REQUIRES_UPGRADE, which
     * Keystore resolves itself, so it is deliberately absent.
     */
    private val CORRUPTED_KEY_BLOB_MESSAGES = listOf(
        "invalid key blob",
        "invalid_key_blob",
        "key blob corrupted",
        "internal keystore code: -33",
        "error -33",
        "error: -33",
    )

    /**
     * Keystore-level corruption text. "Uninitialized" is deliberately absent: it means `load()` was
     * not called, or an auth-bound key has no lock screen, and wiping fixes neither.
     */
    private val CORRUPTED_KEYSTORE_MESSAGES = listOf("corrupted", "key blob", "unrecoverable")

    /** Keystore 1 ("Key user not authenticated") and Keystore 2 ("User authentication required"). */
    private val AUTH_REQUIRED_MESSAGES = listOf("user authentication required", "key user not authenticated")
}

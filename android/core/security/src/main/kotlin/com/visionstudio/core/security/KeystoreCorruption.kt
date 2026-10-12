package com.visionstudio.core.security

import java.security.GeneralSecurityException

/**
 * Heuristic for "the Keystore key and the stored ciphertext no longer match". Typical causes:
 * a backup restored onto another device, the lock screen being removed (key invalidated), OEM
 * Keystore bugs after OS updates, or a keyset left behind by a crash mid-write.
 *
 * It walks the cause chain because EncryptedSharedPreferences often wraps the root
 * `AEADBadTagException` / `KeyStoreException` in a `SecurityException`.
 */
internal fun Throwable.isKeystoreCorruption(): Boolean =
    generateSequence(this) { current -> current.cause?.takeIf { it !== current } }
        .take(MAX_CAUSE_DEPTH)
        .any { it.isCorruptionSignal() }

private fun Throwable.isCorruptionSignal(): Boolean =
    this is GeneralSecurityException || // AEADBadTag, KeyStore, UnrecoverableKey, InvalidKey, KeyPermanentlyInvalidated
        this is java.security.ProviderException || // Keystore2 / StrongBox HAL failures
        this is SecurityException || // EncryptedSharedPreferences "Could not decrypt value"
        javaClass.name.endsWith(TINK_KEYSET_PARSE_FAILURE) // corrupt Tink keyset protobuf

private const val MAX_CAUSE_DEPTH = 8
private const val TINK_KEYSET_PARSE_FAILURE = "InvalidProtocolBufferException"

package com.visionstudio.core.security

import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import java.security.KeyStoreException
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException

/** Runs on Robolectric so the platform Keystore exception types behave as on a real device. */
@RunWith(RobolectricTestRunner::class)
class KeystoreCorruptionTest {

    // region proven corruption: wipe

    @Test
    fun `wipes on AEADBadTagException wrapped in GeneralSecurityException`() {
        val ex = GeneralSecurityException("Decryption failed", AEADBadTagException("Tag mismatch"))
        assertTrue(KeystoreCorruption.isPermanentCorruption(ex))
    }

    @Test
    fun `wipes when EncryptedSharedPreferences reports that Tink exhausted every key`() {
        // Exact shape from security-crypto 1.1.0-alpha06 over tink-android 1.8.0: Tink drops the
        // AEADBadTagException, so this message is the only signal.
        val ex = SecurityException("Could not decrypt value.decryption failed", GeneralSecurityException("decryption failed"))
        assertTrue(KeystoreCorruption.isPermanentCorruption(ex))
    }

    @Test
    fun `wipes on a permanently invalidated or unrecoverable key`() {
        val invalidated = KeyStoreException(
            "the master key android-keystore://visionstudio_master_key exists but is unusable",
            KeyPermanentlyInvalidatedException("Key permanently invalidated"),
        )
        assertTrue(KeystoreCorruption.isPermanentCorruption(invalidated))
        assertTrue(KeystoreCorruption.isPermanentCorruption(UnrecoverableKeyException("Failed to obtain information about key")))
    }

    @Test
    fun `wipes on a platform invalid key blob, identified through a generic wrapper`() {
        val ex = InvalidKeyException("Keystore operation failed", platformKeystoreException(INVALID_KEY_BLOB, "Invalid key blob"))
        assertTrue(KeystoreCorruption.isPermanentCorruption(ex))
    }

    @Test
    fun `wipes on a platform corrupted key blob`() {
        val ex = InvalidKeyException("Keystore operation failed", platformKeystoreException(VALUE_CORRUPTED, "Key blob corrupted"))
        assertTrue(KeystoreCorruption.isPermanentCorruption(ex))
    }

    @Test
    fun `wipes on keystore or provider messages that name a corrupt key blob`() {
        assertTrue(KeystoreCorruption.isPermanentCorruption(KeyStoreException("Key blob corrupted")))
        assertTrue(KeystoreCorruption.isPermanentCorruption(ProviderException("Failed to load key: Invalid key blob")))
        assertTrue(KeystoreCorruption.isPermanentCorruption(ProviderException("internal Keystore code: -33")))
        assertTrue(KeystoreCorruption.isPermanentCorruption(ProviderException("Keystore operation failed: error: -33 INVALID_KEY_BLOB")))
        assertTrue(KeystoreCorruption.isPermanentCorruption(KeyPermanentlyInvalidatedException()))
    }

    // endregion

    // region recoverable or unknown: keep the keys

    @Test
    fun `does not wipe on transient ProviderException or UserNotAuthenticatedException`() {
        val transient = ProviderException("Keystore binder transaction failed")
        val authRequired = UserNotAuthenticatedException("Screen locked")
        assertFalse(KeystoreCorruption.isPermanentCorruption(transient))
        assertFalse(KeystoreCorruption.isPermanentCorruption(authRequired))
    }

    @Test
    fun `a locked device outranks a corruption signal in the same chain`() {
        // UnrecoverableKeyException alone would wipe; the locked-device cause underneath vetoes it.
        val ex = SecurityException(
            "Could not decrypt value.",
            UnrecoverableKeyException("Failed to obtain information about key")
                .apply { initCause(UserNotAuthenticatedException("Screen locked")) },
        )
        assertFalse(KeystoreCorruption.isPermanentCorruption(ex))
    }

    @Test
    fun `does not wipe when the platform says authentication is required or to retry`() {
        val authRequired = UnrecoverableKeyException("Failed to obtain information about key")
            .apply { initCause(platformKeystoreException(LOCKED, "User authentication required")) }
        val transient = UnrecoverableKeyException("Failed to obtain information about key")
            .apply { initCause(platformKeystoreException(TRANSIENT_KEYMINT_FAILURE, "Busy")) }
        assertFalse(KeystoreCorruption.isPermanentCorruption(authRequired))
        assertFalse(KeystoreCorruption.isPermanentCorruption(transient))
    }

    @Test
    fun `does not wipe on a key that only requires an upgrade`() {
        // -62 is KEY_REQUIRES_UPGRADE; Keystore upgrades the blob itself.
        val ex = InvalidKeyException("Keystore operation failed", platformKeystoreException(KEY_REQUIRES_UPGRADE, "Key requires upgrade"))
        assertFalse(KeystoreCorruption.isPermanentCorruption(ex))
        assertFalse(KeystoreCorruption.isPermanentCorruption(ProviderException("Keystore error -62")))
    }

    @Test
    fun `does not wipe on a platform system error`() {
        val ex = InvalidKeyException("Keystore operation failed", platformKeystoreException(SYSTEM_ERROR, "System error"))
        assertFalse(KeystoreCorruption.isPermanentCorruption(ex))
    }

    @Test
    fun `does not wipe on an uninitialised keystore`() {
        assertFalse(KeystoreCorruption.isPermanentCorruption(KeyStoreException("Uninitialized keystore")))
    }

    @Test
    fun `does not wipe on unclassified security failures`() {
        assertFalse(KeystoreCorruption.isPermanentCorruption(GeneralSecurityException("cannot read or generate keyset")))
        assertFalse(KeystoreCorruption.isPermanentCorruption(SecurityException("Permission denied")))
        assertFalse(KeystoreCorruption.isPermanentCorruption(KeyStoreException("keystore unavailable")))
        assertFalse(KeystoreCorruption.isPermanentCorruption(ProviderException("strongbox HAL")))
    }

    @Test
    fun `does not wipe on ordinary failures`() {
        assertFalse(KeystoreCorruption.isPermanentCorruption(IllegalStateException("bug")))
        assertFalse(KeystoreCorruption.isPermanentCorruption(IOException("disk full")))
        assertFalse(KeystoreCorruption.isPermanentCorruption(IllegalArgumentException("bad input")))
    }

    @Test
    fun `classify separates wipe, unlock and retry`() {
        assertEquals(
            KeystoreCorruption.Action.WIPE_AND_RESET,
            KeystoreCorruption.classify(GeneralSecurityException("Decrypt failed", AEADBadTagException("Tag mismatch"))),
        )
        assertEquals(
            KeystoreCorruption.Action.REQUIRE_USER_AUTH,
            KeystoreCorruption.classify(GeneralSecurityException("Failed", UserNotAuthenticatedException("Device locked"))),
        )
        assertEquals(
            KeystoreCorruption.Action.TRANSIENT_RETRY,
            KeystoreCorruption.classify(ProviderException("DeadSystemException: Keystore binder transaction failed")),
        )
        // A transient platform failure vetoes the UnrecoverableKeyException that wraps it.
        val transientUnderUnrecoverable = UnrecoverableKeyException("Failed to obtain information about key")
            .apply { initCause(platformKeystoreException(TRANSIENT_KEYMINT_FAILURE, "Busy")) }
        assertEquals(KeystoreCorruption.Action.TRANSIENT_RETRY, KeystoreCorruption.classify(transientUnderUnrecoverable))
    }

    @Test
    fun `self-referential cause chains terminate`() {
        val looping = object : RuntimeException("loop") {
            override val cause: Throwable get() = this
        }
        assertFalse(KeystoreCorruption.isPermanentCorruption(looping))
    }

    // endregion

    /** The platform constructor is hidden from the SDK stubs, but public on the device. */
    private fun platformKeystoreException(internalCode: Int, message: String): Throwable =
        android.security.KeyStoreException::class.java
            .getConstructor(Int::class.javaPrimitiveType, String::class.java)
            .newInstance(internalCode, message)

    private companion object {
        /** Internal Keystore / KeyMint codes, as passed to the platform constructor. */
        const val INVALID_KEY_BLOB = -33
        const val KEY_REQUIRES_UPGRADE = -62
        /** The platform maps this code to `isTransientFailure() == true`. */
        const val TRANSIENT_KEYMINT_FAILURE = -28
        const val LOCKED = 2
        const val SYSTEM_ERROR = 4
        const val VALUE_CORRUPTED = 8
    }
}

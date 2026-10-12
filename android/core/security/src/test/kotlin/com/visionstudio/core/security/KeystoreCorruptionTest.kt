package com.visionstudio.core.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.security.KeyStoreException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException

class KeystoreCorruptionTest {

    @Test
    fun `direct crypto failures are corruption`() {
        assertTrue(AEADBadTagException("tag mismatch").isKeystoreCorruption())
        assertTrue(KeyStoreException("keystore unavailable").isKeystoreCorruption())
        assertTrue(UnrecoverableKeyException("no key").isKeystoreCorruption())
        assertTrue(java.security.ProviderException("strongbox HAL").isKeystoreCorruption())
    }

    @Test
    fun `wrapped crypto failure is detected through the cause chain`() {
        val wrapped = RuntimeException(
            "outer",
            SecurityException("Could not decrypt value", AEADBadTagException("tag mismatch")),
        )
        assertTrue(wrapped.isKeystoreCorruption())
    }

    @Test
    fun `tink keyset parse failure is detected by class name`() {
        assertTrue(IOException("io", InvalidProtocolBufferException()).isKeystoreCorruption())
    }

    @Test
    fun `ordinary failures are not corruption`() {
        assertFalse(IllegalStateException("bug").isKeystoreCorruption())
        assertFalse(IOException("disk full").isKeystoreCorruption())
        assertFalse(IllegalArgumentException("bad input").isKeystoreCorruption())
    }

    @Test
    fun `self-referential cause chains terminate`() {
        val looping = object : RuntimeException("loop") {
            override val cause: Throwable get() = this
        }
        assertFalse(looping.isKeystoreCorruption())
    }

    /** Stand-in for Tink's shaded protobuf exception, matched by simple name. */
    private class InvalidProtocolBufferException : IOException("Protocol message had invalid UTF-8")
}

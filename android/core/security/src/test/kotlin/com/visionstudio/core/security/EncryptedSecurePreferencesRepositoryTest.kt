package com.visionstudio.core.security

import app.cash.turbine.test
import com.visionstudio.core.common.error.VisionStudioException
import com.visionstudio.core.common.result.Result
import com.visionstudio.core.common.result.exceptionOrNull
import com.visionstudio.core.common.result.getOrThrow
import com.visionstudio.core.model.AuthScheme
import com.visionstudio.core.model.EngineConfig
import com.visionstudio.core.model.EngineSlot
import com.visionstudio.core.model.ProviderProtocol
import com.visionstudio.core.model.RestDialect
import com.visionstudio.core.security.fakes.FakeEncryptedPreferencesFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyStoreException
import javax.crypto.AEADBadTagException

@OptIn(ExperimentalCoroutinesApi::class)
class EncryptedSecurePreferencesRepositoryTest {

    private val factory = FakeEncryptedPreferencesFactory()

    private fun TestScope.repository() =
        EncryptedSecurePreferencesRepository(factory, UnconfinedTestDispatcher(testScheduler))

    private val falConfig = EngineConfig(
        slot = EngineSlot.VIDEO_STUDIO,
        protocol = ProviderProtocol.REST,
        endpointUrl = "https://queue.fal.run/fal-ai/kling-video",
        modelId = "kling-v2",
        restDialect = RestDialect.FAL_QUEUE,
        authScheme = AuthScheme.KEY,
    )

    private fun decryptFailure() =
        SecurityException("Could not decrypt value", AEADBadTagException("Tag mismatch"))

    // region happy path

    @Test
    fun `api key round-trips trimmed and is scoped per slot`() = runTest {
        val repo = repository()

        assertEquals(Result.Success(Unit), repo.setApiKey(EngineSlot.TEXT_TO_IMAGE, "  sk-test-123 \n"))

        assertEquals("sk-test-123", repo.getApiKey(EngineSlot.TEXT_TO_IMAGE).getOrThrow())
        assertTrue(repo.hasApiKey(EngineSlot.TEXT_TO_IMAGE).getOrThrow())
        assertNull(repo.getApiKey(EngineSlot.IMAGE_EDIT).getOrThrow())
        assertFalse(repo.hasApiKey(EngineSlot.IMAGE_EDIT).getOrThrow())
    }

    @Test
    fun `blank api key is rejected without touching storage`() = runTest {
        val repo = repository()

        val result = repo.setApiKey(EngineSlot.TEXT_TO_IMAGE, "   ")

        assertTrue(result.exceptionOrNull() is VisionStudioException.ValidationException)
        assertEquals(0, factory.createCount)
    }

    @Test
    fun `clearApiKey removes only that slot`() = runTest {
        val repo = repository()
        repo.setApiKey(EngineSlot.TEXT_TO_IMAGE, "a")
        repo.setApiKey(EngineSlot.IMAGE_EDIT, "b")

        repo.clearApiKey(EngineSlot.TEXT_TO_IMAGE)

        assertNull(repo.getApiKey(EngineSlot.TEXT_TO_IMAGE).getOrThrow())
        assertEquals("b", repo.getApiKey(EngineSlot.IMAGE_EDIT).getOrThrow())
    }

    @Test
    fun `engine config round-trips and defaults to unconfigured`() = runTest {
        val repo = repository()

        assertEquals(
            EngineConfig.unconfigured(EngineSlot.VIDEO_STUDIO),
            repo.getEngineConfig(EngineSlot.VIDEO_STUDIO).getOrThrow(),
        )

        repo.saveEngineConfig(falConfig)

        assertEquals(falConfig, repo.getEngineConfig(EngineSlot.VIDEO_STUDIO).getOrThrow())
    }

    @Test
    fun `secrets are not stored inside the config payload`() = runTest {
        val repo = repository()
        repo.setApiKey(EngineSlot.VIDEO_STUDIO, "fal-secret")
        repo.saveEngineConfig(falConfig)

        val rawConfig = factory.store.rawString(
            EncryptedSecurePreferencesRepository.configKey(EngineSlot.VIDEO_STUDIO),
        ).orEmpty()

        assertFalse(rawConfig.contains("fal-secret"))
    }

    @Test
    fun `clearAll wipes keys and configs`() = runTest {
        val repo = repository()
        repo.setApiKey(EngineSlot.VIDEO_STUDIO, "fal-secret")
        repo.saveEngineConfig(falConfig)

        repo.clearAll().getOrThrow()

        assertNull(repo.getApiKey(EngineSlot.VIDEO_STUDIO).getOrThrow())
        assertEquals(
            EngineConfig.unconfigured(EngineSlot.VIDEO_STUDIO),
            repo.getEngineConfig(EngineSlot.VIDEO_STUDIO).getOrThrow(),
        )
    }

    @Test
    fun `underlying store is opened once and reused`() = runTest {
        val repo = repository()
        repo.setApiKey(EngineSlot.TEXT_TO_IMAGE, "a")
        repo.getApiKey(EngineSlot.TEXT_TO_IMAGE)
        repo.getEngineConfig(EngineSlot.TEXT_TO_IMAGE)

        assertEquals(1, factory.createCount)
    }

    // endregion

    // region corruption recovery

    @Test
    fun `corrupted keystore on open is purged and reinitialised`() = runTest {
        factory.createFailures += KeyStoreException("Signature/MAC verification failed")
        val repo = repository()

        val result = repo.setApiKey(EngineSlot.TEXT_TO_IMAGE, "fresh-key")

        assertEquals(Result.Success(Unit), result)
        assertEquals(1, factory.purgeCount)
        assertEquals(2, factory.createCount)
        assertEquals("fresh-key", repo.getApiKey(EngineSlot.TEXT_TO_IMAGE).getOrThrow())
        val event = repo.recoveryEvents.replayCache.single()
        assertEquals(KeyStoreException::class.java.name, event.exceptionType)
    }

    @Test
    fun `undecryptable value on read triggers purge and returns empty`() = runTest {
        val repo = repository()
        repo.setApiKey(EngineSlot.IMAGE_EDIT, "old-key")
        factory.store.readFailure = ::decryptFailure

        val result = repo.getApiKey(EngineSlot.IMAGE_EDIT)

        assertEquals(Result.Success(null), result)
        assertEquals(1, factory.purgeCount)
        assertEquals(1, repo.recoveryEvents.replayCache.size)
    }

    @Test
    fun `store stays usable after recovery`() = runTest {
        val repo = repository()
        factory.store.readFailure = ::decryptFailure
        repo.getEngineConfig(EngineSlot.TEXT_TO_IMAGE)

        repo.saveEngineConfig(falConfig).getOrThrow()

        assertEquals(falConfig, repo.getEngineConfig(EngineSlot.VIDEO_STUDIO).getOrThrow())
        assertEquals(1, factory.purgeCount)
    }

    @Test
    fun `persistent corruption surfaces SecureStorageException after a single purge`() = runTest {
        factory.createFailures += KeyStoreException("first")
        factory.createFailures += KeyStoreException("second")
        val repo = repository()

        val error = repo.getApiKey(EngineSlot.TEXT_TO_IMAGE).exceptionOrNull()

        assertTrue(error is VisionStudioException.SecureStorageException)
        assertEquals(1, factory.purgeCount)
        assertTrue(error!!.cause!!.suppressed.any { it.message == "first" })
    }

    @Test
    fun `purge failure surfaces SecureStorageException`() = runTest {
        factory.createFailures += KeyStoreException("corrupt")
        factory.purgeFailure = IllegalStateException("keystore locked")
        val repo = repository()

        val error = repo.getApiKey(EngineSlot.TEXT_TO_IMAGE).exceptionOrNull()

        assertTrue(error is VisionStudioException.SecureStorageException)
        assertTrue(repo.recoveryEvents.replayCache.isEmpty())
    }

    @Test
    fun `non-crypto failures never purge user data`() = runTest {
        val repo = repository()
        repo.setApiKey(EngineSlot.TEXT_TO_IMAGE, "keep-me")
        factory.store.readFailure = { IllegalStateException("unexpected") }

        val error = repo.getApiKey(EngineSlot.TEXT_TO_IMAGE).exceptionOrNull()

        assertTrue(error is VisionStudioException.SecureStorageException)
        assertEquals(0, factory.purgeCount)
        assertTrue(repo.recoveryEvents.replayCache.isEmpty())
        factory.store.readFailure = null
        assertEquals("keep-me", repo.getApiKey(EngineSlot.TEXT_TO_IMAGE).getOrThrow())
    }

    @Test
    fun `rejected commit is reported as an error`() = runTest {
        val repo = repository()
        repo.getApiKey(EngineSlot.TEXT_TO_IMAGE)
        factory.store.commitSucceeds = false

        val error = repo.setApiKey(EngineSlot.TEXT_TO_IMAGE, "k").exceptionOrNull()

        assertTrue(error is VisionStudioException.SecureStorageException)
    }

    @Test
    fun `malformed config json is dropped without a keystore purge`() = runTest {
        val repo = repository()
        repo.getApiKey(EngineSlot.TEXT_TO_IMAGE)
        val key = EncryptedSecurePreferencesRepository.configKey(EngineSlot.IMAGE_EDIT)
        factory.store.seed(key, "{not-json")

        val config = repo.getEngineConfig(EngineSlot.IMAGE_EDIT).getOrThrow()

        assertEquals(EngineConfig.unconfigured(EngineSlot.IMAGE_EDIT), config)
        assertNull(factory.store.rawString(key))
        assertEquals(0, factory.purgeCount)
    }

    // endregion

    // region observation

    @Test
    fun `observeEngineConfigs emits on save and on recovery`() = runTest {
        val repo = repository()

        repo.observeEngineConfigs().test {
            assertEquals(
                EngineConfig.unconfigured(EngineSlot.VIDEO_STUDIO),
                awaitItem().getValue(EngineSlot.VIDEO_STUDIO),
            )

            repo.saveEngineConfig(falConfig)
            assertEquals(falConfig, awaitItem().getValue(EngineSlot.VIDEO_STUDIO))

            factory.store.readFailure = ::decryptFailure
            repo.getApiKey(EngineSlot.TEXT_TO_IMAGE)
            assertEquals(
                EngineConfig.unconfigured(EngineSlot.VIDEO_STUDIO),
                awaitItem().getValue(EngineSlot.VIDEO_STUDIO),
            )

            cancelAndIgnoreRemainingEvents()
        }
    }

    // endregion
}

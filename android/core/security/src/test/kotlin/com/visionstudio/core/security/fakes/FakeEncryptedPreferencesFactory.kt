package com.visionstudio.core.security.fakes

import android.content.SharedPreferences
import com.visionstudio.core.security.EncryptedPreferencesFactory

/** Simulates the Keystore-backed factory: queued [createFailures], counted purges. */
class FakeEncryptedPreferencesFactory : EncryptedPreferencesFactory {

    var store: InMemorySharedPreferences = InMemorySharedPreferences()
        private set

    /** Each [create] call pops and throws the next queued failure, if any. */
    val createFailures: ArrayDeque<Exception> = ArrayDeque()

    var purgeFailure: Exception? = null

    var createCount: Int = 0
        private set

    var purgeCount: Int = 0
        private set

    override fun create(): SharedPreferences {
        createCount++
        createFailures.removeFirstOrNull()?.let { throw it }
        return store
    }

    override fun purge() {
        purgeCount++
        purgeFailure?.let { throw it }
        store = InMemorySharedPreferences()
    }
}

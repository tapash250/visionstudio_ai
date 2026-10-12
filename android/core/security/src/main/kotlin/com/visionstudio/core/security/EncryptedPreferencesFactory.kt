package com.visionstudio.core.security

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.KeyStore
import javax.inject.Inject

/**
 * Creates and destroys the encrypted credential store. Split out from the repository so the
 * recovery logic can be unit-tested without a real AndroidKeyStore.
 *
 * Both functions perform Keystore and disk I/O and must be called on an IO dispatcher.
 */
interface EncryptedPreferencesFactory {
    /** Opens (creating if needed) the encrypted preferences. May throw on Keystore corruption. */
    fun create(): SharedPreferences

    /** Irreversibly deletes the preferences file, its Tink keysets and the Keystore master key. */
    fun purge()
}

internal class KeystoreEncryptedPreferencesFactory @Inject constructor(
    @ApplicationContext private val context: Context,
) : EncryptedPreferencesFactory {

    override fun create(): SharedPreferences {
        val masterKey = MasterKey.Builder(context, MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setRequestStrongBoxBacked(true)
            }
            .build()

        return EncryptedSharedPreferences.create(
            context,
            PREFERENCES_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun purge() {
        // Tink stores its wrapped keysets inside the same file, so deleting it drops both the
        // ciphertexts and the keysets that can no longer be unwrapped.
        context.deleteSharedPreferences(PREFERENCES_FILE)
        val sharedPrefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        File(sharedPrefsDir, "$PREFERENCES_FILE.xml").delete()
        File(sharedPrefsDir, "$PREFERENCES_FILE.xml.bak").delete()

        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(MASTER_KEY_ALIAS)) {
            keyStore.deleteEntry(MASTER_KEY_ALIAS)
        }
    }

    companion object {
        /** Must match the exclusion in the app's backup / data-extraction rules. */
        const val PREFERENCES_FILE: String = "visionstudio_secure_prefs"
        const val MASTER_KEY_ALIAS: String = "visionstudio_master_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}

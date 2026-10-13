package com.mymoney.core.data.security

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tink primitives whose keysets are wrapped by a hardware-backed Android Keystore master key.
 * No custom cryptography: AES-256-GCM (AEAD) and AES-256-GCM-HKDF streaming (files) only (docs/SECURITY.md).
 */
@Singleton
class CryptoProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val aead: Aead by lazy {
        AeadConfig.register()
        keysetManager(AEAD_KEYSET, "AES256_GCM")
            .keysetHandle
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    val streamingAead: StreamingAead by lazy {
        StreamingAeadConfig.register()
        keysetManager(STREAMING_KEYSET, "AES256_GCM_HKDF_4KB")
            .keysetHandle
            .getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)
    }

    private fun keysetManager(name: String, template: String): AndroidKeysetManager =
        AndroidKeysetManager.Builder()
            .withSharedPref(context, name, PREF_FILE)
            .withKeyTemplate(KeyTemplates.get(template))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()

    /** Used by "delete all data": removes keysets so previously encrypted material is unrecoverable. */
    fun destroyKeys() {
        context.deleteSharedPreferences(PREF_FILE)
        runCatching {
            val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            ks.deleteEntry(MASTER_KEY_ALIAS)
        }
    }

    companion object {
        private const val PREF_FILE = "mymoney_keysets"
        private const val AEAD_KEYSET = "aead_keyset"
        private const val STREAMING_KEYSET = "streaming_keyset"
        private const val MASTER_KEY_ALIAS = "mymoney_master_key"
        private const val MASTER_KEY_URI = "android-keystore://$MASTER_KEY_ALIAS"
    }
}

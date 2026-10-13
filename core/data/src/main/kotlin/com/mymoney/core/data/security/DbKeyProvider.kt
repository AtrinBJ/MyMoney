package com.mymoney.core.data.security

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides the SQLCipher key: 32 random bytes, stored only in encrypted form (Tink AEAD, Keystore-wrapped).
 *
 * The key is passed to SQLCipher in raw-key syntax (`x'<hex>'`), which skips SQLCipher's internal PBKDF2.
 * A random 256-bit key needs no stretching, and skipping the KDF keeps app start fast.
 */
@Singleton
class DbKeyProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val crypto: CryptoProvider,
) {
    private val keyFile: File get() = File(context.noBackupFilesDir, KEY_FILE)

    @Synchronized
    fun sqlCipherPassphrase(): ByteArray {
        val raw = loadOrCreateRawKey()
        val hex = raw.joinToString("") { "%02x".format(it) }
        raw.fill(0)
        return "x'$hex'".toByteArray(Charsets.US_ASCII)
    }

    private fun loadOrCreateRawKey(): ByteArray {
        val file = keyFile
        if (file.exists()) {
            return crypto.aead.decrypt(file.readBytes(), ASSOCIATED_DATA)
        }
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val tmp = File(file.parentFile, "$KEY_FILE.tmp")
        tmp.writeBytes(crypto.aead.encrypt(key, ASSOCIATED_DATA))
        check(tmp.renameTo(file)) { "Could not persist database key" }
        return key
    }

    fun destroy() {
        keyFile.delete()
    }

    private companion object {
        const val KEY_FILE = "db.key.enc"
        val ASSOCIATED_DATA = "mymoney-db-key-v1".toByteArray()
    }
}

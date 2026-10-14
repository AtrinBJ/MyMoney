package com.mymoney.core.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Opens the encrypted database only when it is first used. Room creates its open helper while the database object
 * is built (on the main thread, during dependency injection), but reads/writes happen on background threads. The
 * expensive steps — loading SQLCipher's native library and decrypting the key with the Keystore — run here, on
 * that first background access, instead of blocking app start (~190 ms measured on a Galaxy A55).
 */
class DeferredOpenHelperFactory(private val passphrase: () -> ByteArray) : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper = Deferred(configuration)

    private inner class Deferred(private val configuration: SupportSQLiteOpenHelper.Configuration) : SupportSQLiteOpenHelper {
        @Volatile private var walEnabled: Boolean? = null
        private val delegate: SupportSQLiteOpenHelper by lazy {
            System.loadLibrary("sqlcipher")
            SupportOpenHelperFactory(passphrase()).create(configuration).also { h -> walEnabled?.let { h.setWriteAheadLoggingEnabled(it) } }
        }
        private var opened = false

        override val databaseName: String? get() = configuration.name

        override fun setWriteAheadLoggingEnabled(enabled: Boolean) {
            if (opened) delegate.setWriteAheadLoggingEnabled(enabled) else walEnabled = enabled
        }

        override val writableDatabase: SupportSQLiteDatabase get() = delegate.also { opened = true }.writableDatabase
        override val readableDatabase: SupportSQLiteDatabase get() = delegate.also { opened = true }.readableDatabase

        override fun close() {
            if (opened) delegate.close()
        }
    }
}

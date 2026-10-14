package com.mymoney.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mymoney.core.data.security.CryptoProvider
import com.mymoney.core.data.security.DbKeyProvider
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on a real device: the database file on disk must not be readable as plain SQLite (production open path). */
@RunWith(AndroidJUnit4::class)
class EncryptedDatabaseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "encryption-test.db"

    @After
    fun cleanup() {
        context.deleteDatabase(name)
    }

    @Test
    fun databaseFileIsEncryptedAndReopensWithSameKey() {
        val keys = DbKeyProvider(context, CryptoProvider(context))
        fun open() = Room.databaseBuilder(context, MyMoneyDatabase::class.java, name)
            .openHelperFactory(DeferredOpenHelperFactory { keys.sqlCipherPassphrase() }) // same path as the app
            .addCallback(MyMoneyDatabase.SeedCallback)
            .build()

        val db = open()
        val count = runBlocking { db.currencyDao().getAll().size }
        assertTrue(count > 0)
        db.close()

        val header = ByteArray(16).also { buf -> context.getDatabasePath(name).inputStream().use { it.read(buf) } }
        assertFalse(String(header, Charsets.US_ASCII).startsWith("SQLite format 3"), "file must be encrypted")

        val reopened = open()
        assertEquals(count, runBlocking { reopened.currencyDao().getAll().size })
        reopened.close()
    }
}

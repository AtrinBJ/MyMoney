package com.mymoney.core.data.security

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mymoney.core.data.attachments.AttachmentStore
import com.mymoney.core.data.backup.BackupManager
import com.mymoney.core.data.backup.InspectResult
import com.mymoney.core.data.db.MyMoneyDatabase
import com.mymoney.core.data.prefs.DataStoreSettingsRepository
import com.mymoney.core.domain.common.IdGenerator
import com.mymoney.core.domain.common.TimeProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs on a real device (Stage 6): the parts that need the hardware-backed Android Keystore, which Robolectric
 * does not provide. Uses temporary folders and an in-memory database, so the user's data is never touched.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreBackedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val crypto = CryptoProvider(context)
    private val root = File(context.cacheDir, "keystore-test").apply { deleteRecursively(); mkdirs() }
    private val clock = object : TimeProvider {
        override fun nowMillis() = System.currentTimeMillis()
        override fun today(): LocalDate = LocalDate.now()
    }
    private val ids = object : IdGenerator {
        var n = 0
        override fun newId() = "kt-${n++}"
    }

    @After
    fun cleanup() {
        root.deleteRecursively()
    }

    @Test
    fun pinRecordIsEncryptedWithTheKeystoreKey() {
        val pins = PinStore(root, { crypto.aead }, clock)
        pins.setPin("2580")
        val raw = File(root, "pin.enc").readBytes()
        assertFalse(String(raw, Charsets.ISO_8859_1).contains("2580"))
        assertEquals(PinStore.Result.Ok, pins.verify("2580"))
        assertIs<PinStore.Result.Wrong>(pins.verify("1111"))
        pins.clear()
    }

    @Test
    fun attachmentsAndBackupsWithRealKeys(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, MyMoneyDatabase::class.java).addCallback(MyMoneyDatabase.SeedCallback).build()
        try {
            val attachments = AttachmentStore(File(root, "att"), { crypto.streamingAead }, db, ids, clock)
            val content = ByteArray(300_000) { (it % 253).toByte() }
            val a = attachments.add("g1", "receipt.jpg", "image/jpeg", content.inputStream())
            // On disk the file is ciphertext.
            val stored = File(root, "att/${a.storedName}").readBytes()
            assertFalse(stored.copyOfRange(100, 400).contentEquals(content.copyOfRange(100, 400)))
            assertTrue(attachments.open(a).use { it.readBytes() }.contentEquals(content))

            val prefs = object : DataStore<Preferences> {
                val state = MutableStateFlow(emptyPreferences())
                override val data: Flow<Preferences> = state
                override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences) = transform(state.value).also { state.value = it }
            }
            val backups = BackupManager(db, attachments, DataStoreSettingsRepository(prefs), { crypto.streamingAead }, File(root, "cache"), File(root, "safety"), clock, ids, "test")
            val withPassword = ByteArrayOutputStream().also { backups.export(it, "device test pw".toCharArray()) }.toByteArray()
            assertIs<InspectResult.Ready>(backups.inspect({ withPassword.inputStream() }, "device test pw".toCharArray()))
            assertIs<InspectResult.WrongPassword>(backups.inspect({ withPassword.inputStream() }, "nope".toCharArray()))
            // Safety copy uses the device key.
            val safety = backups.safetyBackup()
            assertIs<InspectResult.Ready>(backups.inspect({ safety.inputStream() }, null))
        } finally {
            db.close()
        }
    }
}

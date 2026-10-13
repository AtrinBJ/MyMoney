package com.mymoney.core.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Schema upgrades must keep every existing row (docs/03 §3.2). */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val file = context.getDatabasePath("migration-test.db")

    @get:Rule
    val helper = MigrationTestHelper(
        instrumentation = InstrumentationRegistry.getInstrumentation(),
        file = file,
        driver = AndroidSQLiteDriver(),
        databaseClass = MyMoneyDatabase::class,
    )

    @Test
    fun migrate1to3_preserves_ledger_data() {
        helper.createDatabase(1).apply {
            execSQL("INSERT INTO currency (code, minorDigits, symbol, nameFa, nameEn, isCustom, isEnabled, sortOrder) VALUES ('IRR', 0, 'r', 'r', 'Rial', 0, 1, 0)")
            execSQL(
                "INSERT INTO category (id, type, parentId, systemKey, name, iconKey, colorKey, defaultCostType, defaultEssential, isArchived, sortOrder, createdAt, updatedAt) " +
                    "VALUES ('sys-expense.food', 'EXPENSE', NULL, 'expense.food', NULL, NULL, NULL, NULL, NULL, 0, 0, 1, 1)",
            )
            execSQL(
                "INSERT INTO account (id, name, type, currencyCode, institution, startDate, note, colorKey, iconKey, isArchived, " +
                    "includeInNetWorth, includeInLiquid, creditLimitMinor, sortOrder, createdAt, updatedAt) " +
                    "VALUES ('a1', 'Bank', 'BANK', 'IRR', NULL, 20000, NULL, NULL, NULL, 0, 1, 1, NULL, 0, 1, 1)",
            )
            execSQL(
                "INSERT INTO entry (id, groupId, accountId, kind, amountMinor, date, timeMinutes, status, categoryId, payee, note, costType, " +
                    "essential, relatedEntryId, recurringRuleId, occurrenceDate, assetTradeId, importBatchId, createdAt, updatedAt, deletedAt) " +
                    "VALUES ('e1', 'g1', 'a1', 'EXPENSE', -12345, 20100, NULL, 'COMPLETED', 'sys-expense.food', 'Shop', NULL, NULL, NULL, NULL, " +
                    "NULL, NULL, NULL, NULL, 1, 1, NULL)",
            )
            execSQL("INSERT INTO tag (id, name, colorKey, createdAt) VALUES ('t1', 'trip', NULL, 1)")
            execSQL("INSERT INTO entry_tag (entryId, tagId) VALUES ('e1', 't1')")
            close()
        }

        helper.runMigrationsAndValidate(2).close()
        helper.runMigrationsAndValidate(3).close()

        val db = Room.databaseBuilder(context, MyMoneyDatabase::class.java, file.absolutePath)
            .allowMainThreadQueries()
            .build()
        runBlocking {
            assertEquals(-12345, db.accountDao().balance("a1", 99999))
            assertEquals("Shop", db.entryDao().groupEntries("g1").single().payee)
            assertEquals(listOf("t1"), db.entryDao().tagsFor(listOf("e1")).map { it.tagId })
            // New v2 tables exist and are usable.
            assertEquals(0, db.recurringDao().getAll().size)
            assertEquals(0L, db.goalDao().allocatedFrom("a1"))
            // v3 tables exist; an existing entry has no attachments and no import batch.
            assertEquals(0, db.attachmentDao().all().size)
            assertEquals(0, db.importBatchDao().all().size)
        }
        db.close()
    }
}

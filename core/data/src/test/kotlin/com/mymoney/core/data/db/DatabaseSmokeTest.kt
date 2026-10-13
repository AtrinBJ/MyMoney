package com.mymoney.core.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** JVM tests use plain Room in memory; SQLCipher (native) is verified by the instrumented test on device. */
@RunWith(AndroidJUnit4::class)
class DatabaseSmokeTest {
    private lateinit var db: MyMoneyDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MyMoneyDatabase::class.java)
            .addCallback(MyMoneyDatabase.SeedCallback)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `reference currencies are seeded on create`() = runTest {
        val all = db.currencyDao().getAll()
        assertEquals(SeedData.currencies.size, all.size)
        val irr = assertNotNull(db.currencyDao().get("IRR"))
        assertEquals(0, irr.minorDigits)
        assertEquals(2, db.currencyDao().get("EUR")!!.minorDigits)
        assertEquals("IRR", all.first().code)
    }
}

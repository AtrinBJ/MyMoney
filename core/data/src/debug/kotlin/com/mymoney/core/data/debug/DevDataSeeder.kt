package com.mymoney.core.data.debug

import androidx.room.withTransaction
import com.mymoney.core.data.db.MyMoneyDatabase
import com.mymoney.core.data.db.SeedData
import com.mymoney.core.data.db.entity.EntryEntity
import com.mymoney.core.domain.common.TimeProvider
import com.mymoney.core.domain.model.Account
import com.mymoney.core.domain.model.AccountType
import com.mymoney.core.domain.model.EntryKind
import com.mymoney.core.domain.model.EntryStatus
import com.mymoney.core.domain.repository.AccountRepository
import java.time.LocalDate
import javax.inject.Inject
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * DEBUG BUILDS ONLY (src/debug): fills the database with clearly fake data for QA and performance checks.
 * Release builds do not contain this class, so they can never show invented numbers.
 * All generated accounts are named "TEST …" / "آزمایشی …".
 */
class DevDataSeeder @Inject constructor(
    private val db: MyMoneyDatabase,
    private val accounts: AccountRepository,
    private val clock: TimeProvider,
) {
    /**
     * Inserts [count] transactions (about 85% expenses, 9% income, 6% transfers with two legs) spread over the
     * last three years, in chunks of 5,000 per database transaction. Deterministic for a given [seed].
     * Returns the number of entry rows written.
     */
    suspend fun seedPerformanceData(count: Int = 100_000, seed: Int = 42): Int = withContext(Dispatchers.IO) {
        val start = LocalDate.of(2020, 1, 1)
        val ids = listOf("perf-bank", "perf-cash", "perf-card", "perf-savings")
        val existing = accounts.getAll().map { it.id }.toSet()
        listOf(
            Account("perf-bank", "آزمایشی بانک TEST", AccountType.BANK, "IRR", startDate = start),
            Account("perf-cash", "آزمایشی نقد TEST", AccountType.CASH, "IRR", startDate = start),
            Account("perf-card", "آزمایشی کارت TEST", AccountType.CREDIT_CARD, "IRR", startDate = start),
            Account("perf-savings", "آزمایشی پس‌انداز TEST", AccountType.SAVINGS, "IRR", startDate = start),
        ).filter { it.id !in existing }.forEach { accounts.create(it, 5_000_000_000) }

        val expenseCats = SeedData.categories.filter { it.key.startsWith("expense.") }.map { SeedData.categoryId(it.key) }
        val incomeCats = SeedData.categories.filter { it.key.startsWith("income.") }.map { SeedData.categoryId(it.key) }
        val payees = listOf("سوپرمارکت", "نانوایی", "تاکسی", "داروخانه", "کافه", "فروشگاه اینترنتی", "پمپ بنزین", "رستوران", "Shop", "Market")
        val rnd = Random(seed)
        val today = clock.today().toEpochDay()
        val first = today - 3 * 365
        val now = clock.nowMillis()
        var written = 0
        var i = 0
        while (i < count) {
            val chunk = minOf(CHUNK, count - i)
            val rows = ArrayList<EntryEntity>(chunk + 200)
            repeat(chunk) { k ->
                val n = i + k
                val date = first + rnd.nextLong(today - first + 1)
                val group = "perf-$seed-$n"
                val roll = rnd.nextInt(100)
                fun leg(suffix: String, account: String, kind: EntryKind, amount: Long, cat: String?, payee: String?) = EntryEntity(
                    id = "$group$suffix", groupId = group, accountId = account, kind = kind, amountMinor = amount, date = date,
                    timeMinutes = rnd.nextInt(24 * 60), status = EntryStatus.COMPLETED, categoryId = cat, payee = payee, note = null,
                    costType = null, essential = null, relatedEntryId = null, recurringRuleId = null, occurrenceDate = null,
                    assetTradeId = null, importBatchId = null, createdAt = now + n, updatedAt = now + n, deletedAt = null,
                )
                when {
                    roll < 85 -> rows += leg(
                        "", ids[rnd.nextInt(3)], EntryKind.EXPENSE, -(10_000L + rnd.nextLong(5_000_000)) * 10,
                        expenseCats[rnd.nextInt(expenseCats.size)], payees[rnd.nextInt(payees.size)] + " " + rnd.nextInt(50),
                    )
                    roll < 94 -> rows += leg("", "perf-bank", EntryKind.INCOME, (2_000_000L + rnd.nextLong(40_000_000)) * 10, incomeCats[rnd.nextInt(incomeCats.size)], null)
                    else -> {
                        val amount = (100_000L + rnd.nextLong(10_000_000)) * 10
                        rows += leg("-o", "perf-bank", EntryKind.TRANSFER, -amount, null, null)
                        rows += leg("-i", if (rnd.nextBoolean()) "perf-savings" else "perf-cash", EntryKind.TRANSFER, amount, null, null)
                    }
                }
            }
            db.withTransaction { db.entryDao().insertEntries(rows) }
            written += rows.size
            i += chunk
        }
        written
    }

    private companion object {
        const val CHUNK = 5_000
    }
}

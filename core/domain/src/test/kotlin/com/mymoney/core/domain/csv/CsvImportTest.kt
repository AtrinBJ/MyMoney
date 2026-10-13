package com.mymoney.core.domain.csv

import com.mymoney.core.domain.common.Outcome
import com.mymoney.core.domain.model.Account
import com.mymoney.core.domain.model.AccountType
import com.mymoney.core.domain.model.EntryKind
import com.mymoney.core.domain.repository.ImportBatch
import com.mymoney.core.domain.repository.ImportBatchRepository
import com.mymoney.core.domain.repository.TransactionRunner
import com.mymoney.core.domain.settings.CalendarType
import com.mymoney.core.domain.settings.IrrUnit
import com.mymoney.core.domain.testing.FixedClock
import com.mymoney.core.domain.testing.InMemoryStore
import com.mymoney.core.domain.testing.SequentialIds
import com.mymoney.core.domain.usecase.ImportCsvUseCase
import com.mymoney.core.domain.usecase.IncomeExpenseDraft
import com.mymoney.core.domain.usecase.RecordOperationUseCase
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class CsvImportTest {
    @Test
    fun `parses quoted fields, embedded delimiters, quotes, line breaks and BOM`() {
        val text = "﻿date;amount;note\r\n1405/07/01;\"-1,250,000\";\"rent; october\"\n1405/07/02;500;\"say \"\"hi\"\"\nsecond line\"\n\n"
        assertEquals(';', Csv.detectDelimiter(text))
        val rows = Csv.parse(text)
        assertEquals(3, rows.size)
        assertEquals(listOf("1405/07/01", "-1,250,000", "rent; october"), rows[1])
        assertEquals("say \"hi\"\nsecond line", rows[2][2])
    }

    @Test
    fun `writing quotes when needed and neutralizes spreadsheet formulas`() {
        assertEquals("a,\"b,c\",\"x \"\"y\"\"\",-1250\r\n", Csv.line(listOf("a", "b,c", "x \"y\"", "-1250")))
        assertEquals("'=HYPERLINK(1),'@cmd,'-x\r\n", Csv.line(listOf("=HYPERLINK(1)", "@cmd", "-x")))
        // Round trip.
        val fields = listOf("خرید، سوپرمارکت", "line\nbreak", "\"q\"", "")
        assertEquals(fields, Csv.parse(Csv.line(fields)).single())
    }

    @Test
    fun `dates in Jalali and Gregorian, any order, Persian digits`() {
        assertEquals(LocalDate.of(2026, 9, 23), CsvImport.parseDate("۱۴۰۵/۰۷/۰۱", CalendarType.JALALI, DateOrder.YMD))
        assertEquals(LocalDate.of(2026, 9, 23), CsvImport.parseDate("01-07-1405", CalendarType.JALALI, DateOrder.DMY))
        assertEquals(LocalDate.of(2026, 10, 14), CsvImport.parseDate("10/14/2026", CalendarType.GREGORIAN, DateOrder.MDY))
        assertEquals(LocalDate.of(2026, 9, 23), CsvImport.parseDate("05/07/01", CalendarType.JALALI, DateOrder.YMD))
        assertEquals(LocalDate.of(2025, 3, 20), CsvImport.parseDate("1403/12/30", CalendarType.JALALI, DateOrder.YMD)) // 1403 is a leap year
        assertNull(CsvImport.parseDate("1404/12/30", CalendarType.JALALI, DateOrder.YMD)) // 1404 is not
        assertNull(CsvImport.parseDate("2026/02/30", CalendarType.GREGORIAN, DateOrder.YMD))
        assertNull(CsvImport.parseDate("yesterday", CalendarType.GREGORIAN, DateOrder.YMD))
        assertEquals(CalendarType.JALALI to DateOrder.YMD, CsvImport.guessDateFormat(listOf("1405/07/01")))
        assertEquals(CalendarType.GREGORIAN to DateOrder.DMY, CsvImport.guessDateFormat(listOf("23/09/2026")))
    }

    private val mapping = CsvMapping(
        dateCol = 0, amountCol = 1, payeeCol = 2, calendar = CalendarType.JALALI, accountId = "bank", currency = "IRR", minorDigits = 0, irrUnit = IrrUnit.RIAL,
    )

    @Test
    fun `every row is validated with a reason and duplicates are flagged`() {
        val rows = Csv.parse(
            "date,amount,payee\n" +
                "1405/07/01,-1250000,Shop\n" + // ok expense
                "1405/07/02,(300),Café\n" + // parentheses = negative
                "1405/07/03,5000000,Salary\n" + // income
                "1405/13/01,10,X\n" + // invalid month
                "1405/07/04,abc,X\n" + // amount
                "1405/07/05,0,X\n" + // zero
                "1405/07/01,-1250000,shop\n" + // same as line 2 (normalized payee) → duplicate within the file
                "1405/07/06,-77,Known\n" + // exists already
                "1405/07/07\n", // missing columns
        )
        val known = setOf(CsvImport.duplicateKey(LocalDate.of(2026, 9, 28), -77, "known"))
        val plan = CsvImport.plan(rows, mapping, known)
        assertEquals(9, plan.size)
        assertEquals(EntryKind.EXPENSE to 1_250_000L, plan[0].kind to plan[0].amountMinor)
        assertEquals(EntryKind.EXPENSE to 300L, plan[1].kind to plan[1].amountMinor)
        assertEquals(EntryKind.INCOME, plan[2].kind)
        assertEquals(RowProblem.DATE, plan[3].problem)
        assertEquals(5, plan[3].line)
        assertEquals(RowProblem.AMOUNT, plan[4].problem)
        assertEquals(RowProblem.ZERO, plan[5].problem)
        assertTrue(plan[6].duplicate && !plan[0].duplicate)
        assertTrue(plan[7].duplicate)
        assertEquals(RowProblem.COLUMNS, plan[8].problem)
    }

    @Test
    fun `debit and credit columns, toman amounts`() {
        val rows = Csv.parse("d,debit,credit\n1405/07/01,\"۱۲٬۵۰۰\",\n1405/07/02,,300\n")
        val plan = CsvImport.plan(rows, mapping.copy(amountCol = null, debitCol = 1, creditCol = 2, irrUnit = IrrUnit.TOMAN), emptySet())
        assertEquals(EntryKind.EXPENSE to 125_000L, plan[0].kind to plan[0].amountMinor) // 12,500 toman = 125,000 rial
        assertEquals(EntryKind.INCOME to 3_000L, plan[1].kind to plan[1].amountMinor)
    }

    private class Batches : ImportBatchRepository {
        val all = MutableStateFlow(emptyList<ImportBatch>())
        override suspend fun findCompleted(fileSha256: String) = all.value.firstOrNull { it.fileSha256 == fileSha256 && !it.reverted }
        override suspend fun insert(batch: ImportBatch) { all.value = all.value + batch }
        override suspend fun markReverted(id: String) { all.value = all.value.map { if (it.id == id) it.copy(reverted = true) else it } }
        override fun observeAll(): Flow<List<ImportBatch>> = all
    }

    /** Snapshot/rollback of the in-memory ledger, like a database transaction. */
    private class Tx(private val store: InMemoryStore) : TransactionRunner {
        override suspend fun <T> run(block: suspend () -> T): T {
            val before = LinkedHashMap(store.entries)
            return try {
                block()
            } catch (e: Throwable) {
                store.entries.clear()
                store.entries.putAll(before)
                throw e
            }
        }
    }

    @Test
    fun `import records valid rows atomically, skips duplicates, and can be undone`() = runTest {
        val store = InMemoryStore()
        store.accountRepo.create(Account("bank", "Bank", AccountType.BANK, "IRR", startDate = LocalDate.of(2026, 1, 1)), 10_000_000)
        val ids = SequentialIds()
        val record = RecordOperationUseCase(store.accountRepo, store.categoryRepo, store.currencyRepo, store.ledgerRepo, store.rateRepo, ids, FixedClock())
        record(IncomeExpenseDraft("old", EntryKind.EXPENSE, "bank", 77, LocalDate.of(2026, 9, 28), "cat-food", payee = "Known"))
        val batches = Batches()
        val import = ImportCsvUseCase(record, store.ledgerRepo, batches, Tx(store), ids, FixedClock())

        val rows = Csv.parse("date,amount,payee\n1405/07/01,-1000,Shop\n1405/07/03,5000,Salary\n1405/07/06,-77,Known\n1405/13/01,1,X\n")
        val plan = CsvImport.plan(rows, mapping, import.existingKeys("bank", CsvImport.plan(rows, mapping, emptySet())))
        val categoryFor = { r: ImportRow -> if (r.kind == EntryKind.EXPENSE) "cat-food" else "cat-salary" }
        val result = assertIs<Outcome.Success<*>>(import("bank.csv", "sha-1", "bank", plan, includeDuplicates = false, categoryFor = categoryFor))
        val r = result.value as com.mymoney.core.domain.usecase.ImportResult
        assertEquals(2, r.imported) // the duplicate and the invalid row are skipped
        assertEquals(10_000_000 - 77 - 1000 + 5000, store.balanceOf("bank"))
        assertEquals("sha-1", import.alreadyImported("sha-1")?.fileSha256)

        import.revert(r.batchId)
        assertEquals(10_000_000 - 77, store.balanceOf("bank"))
        assertNull(import.alreadyImported("sha-1"))

        // A row that fails validation in the ledger (unknown category) rolls back the whole import.
        val failing = import("bad.csv", "sha-2", "bank", plan, includeDuplicates = false) { "no-such-category" }
        assertIs<Outcome.Failure>(failing)
        assertEquals(10_000_000 - 77, store.balanceOf("bank"))
        assertNull(import.alreadyImported("sha-2"))
    }
}

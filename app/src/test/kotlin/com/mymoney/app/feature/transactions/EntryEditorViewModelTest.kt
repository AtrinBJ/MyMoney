package com.mymoney.app.feature.transactions

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mymoney.core.data.db.MyMoneyDatabase
import com.mymoney.core.data.db.SeedData
import com.mymoney.core.data.prefs.DataStoreSettingsRepository
import com.mymoney.core.data.repository.AccountRepositoryImpl
import com.mymoney.core.data.repository.CategoryRepositoryImpl
import com.mymoney.core.data.repository.ChangeLogger
import com.mymoney.core.data.repository.CurrencyRepositoryImpl
import com.mymoney.core.data.repository.ExchangeRateRepositoryImpl
import com.mymoney.core.data.repository.LedgerRepositoryImpl
import com.mymoney.core.data.repository.TagRepositoryImpl
import com.mymoney.core.domain.common.IdGenerator
import com.mymoney.core.domain.common.TimeProvider
import com.mymoney.core.domain.model.Account
import com.mymoney.core.domain.model.AccountType
import com.mymoney.core.domain.model.EntryKind
import com.mymoney.core.domain.usecase.FindPossibleDuplicatesUseCase
import com.mymoney.core.domain.usecase.RecordOperationUseCase
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Drives the editor the way the UI does: typed strings in, ledger rows out. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class EntryEditorViewModelTest {
    private lateinit var db: MyMoneyDatabase
    private lateinit var ledger: LedgerRepositoryImpl
    private lateinit var accounts: AccountRepositoryImpl
    private val clock = object : TimeProvider {
        var t = 1_760_000_000_000
        override fun nowMillis() = t++
        override fun today(): LocalDate = LocalDate.of(2026, 10, 13)
    }
    private val ids = object : IdGenerator {
        var n = 0
        override fun newId() = "id-${n++}"
    }
    private val prefs = object : DataStore<Preferences> {
        val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            transform(state.value).also { state.value = it }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MyMoneyDatabase::class.java)
            .addCallback(MyMoneyDatabase.SeedCallback)
            .allowMainThreadQueries()
            .build()
        val log = ChangeLogger(db.changeLogDao(), ids, clock)
        ledger = LedgerRepositoryImpl(db, log, clock)
        accounts = AccountRepositoryImpl(db, log, ids, clock)
        runBlocking {
            accounts.create(Account("bank", "Bank", AccountType.BANK, "IRR", startDate = LocalDate.of(2026, 1, 1)), 100_000_000)
            accounts.create(Account("eur", "Euro", AccountType.BANK, "EUR", startDate = LocalDate.of(2026, 1, 1)), 100_000)
        }
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun viewModel(args: Map<String, Any?>): EntryEditorViewModel = viewModel(SavedStateHandle(args))

    private fun viewModel(handle: SavedStateHandle): EntryEditorViewModel {
        val categories = CategoryRepositoryImpl(db, ChangeLogger(db.changeLogDao(), ids, clock), ids, clock)
        val currencies = CurrencyRepositoryImpl(db)
        val rates = ExchangeRateRepositoryImpl(db)
        return EntryEditorViewModel(
            savedStateHandle = handle,
            accounts = accounts,
            categories = categories,
            tagRepo = TagRepositoryImpl(db, ids, clock),
            currencies = currencies,
            ledger = ledger,
            settings = DataStoreSettingsRepository(prefs), // default: IRR in toman
            record = RecordOperationUseCase(accounts, categories, currencies, ledger, rates, ids, clock),
            findDuplicates = FindPossibleDuplicatesUseCase(ledger),
            ids = ids,
            clock = clock,
        )
    }

    private fun EntryEditorViewModel.awaitLoaded() = runBlocking {
        repeat(200) { if (!state.value.loading) return@runBlocking; Thread.sleep(10) }
    }

    private fun EntryEditorViewModel.awaitSaved() = runBlocking {
        repeat(300) { if (state.value.saved || state.value.error != null || state.value.duplicates.isNotEmpty()) return@runBlocking; Thread.sleep(10) }
    }

    @Test
    fun `typed toman amount is stored exactly as rial (FBR-22)`() {
        val vm = viewModel(mapOf("mode" to "EXPENSE", "accountId" to "bank"))
        vm.awaitLoaded()
        vm.setAmount("125000.5")
        vm.setCategory(SeedData.categoryId("expense.food"))
        vm.save()
        vm.awaitSaved()
        assertTrue(vm.state.value.saved, "error: ${vm.state.value.error}")
        val entry = runBlocking { ledger.getGroup(vm.state.value.groupId)!!.entries.single() }
        assertEquals(EntryKind.EXPENSE, entry.kind)
        assertEquals(-1_250_005, entry.amountMinor)
    }

    @Test
    fun `cross-currency transfer from the editor (FBR-03)`() {
        val vm = viewModel(mapOf("mode" to "TRANSFER", "accountId" to "eur"))
        vm.awaitLoaded()
        vm.setToAccount("bank")
        vm.setAmount("100") // €100.00
        assertTrue(vm.state.value.isCrossCurrency)
        vm.setAmountIn("10500000") // 10,500,000 toman = 105,000,000 rial
        // shown in toman: 10,500,000 toman / €100 = 105,000 toman per euro
        assertEquals(0, java.math.BigDecimal("105000").compareTo(vm.impliedRate(vm.state.value)))
        vm.save()
        vm.awaitSaved()
        assertTrue(vm.state.value.saved, "error: ${vm.state.value.error}")
        assertEquals(100_000 - 10_000, runBlocking { accounts.balance("eur") })
        assertEquals(100_000_000 + 105_000_000, runBlocking { accounts.balance("bank") })
    }

    @Test
    fun `duplicate warning then save anyway (FBR-36)`() {
        fun record(): EntryEditorViewModel {
            val vm = viewModel(mapOf("mode" to "EXPENSE", "accountId" to "bank"))
            vm.awaitLoaded()
            vm.setAmount("50000")
            vm.setCategory(SeedData.categoryId("expense.food"))
            vm.setPayee("Bakery")
            vm.save()
            vm.awaitSaved()
            return vm
        }
        val first = record()
        assertTrue(first.state.value.saved, "first: ${first.state.value}")
        val second = record()
        assertTrue(second.state.value.duplicates.isNotEmpty(), "second: saved=${second.state.value.saved} err=${second.state.value.error} dups=${second.state.value.duplicates}")
        second.save(force = true)
        second.awaitSaved()
        assertTrue(second.state.value.saved)
        assertEquals(100_000_000 - 1_000_000, runBlocking { accounts.balance("bank") })
    }

    @Test
    fun `editing an expense loads its values and replaces it`() {
        val vm = viewModel(mapOf("mode" to "EXPENSE", "accountId" to "bank"))
        vm.awaitLoaded()
        vm.setAmount("1000")
        vm.setCategory(SeedData.categoryId("expense.food"))
        vm.save()
        vm.awaitSaved()
        val groupId = vm.state.value.groupId

        val edit = viewModel(mapOf("groupId" to groupId))
        edit.awaitLoaded()
        assertEquals(EditorMode.EXPENSE, edit.state.value.mode)
        assertEquals("1000", edit.state.value.amount)
        assertTrue(edit.state.value.isEdit)
        edit.setAmount("2000")
        edit.save()
        edit.awaitSaved()
        assertEquals(-20_000, runBlocking { ledger.getGroup(groupId)!!.entries.single().amountMinor })
        assertEquals(1, runBlocking { db.entryDao().groupEntries(groupId).size })
        assertEquals(true, runBlocking { prefs.data.first() }.asMap().isEmpty())
    }

    @Test
    fun `a half-filled form survives process death with the same operation id`() {
        val handle = SavedStateHandle(mapOf("mode" to "EXPENSE", "accountId" to "bank"))
        val before = viewModel(handle)
        before.awaitLoaded()
        before.setAmount("42000")
        before.setCategory(SeedData.categoryId("expense.food"))
        before.setPayee("نانوایی")
        runBlocking { repeat(50) { if (handle.get<String>("editor-draft")?.contains("42000") == true) return@runBlocking; Thread.sleep(10) } }
        // The system recreates the ViewModel from the saved state only.
        val after = viewModel(handle)
        after.awaitLoaded()
        assertEquals(before.state.value.groupId, after.state.value.groupId)
        assertEquals("42000", after.state.value.amount)
        assertEquals("نانوایی", after.state.value.payee)
        assertEquals(SeedData.categoryId("expense.food"), after.state.value.categoryId)
    }
}

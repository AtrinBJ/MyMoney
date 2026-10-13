package com.mymoney.app.feature.transactions

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.mymoney.app.navigation.EntryEditorRoute
import com.mymoney.core.domain.common.DomainError
import com.mymoney.core.domain.common.IdGenerator
import com.mymoney.core.domain.common.Outcome
import com.mymoney.core.domain.common.TimeProvider
import com.mymoney.core.domain.model.Account
import com.mymoney.core.domain.model.Category
import com.mymoney.core.domain.model.CategoryType
import com.mymoney.core.domain.model.CostType
import com.mymoney.core.domain.model.Entry
import com.mymoney.core.domain.model.EntryGroup
import com.mymoney.core.domain.model.EntryKind
import com.mymoney.core.domain.model.EntryStatus
import com.mymoney.core.domain.model.SystemCategories
import com.mymoney.core.domain.model.Tag
import com.mymoney.core.domain.money.AmountParser
import com.mymoney.core.domain.money.Currency
import com.mymoney.core.domain.money.MoneyMath
import com.mymoney.core.domain.repository.AccountRepository
import com.mymoney.core.domain.repository.CategoryRepository
import com.mymoney.core.domain.repository.CurrencyRepository
import com.mymoney.core.domain.repository.LedgerRepository
import com.mymoney.core.domain.repository.TagRepository
import com.mymoney.core.domain.settings.IrrUnit
import com.mymoney.core.domain.settings.SettingsRepository
import com.mymoney.core.domain.usecase.AdjustmentDraft
import com.mymoney.core.domain.usecase.FindPossibleDuplicatesUseCase
import com.mymoney.core.domain.usecase.IncomeExpenseDraft
import com.mymoney.core.domain.usecase.OperationDraft
import com.mymoney.core.domain.usecase.RecordOperationUseCase
import com.mymoney.core.domain.usecase.RefundDraft
import com.mymoney.core.domain.usecase.SplitDraft
import com.mymoney.core.domain.usecase.TransferDraft
import dagger.hilt.android.lifecycle.HiltViewModel
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class EditorMode { EXPENSE, INCOME, TRANSFER, REFUND, ADJUSTMENT }

data class SplitRow(val categoryId: String? = null, val amount: String = "")

data class EditorState(
    val loading: Boolean = true,
    val isEdit: Boolean = false,
    val mode: EditorMode = EditorMode.EXPENSE,
    val groupId: String = "",
    val accounts: List<Account> = emptyList(),
    val categories: List<Category> = emptyList(),
    val tags: List<Tag> = emptyList(),
    val currencies: Map<String, Currency> = emptyMap(),
    val irrUnit: IrrUnit = IrrUnit.TOMAN,
    val today: LocalDate = LocalDate.now(),
    val accountId: String? = null,
    val toAccountId: String? = null,
    val amount: String = "",
    val amountIn: String = "",
    val fee: String = "",
    val categoryId: String? = null,
    val useSplit: Boolean = false,
    val splits: List<SplitRow> = listOf(SplitRow(), SplitRow()),
    val date: LocalDate = LocalDate.now(),
    val timeMinutes: Int? = null,
    val pending: Boolean = false,
    val payee: String = "",
    val note: String = "",
    val costType: CostType? = null,
    val essential: Boolean? = null,
    val selectedTags: Set<String> = emptySet(),
    val refundOf: Entry? = null,
    val refundableMinor: Long? = null,
    val adjustCurrentMinor: Long? = null,
    val reason: String = "",
    val error: DomainError? = null,
    val duplicates: List<Entry> = emptyList(),
    val saving: Boolean = false,
    val saved: Boolean = false,
) {
    val account: Account? get() = accounts.firstOrNull { it.id == accountId }
    val toAccount: Account? get() = accounts.firstOrNull { it.id == toAccountId }
    val isCrossCurrency: Boolean get() = mode == EditorMode.TRANSFER && account != null && toAccount != null && account!!.currency != toAccount!!.currency
    val categoryType: CategoryType get() = if (mode == EditorMode.INCOME) CategoryType.INCOME else CategoryType.EXPENSE
    val visibleCategories: List<Category> get() = categories.filter { it.type == categoryType && !it.isArchived }
}

@HiltViewModel
class EntryEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val accounts: AccountRepository,
    private val categories: CategoryRepository,
    private val tagRepo: TagRepository,
    private val currencies: CurrencyRepository,
    private val ledger: LedgerRepository,
    private val settings: SettingsRepository,
    private val record: RecordOperationUseCase,
    private val findDuplicates: FindPossibleDuplicatesUseCase,
    private val ids: IdGenerator,
    private val clock: TimeProvider,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<EntryEditorRoute>()
    private val _state = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = _state.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val s = settings.settings.first()
        val accs = accounts.getAll().filter { !it.isArchived && !it.type.isObligation }
        val cats = categories.getAll()
        val curr = currencies.getAll().associateBy { it.code }
        val today = clock.today()
        var st = EditorState(
            loading = false,
            mode = runCatching { EditorMode.valueOf(route.mode) }.getOrDefault(EditorMode.EXPENSE),
            groupId = route.groupId ?: ids.newId(),
            accounts = accs,
            categories = cats,
            tags = tagRepo.observeAll().first(),
            currencies = curr,
            irrUnit = s.irrUnit,
            today = today,
            date = today,
            accountId = route.accountId ?: accs.firstOrNull()?.id,
        )
        val sourceGroupId = route.groupId ?: route.copyOf
        if (sourceGroupId != null) {
            ledger.getGroup(sourceGroupId)?.let { st = fromGroup(st, it, isCopy = route.groupId == null) }
        }
        route.refundOf?.let { originalId ->
            val original = ledger.getEntry(originalId)
            if (original != null) {
                val refunded = ledger.refundedTotal(original.id, excludingGroupId = st.groupId)
                st = st.copy(
                    mode = EditorMode.REFUND,
                    refundOf = original,
                    refundableMinor = -original.amountMinor - refunded,
                    accountId = original.accountId,
                    categoryId = original.categoryId,
                    payee = original.payee.orEmpty(),
                )
            }
        }
        if (st.mode == EditorMode.ADJUSTMENT) st = st.copy(adjustCurrentMinor = currentBalanceFor(st))
        _state.value = st
    }

    private suspend fun fromGroup(base: EditorState, group: EntryGroup, isCopy: Boolean): EditorState {
        val legs = group.entries
        val transfer = legs.filter { it.kind == EntryKind.TRANSFER }
        val main = legs.first()
        var st = base.copy(
            isEdit = !isCopy,
            groupId = if (isCopy) base.groupId else group.groupId,
            date = if (isCopy) base.today else main.date,
            timeMinutes = if (isCopy) null else main.timeMinutes,
            pending = main.status == EntryStatus.PENDING,
            note = main.note.orEmpty(),
            selectedTags = main.tagIds,
        )
        if (transfer.size == 2) {
            val out = transfer.first { it.amountMinor < 0 }
            val inn = transfer.first { it.amountMinor > 0 }
            val fee = legs.firstOrNull { it.kind == EntryKind.EXPENSE }
            st = st.copy(
                mode = EditorMode.TRANSFER,
                accountId = out.accountId,
                toAccountId = inn.accountId,
                amount = st.display(-out.amountMinor, out.accountId),
                amountIn = st.display(inn.amountMinor, inn.accountId),
                fee = fee?.let { st.display(-it.amountMinor, it.accountId) }.orEmpty(),
                note = out.note.orEmpty(),
                selectedTags = out.tagIds,
            )
            return st
        }
        st = st.copy(accountId = main.accountId, payee = main.payee.orEmpty())
        return when (main.kind) {
            EntryKind.INCOME, EntryKind.EXPENSE -> st.copy(
                mode = if (main.kind == EntryKind.INCOME) EditorMode.INCOME else EditorMode.EXPENSE,
                amount = st.display(Math.abs(main.amountMinor), main.accountId),
                categoryId = main.categoryId,
                useSplit = main.splits.isNotEmpty(),
                splits = if (main.splits.isEmpty()) st.splits else main.splits.map { SplitRow(it.categoryId, st.display(it.amountMinor, main.accountId)) },
                costType = main.costType,
                essential = main.essential,
            )
            EntryKind.REFUND -> {
                val original = main.relatedEntryId?.let { ledger.getEntry(it) }
                st.copy(
                    mode = EditorMode.REFUND,
                    amount = st.display(main.amountMinor, main.accountId),
                    categoryId = main.categoryId,
                    refundOf = original,
                    refundableMinor = original?.let { -it.amountMinor - ledger.refundedTotal(it.id, excludingGroupId = group.groupId) },
                )
            }
            EntryKind.ADJUSTMENT -> st.copy(mode = EditorMode.ADJUSTMENT, reason = main.note.orEmpty(), note = "")
            else -> st
        }
    }

    private suspend fun currentBalanceFor(st: EditorState): Long? {
        val acc = st.accountId ?: return null
        val own = if (st.isEdit) ledger.getGroup(st.groupId)?.entries?.filter { it.accountId == acc && !it.date.isAfter(st.date) }?.sumOf { it.amountMinor } ?: 0 else 0
        return accounts.balance(acc, upTo = st.date) - own
    }

    // ---- intents ----
    fun setMode(mode: EditorMode) = _state.update {
        if (it.isEdit || it.refundOf != null) it else it.copy(mode = mode, categoryId = null, useSplit = false, error = null)
    }
    fun setAccount(id: String) {
        _state.update { it.copy(accountId = id, error = null) }
        if (_state.value.mode == EditorMode.ADJUSTMENT) refreshAdjustBalance()
    }
    fun setToAccount(id: String) = _state.update { it.copy(toAccountId = id, error = null) }
    fun setAmount(v: String) = _state.update {
        // Same-currency transfers keep both legs equal automatically.
        if (it.mode == EditorMode.TRANSFER && !it.isCrossCurrency) it.copy(amount = v, amountIn = v, error = null) else it.copy(amount = v, error = null)
    }
    fun setAmountIn(v: String) = _state.update { it.copy(amountIn = v, error = null) }
    fun setFee(v: String) = _state.update { it.copy(fee = v, error = null) }
    fun setCategory(id: String) = _state.update { s ->
        val cat = s.categories.firstOrNull { it.id == id }
        s.copy(
            categoryId = id,
            costType = s.costType ?: cat?.defaultCostType,
            essential = s.essential ?: cat?.defaultEssential,
            error = null,
        )
    }
    fun setUseSplit(v: Boolean) = _state.update { it.copy(useSplit = v, error = null) }
    fun setSplit(index: Int, row: SplitRow) = _state.update { s ->
        s.copy(splits = s.splits.toMutableList().also { it[index] = row }, error = null)
    }
    fun addSplit() = _state.update { it.copy(splits = it.splits + SplitRow()) }
    fun removeSplit(index: Int) = _state.update { s -> if (s.splits.size <= 2) s else s.copy(splits = s.splits.filterIndexed { i, _ -> i != index }) }
    fun setDate(d: LocalDate) {
        _state.update { it.copy(date = d, error = null) }
        if (_state.value.mode == EditorMode.ADJUSTMENT) refreshAdjustBalance()
    }
    fun setTime(m: Int?) = _state.update { it.copy(timeMinutes = m) }
    fun setPending(v: Boolean) = _state.update { it.copy(pending = v) }
    fun setPayee(v: String) = _state.update { it.copy(payee = v) }
    fun setNote(v: String) = _state.update { it.copy(note = v) }
    fun setReason(v: String) = _state.update { it.copy(reason = v, error = null) }
    fun setCostType(v: CostType?) = _state.update { it.copy(costType = v) }
    fun setEssential(v: Boolean?) = _state.update { it.copy(essential = v) }
    fun toggleTag(id: String) = _state.update { s ->
        s.copy(selectedTags = if (id in s.selectedTags) s.selectedTags - id else s.selectedTags + id)
    }
    fun addTag(name: String) {
        viewModelScope.launch {
            val tag = Tag(ids.newId(), name.trim())
            if (tagRepo.save(tag) is Outcome.Success) {
                _state.update { it.copy(tags = tagRepo.observeAll().first(), selectedTags = it.selectedTags + tag.id) }
            }
        }
    }
    fun dismissDuplicates() = _state.update { it.copy(duplicates = emptyList()) }

    private fun refreshAdjustBalance() {
        viewModelScope.launch { _state.update { it.copy(adjustCurrentMinor = currentBalanceFor(it)) } }
    }

    // ---- derived ----
    /**
     * Implied FX rate shown under a cross-currency transfer, in the units the user sees:
     * 1 source unit = rate target units (IRR shown as toman when that display unit is chosen).
     */
    fun impliedRate(s: EditorState): BigDecimal? {
        if (!s.isCrossCurrency) return null
        val out = parse(s, s.amount, s.accountId) ?: return null
        val inn = parse(s, s.amountIn, s.toAccountId) ?: return null
        if (out <= 0 || inn <= 0) return null
        val fromCur = s.account!!.currency
        val toCur = s.toAccount!!.currency
        val from = s.currencies[fromCur]?.minorDigits ?: return null
        val to = s.currencies[toCur]?.minorDigits ?: return null
        var outMajor = MoneyMath.toMajor(out, from)
        var inMajor = MoneyMath.toMajor(inn, to)
        if (s.irrUnit == IrrUnit.TOMAN) {
            if (fromCur == Currency.IRR) outMajor = outMajor.movePointLeft(1)
            if (toCur == Currency.IRR) inMajor = inMajor.movePointLeft(1)
        }
        return inMajor.divide(outMajor, MathContext(10, RoundingMode.HALF_EVEN))
    }

    fun splitRemaining(s: EditorState): Long? {
        val total = parse(s, s.amount, s.accountId) ?: return null
        val parts = s.splits.sumOf { parse(s, it.amount, s.accountId) ?: 0L }
        return total - parts
    }

    // ---- save ----
    fun save(force: Boolean = false) {
        val s = _state.value
        if (s.saving) return
        _state.update { it.copy(saving = true, error = null, duplicates = emptyList()) }
        viewModelScope.launch {
            val draft = when (val d = buildDraft(s)) {
                is Outcome.Failure -> {
                    _state.update { it.copy(saving = false, error = d.error) }
                    return@launch
                }
                is Outcome.Success -> d.value
            }
            if (!force && !s.isEdit) {
                val dups = duplicatesFor(draft)
                if (dups.isNotEmpty()) {
                    _state.update { it.copy(saving = false, duplicates = dups) }
                    return@launch
                }
            }
            when (val r = record(draft)) {
                is Outcome.Success -> _state.update { it.copy(saving = false, saved = true, duplicates = emptyList()) }
                is Outcome.Failure -> _state.update { it.copy(saving = false, error = r.error, duplicates = emptyList()) }
            }
        }
    }

    private suspend fun duplicatesFor(d: OperationDraft): List<Entry> = when (d) {
        is IncomeExpenseDraft -> findDuplicates(
            d.accountId, d.kind, if (d.kind == EntryKind.EXPENSE) -d.amountMinor else d.amountMinor, d.date, d.payee, d.groupId,
        )
        is RefundDraft -> findDuplicates(d.accountId, EntryKind.REFUND, d.amountMinor, d.date, d.payee, d.groupId)
        else -> emptyList()
    }

    private fun parse(s: EditorState, text: String, accountId: String?, allowNegative: Boolean = false): Long? {
        val acc = s.accounts.firstOrNull { it.id == accountId } ?: return null
        val digits = s.currencies[acc.currency]?.minorDigits ?: return null
        return (AmountParser.parse(text, acc.currency, digits, s.irrUnit, allowNegative) as? Outcome.Success)?.value
    }

    private fun parseOrError(s: EditorState, text: String, accountId: String?, allowNegative: Boolean = false): Outcome<Long> {
        val acc = s.accounts.firstOrNull { it.id == accountId } ?: return Outcome.Failure(DomainError.Required("account"))
        val digits = s.currencies[acc.currency]?.minorDigits ?: return Outcome.Failure(DomainError.NotFound("currency", acc.currency))
        return AmountParser.parse(text, acc.currency, digits, s.irrUnit, allowNegative)
    }

    private fun buildDraft(s: EditorState): Outcome<OperationDraft> {
        val accountId = s.accountId ?: return Outcome.Failure(DomainError.Required("account"))
        val status = if (s.pending) EntryStatus.PENDING else EntryStatus.COMPLETED
        return when (s.mode) {
            EditorMode.EXPENSE, EditorMode.INCOME -> {
                val amount = parseOrError(s, s.amount, accountId).let { if (it is Outcome.Failure) return it; (it as Outcome.Success).value }
                val splits = if (s.useSplit) {
                    s.splits.filter { it.amount.isNotBlank() || it.categoryId != null }.map { row ->
                        val part = parseOrError(s, row.amount, accountId).let { if (it is Outcome.Failure) return it; (it as Outcome.Success).value }
                        SplitDraft(row.categoryId ?: return Outcome.Failure(DomainError.Required("category")), part)
                    }
                } else {
                    emptyList()
                }
                val kind = if (s.mode == EditorMode.INCOME) EntryKind.INCOME else EntryKind.EXPENSE
                Outcome.Success(
                    IncomeExpenseDraft(
                        groupId = s.groupId, kind = kind, accountId = accountId, amountMinor = amount, date = s.date,
                        categoryId = if (s.useSplit) null else s.categoryId, splits = splits, timeMinutes = s.timeMinutes,
                        status = status, payee = s.payee, note = s.note,
                        costType = if (kind == EntryKind.EXPENSE) s.costType else null,
                        essential = if (kind == EntryKind.EXPENSE) s.essential else null,
                        tagIds = s.selectedTags,
                    ),
                )
            }
            EditorMode.TRANSFER -> {
                val to = s.toAccountId ?: return Outcome.Failure(DomainError.Required("account"))
                val out = parseOrError(s, s.amount, accountId).let { if (it is Outcome.Failure) return it; (it as Outcome.Success).value }
                val inn = if (s.isCrossCurrency) {
                    parseOrError(s, s.amountIn, to).let { if (it is Outcome.Failure) return it; (it as Outcome.Success).value }
                } else {
                    out
                }
                val fee = if (s.fee.isBlank()) 0L else parseOrError(s, s.fee, accountId).let { if (it is Outcome.Failure) return it; (it as Outcome.Success).value }
                Outcome.Success(
                    TransferDraft(
                        groupId = s.groupId, fromAccountId = accountId, toAccountId = to, amountOutMinor = out, amountInMinor = inn,
                        date = s.date, feeMinor = fee, timeMinutes = s.timeMinutes, status = status, note = s.note, tagIds = s.selectedTags,
                    ),
                )
            }
            EditorMode.REFUND -> {
                val amount = parseOrError(s, s.amount, accountId).let { if (it is Outcome.Failure) return it; (it as Outcome.Success).value }
                Outcome.Success(
                    RefundDraft(
                        groupId = s.groupId, accountId = accountId, amountMinor = amount, date = s.date,
                        originalEntryId = s.refundOf?.id, categoryId = s.categoryId, timeMinutes = s.timeMinutes,
                        status = status, payee = s.payee, note = s.note, tagIds = s.selectedTags,
                    ),
                )
            }
            EditorMode.ADJUSTMENT -> {
                val actual = parseOrError(s, s.amount, accountId, allowNegative = true).let { if (it is Outcome.Failure) return it; (it as Outcome.Success).value }
                Outcome.Success(AdjustmentDraft(s.groupId, accountId, actual, s.date, s.reason, s.note))
            }
        }
    }

    companion object {
        /** Fee leg uses the protected fees category; exposed for the UI hint. */
        const val FEES_KEY = SystemCategories.FEES
    }
}

/** Minor units → canonical editable string in the unit the user types (toman for IRR when chosen). */
internal fun EditorState.display(minor: Long, accountId: String): String {
    val currency = accounts.firstOrNull { it.id == accountId }?.currency ?: return ""
    val digits = currencies[currency]?.minorDigits ?: 2
    var major = BigDecimal.valueOf(minor, digits)
    if (currency == Currency.IRR && irrUnit == IrrUnit.TOMAN) major = major.movePointLeft(1)
    return major.stripTrailingZeros().toPlainString()
}

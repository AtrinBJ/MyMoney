package com.mymoney.app.feature.planning

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mymoney.app.data.FinanceSnapshot
import com.mymoney.app.data.FinanceSnapshotProvider
import com.mymoney.core.domain.calc.Amortization
import com.mymoney.core.domain.calc.BudgetCalculator
import com.mymoney.core.domain.calc.BudgetStatus
import com.mymoney.core.domain.calc.DueStatus
import com.mymoney.core.domain.calc.GoalProgress
import com.mymoney.core.domain.calc.GoalProjection
import com.mymoney.core.domain.calc.Occurrence
import com.mymoney.core.domain.calc.Recurrence
import com.mymoney.core.domain.calendar.DateRange
import com.mymoney.core.domain.common.DomainError
import com.mymoney.core.domain.common.IdGenerator
import com.mymoney.core.domain.common.Outcome
import com.mymoney.core.domain.model.GoalMode
import com.mymoney.core.domain.model.GoalStatus
import com.mymoney.core.domain.model.RuleKind
import com.mymoney.core.domain.model.RuleStatus
import com.mymoney.core.domain.repository.LedgerAnalytics
import com.mymoney.core.domain.usecase.PayOccurrenceUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PlanningState(
    val loading: Boolean = true,
    val snapshot: FinanceSnapshot? = null,
    val due: List<Occurrence> = emptyList(),
    val budgets: List<BudgetStatus> = emptyList(),
    val subscriptionsMonthlyBase: Long = 0,
    val subscriptionCount: Int = 0,
    val goals: List<GoalProgress> = emptyList(),
)

/** Shared helpers for budget/goal evaluation so dashboard, planning and reports agree (FBR-39). */
object PlanningMath {
    suspend fun budgetStatuses(s: FinanceSnapshot, analytics: LedgerAnalytics, date: LocalDate): List<BudgetStatus> {
        val active = s.budgets.filter { !it.isArchived }
        if (active.isEmpty()) return emptyList()
        val calc = BudgetCalculator(s.categories, s.rateBook, s.settings.weekStart)
        val from = active.minOf { calc.rowsNeededFrom(it, date) }
        val to = active.maxOf { calc.periodOf(it, date).endInclusive }
        val rows = analytics.budgetRows(from, to)
        return active.map { calc.status(it, date, rows) }
    }

    fun goalProgress(s: FinanceSnapshot): List<GoalProgress> = s.goals.filter { it.status != GoalStatus.ARCHIVED }.map { g ->
        val linked = if (g.mode == GoalMode.LINKED_ACCOUNTS) {
            g.accountIds.sumOf { id ->
                s.accountsById[id]?.let { a -> s.rateBook.convert(a.balanceMinor, a.account.currency, g.currency, s.today)?.minor } ?: 0
            }
        } else {
            null
        }
        GoalProjection.progress(g, s.contributions.filter { it.goalId == g.id }, linked, s.today)
    }

    fun subscriptionsMonthlyBase(s: FinanceSnapshot): Long = s.rules
        .filter { it.kind == RuleKind.SUBSCRIPTION && it.status == RuleStatus.ACTIVE }
        .sumOf { r -> s.toBase(Recurrence.monthlyEquivalent(r, Recurrence.amountOn(r, s.today)), r.currency) ?: 0 }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlanningViewModel @Inject constructor(
    provider: FinanceSnapshotProvider,
    private val analytics: LedgerAnalytics,
    private val payOccurrence: PayOccurrenceUseCase,
    private val ids: IdGenerator,
) : ViewModel() {
    val state: StateFlow<PlanningState> = provider.snapshot.mapLatest { s ->
        val window = DateRange(s.today.minusDays(90), s.today.plusDays(30))
        val due = s.occurrences(window).filter { it.status == DueStatus.OVERDUE || it.status == DueStatus.OPEN || it.status == DueStatus.PARTIAL }
            .filter { it.status == DueStatus.OVERDUE || !it.dueDate.isAfter(s.today.plusDays(30)) }
        PlanningState(
            loading = false,
            snapshot = s,
            due = due.sortedWith(compareBy<Occurrence> { it.status != DueStatus.OVERDUE }.thenBy { it.dueDate }),
            budgets = PlanningMath.budgetStatuses(s, analytics, s.today),
            subscriptionsMonthlyBase = PlanningMath.subscriptionsMonthlyBase(s),
            subscriptionCount = s.rules.count { it.kind == RuleKind.SUBSCRIPTION && it.status == RuleStatus.ACTIVE },
            goals = PlanningMath.goalProgress(s),
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlanningState())

    private val _payError = MutableStateFlow<DomainError?>(null)
    val payError: StateFlow<DomainError?> = _payError.asStateFlow()

    /** Suggested interest part for a loan installment (FBR-34), editable by the user. */
    fun suggestedInterest(o: Occurrence, amountMinor: Long): Long {
        val s = state.value.snapshot ?: return 0
        if (o.rule.kind != RuleKind.LOAN_INSTALLMENT) return 0
        val loanId = o.rule.toAccountId ?: return 0
        val ob = s.obligations.firstOrNull { it.accountId == loanId } ?: return 0
        val outstanding = -(s.accountsById[loanId]?.balanceMinor ?: 0)
        return Amortization.suggestSplit(amountMinor, outstanding, ob.annualRatePercent, ob.interestMethod).second
    }

    fun pay(o: Occurrence, amountMinor: Long, interestMinor: Long, date: LocalDate, onDone: () -> Unit) {
        viewModelScope.launch {
            when (val r = payOccurrence(o.rule, o.dueDate, ids.newId(), amountMinor, date, interestMinor)) {
                is Outcome.Success -> { _payError.value = null; onDone() }
                is Outcome.Failure -> _payError.value = r.error
            }
        }
    }

    fun skip(o: Occurrence) {
        viewModelScope.launch { payOccurrence.skip(o.rule, o.dueDate) }
    }

    fun clearError() { _payError.value = null }
}

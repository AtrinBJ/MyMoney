package com.mymoney.app.data

import com.mymoney.core.domain.calc.AssetCalculator
import com.mymoney.core.domain.calc.AssetHolding
import com.mymoney.core.domain.calc.NetWorthCalculator
import com.mymoney.core.domain.calc.NetWorthSummary
import com.mymoney.core.domain.calc.Occurrence
import com.mymoney.core.domain.calc.OversellException
import com.mymoney.core.domain.calc.RateBook
import com.mymoney.core.domain.calc.Recurrence
import com.mymoney.core.domain.calendar.DateRange
import com.mymoney.core.domain.common.TimeProvider
import com.mymoney.core.domain.model.AccountWithBalance
import com.mymoney.core.domain.model.Asset
import com.mymoney.core.domain.model.AssetTrade
import com.mymoney.core.domain.model.AssetValuation
import com.mymoney.core.domain.model.Budget
import com.mymoney.core.domain.model.Category
import com.mymoney.core.domain.model.ExchangeRate
import com.mymoney.core.domain.model.GoalContribution
import com.mymoney.core.domain.model.Obligation
import com.mymoney.core.domain.model.OccurrenceState
import com.mymoney.core.domain.model.RecurringRule
import com.mymoney.core.domain.model.SavingsGoal
import com.mymoney.core.domain.money.Currency
import com.mymoney.core.domain.repository.AccountRepository
import com.mymoney.core.domain.repository.AssetRepository
import com.mymoney.core.domain.repository.BudgetRepository
import com.mymoney.core.domain.repository.CategoryRepository
import com.mymoney.core.domain.repository.CurrencyRepository
import com.mymoney.core.domain.repository.ExchangeRateRepository
import com.mymoney.core.domain.repository.GoalRepository
import com.mymoney.core.domain.repository.ObligationRepository
import com.mymoney.core.domain.repository.RecurringRepository
import com.mymoney.core.domain.settings.AppSettings
import com.mymoney.core.domain.settings.SettingsRepository
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.shareIn

/**
 * Everything the planning, wealth and report screens need, assembled once. Derived values are lazy so a
 * screen only pays for what it reads. Money calculations come from the domain calculators (FBR-39).
 */
class FinanceSnapshot(
    val today: LocalDate,
    val settings: AppSettings,
    val currencies: Map<String, Currency>,
    val rates: List<ExchangeRate>,
    val accounts: List<AccountWithBalance>,
    val categories: List<Category>,
    val budgets: List<Budget>,
    val rules: List<RecurringRule>,
    val states: List<OccurrenceState>,
    val paid: Map<Pair<String, LocalDate>, Long>,
    val obligations: List<Obligation>,
    val assets: List<Asset>,
    val trades: List<AssetTrade>,
    val valuations: List<AssetValuation>,
    val goals: List<SavingsGoal>,
    val contributions: List<GoalContribution>,
) {
    val base: String get() = settings.baseCurrency
    val rateBook: RateBook by lazy { RateBook(rates, currencies, base) }
    val accountsById: Map<String, AccountWithBalance> by lazy { accounts.associateBy { it.account.id } }
    val categoriesById: Map<String, Category> by lazy { categories.associateBy { it.id } }

    /** Holdings per asset; an inconsistent trade history is reported as null quantity rather than crashing. */
    val holdings: List<AssetHolding> by lazy {
        val tradesBy = trades.groupBy { it.assetId }
        val valuationsBy = valuations.groupBy { it.assetId }
        assets.mapNotNull { a ->
            try {
                AssetCalculator.holding(a, tradesBy[a.id].orEmpty(), valuationsBy[a.id].orEmpty(), currencies[a.currency]?.minorDigits ?: 2)
            } catch (_: OversellException) {
                null
            }
        }
    }

    /** Asset values converted to base; assets without a valuation or rate are left out (reported elsewhere). */
    val assetValuesBase: List<Long> by lazy {
        holdings.filter { it.asset.includeInNetWorth && !it.asset.isArchived }.mapNotNull { h ->
            h.valueMinor?.let { rateBook.convert(it, h.asset.currency, base, today)?.minor }
        }
    }

    val netWorth: NetWorthSummary by lazy { NetWorthCalculator.compute(accounts, rateBook, base, today, assetValuesBase) }

    private val statesBy by lazy { states.groupBy { it.ruleId }.mapValues { (_, l) -> l.associateBy { it.dueDate } } }

    fun occurrences(window: DateRange): List<Occurrence> = rules.flatMap { rule ->
        val paidForRule = paid.filterKeys { it.first == rule.id }.mapKeys { it.key.second }
        Recurrence.occurrences(rule, window, today, statesBy[rule.id].orEmpty(), paidForRule)
    }.sortedBy { it.dueDate }

    fun toBase(minor: Long, currency: String, date: LocalDate = today): Long? = rateBook.convert(minor, currency, base, date)?.minor
}

@Singleton
class FinanceSnapshotProvider @Inject constructor(
    settings: SettingsRepository,
    currencies: CurrencyRepository,
    rates: ExchangeRateRepository,
    accounts: AccountRepository,
    categories: CategoryRepository,
    budgets: BudgetRepository,
    recurring: RecurringRepository,
    obligations: ObligationRepository,
    assets: AssetRepository,
    goals: GoalRepository,
    clock: TimeProvider,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Suppress("UNCHECKED_CAST")
    val snapshot: Flow<FinanceSnapshot> = combine(
        listOf(
            settings.settings, currencies.observeAll(), rates.observeAll(), accounts.observeWithBalances(includeArchived = true),
            categories.observe(includeArchived = true), budgets.observeAll(includeArchived = true), recurring.observeAll(),
            recurring.observeStates(), recurring.observePaidAmounts(), obligations.observeAll(), assets.observeAssets(),
            assets.observeTrades(), assets.observeValuations(), goals.observeGoals(), goals.observeContributions(),
        ),
    ) { v ->
        FinanceSnapshot(
            today = clock.today(),
            settings = v[0] as AppSettings,
            currencies = (v[1] as List<Currency>).associateBy { it.code },
            rates = v[2] as List<ExchangeRate>,
            accounts = v[3] as List<AccountWithBalance>,
            categories = v[4] as List<Category>,
            budgets = v[5] as List<Budget>,
            rules = v[6] as List<RecurringRule>,
            states = v[7] as List<OccurrenceState>,
            paid = v[8] as Map<Pair<String, LocalDate>, Long>,
            obligations = v[9] as List<Obligation>,
            assets = v[10] as List<Asset>,
            trades = v[11] as List<AssetTrade>,
            valuations = v[12] as List<AssetValuation>,
            goals = v[13] as List<SavingsGoal>,
            contributions = v[14] as List<GoalContribution>,
        )
    }
        .flowOn(Dispatchers.Default)
        .shareIn(scope, SharingStarted.WhileSubscribed(5_000), replay = 1)
}

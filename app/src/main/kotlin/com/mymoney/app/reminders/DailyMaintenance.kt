package com.mymoney.app.reminders

import com.mymoney.app.data.FinanceSnapshot
import com.mymoney.app.feature.planning.PlanningMath
import com.mymoney.core.domain.calc.Amortization
import com.mymoney.core.domain.calc.DueStatus
import com.mymoney.core.domain.calendar.DateRange
import com.mymoney.core.domain.common.Outcome
import com.mymoney.core.domain.common.TimeProvider
import com.mymoney.core.domain.model.PostingPolicy
import com.mymoney.core.domain.model.RuleKind
import com.mymoney.core.domain.model.RuleStatus
import com.mymoney.core.domain.repository.LedgerAnalytics
import com.mymoney.core.domain.repository.LedgerRepository
import com.mymoney.core.domain.repository.NotificationLogRepository
import com.mymoney.core.domain.usecase.PayOccurrenceUseCase
import java.time.LocalDate
import javax.inject.Inject

/** What the daily run wants to tell the user. Text is produced by [Notifier] in the user's language. */
sealed interface Reminder {
    val key: String

    data class Due(override val key: String, val ruleName: String, val dueDate: LocalDate, val amountMinor: Long, val currency: String, val income: Boolean) : Reminder
    data class Overdue(override val key: String, val count: Int) : Reminder
    data class BudgetThreshold(override val key: String, val budgetName: String, val percent: Int) : Reminder
    data class AutoPosted(override val key: String, val count: Int) : Reminder
}

interface Notifier {
    /** False when the user turned notifications off for the app (or the permission was denied). */
    fun canNotify(): Boolean

    fun show(reminders: List<Reminder>)
}

data class MaintenanceReport(val purgedEntries: Int, val autoPosted: Int, val notified: Int)

/**
 * The once-a-day job (docs/02 §Background work). Idempotent: running it twice on the same day posts and
 * notifies nothing new, because every action is keyed (occurrence unique index, notification log).
 */
class DailyMaintenance @Inject constructor(
    private val ledger: LedgerRepository,
    private val analytics: LedgerAnalytics,
    private val log: NotificationLogRepository,
    private val payOccurrence: PayOccurrenceUseCase,
    private val clock: TimeProvider,
    private val attachments: com.mymoney.core.domain.repository.AttachmentCleaner,
) {
    suspend fun run(s: FinanceSnapshot, notifier: Notifier): MaintenanceReport {
        val now = clock.nowMillis()
        val purged = ledger.purgeDeletedBefore(now - TRASH_DAYS * DAY_MS)
        if (purged > 0) attachments.purgeOrphans()
        log.purgeOlderThan(now - LOG_KEEP_DAYS * DAY_MS)

        val posted = autoPost(s)
        val out = mutableListOf<Reminder>()
        if (posted > 0) out += Reminder.AutoPosted("auto-${s.today}", posted)

        if (s.settings.remindersEnabled) {
            out += dueReminders(s)
            out += budgetReminders(s)
        }
        val fresh = out.filter { !log.wasSent(it.key) }
        if (fresh.isEmpty() || !notifier.canNotify()) return MaintenanceReport(purged, posted, 0)
        notifier.show(fresh)
        fresh.forEach { log.markSent(it.key, now) }
        return MaintenanceReport(purged, posted, fresh.size)
    }

    /**
     * Rules the user explicitly set to AUTO_POST are recorded on their due date (never earlier), for the
     * expected amount. A deterministic group id makes a second run a no-op (FBR-19 idempotency).
     */
    private suspend fun autoPost(s: FinanceSnapshot): Int {
        val auto = s.rules.filter { it.status == RuleStatus.ACTIVE && it.postingPolicy == PostingPolicy.AUTO_POST }
        if (auto.isEmpty()) return 0
        var count = 0
        s.occurrences(DateRange(s.today.minusDays(AUTO_POST_LOOKBACK_DAYS), s.today))
            .filter { it.rule.postingPolicy == PostingPolicy.AUTO_POST && it.rule.status == RuleStatus.ACTIVE }
            .filter { (it.status == DueStatus.OPEN || it.status == DueStatus.OVERDUE) && it.paidMinor == 0L && !it.dueDate.isAfter(s.today) }
            // A transfer between currencies needs the real amount on each side; leave it for the user to confirm.
            .filter { o -> o.rule.kind != RuleKind.TRANSFER || s.accountsById[o.rule.toAccountId]?.account?.currency == s.accountsById[o.rule.accountId]?.account?.currency }
            .forEach { o ->
                val interest = if (o.rule.kind == RuleKind.LOAN_INSTALLMENT) {
                    val ob = s.obligations.firstOrNull { it.accountId == o.rule.toAccountId }
                    val outstanding = -(o.rule.toAccountId?.let { s.accountsById[it]?.balanceMinor } ?: 0)
                    if (ob == null) 0 else Amortization.suggestSplit(o.expectedMinor, outstanding, ob.annualRatePercent, ob.interestMethod).second
                } else {
                    0
                }
                val r = payOccurrence(o.rule, o.dueDate, "auto-${o.rule.id}-${o.dueDate}", o.expectedMinor, o.dueDate, interest)
                if (r is Outcome.Success) count++
            }
        return count
    }

    private fun dueReminders(s: FinanceSnapshot): List<Reminder> {
        val out = mutableListOf<Reminder>()
        val window = DateRange(s.today.minusDays(OVERDUE_LOOKBACK_DAYS), s.today.plusDays(MAX_REMINDER_DAYS))
        val occ = s.occurrences(window).filter { it.rule.status == RuleStatus.ACTIVE && it.rule.postingPolicy == PostingPolicy.MANUAL_CONFIRM }
        occ.filter { it.status == DueStatus.OPEN || it.status == DueStatus.PARTIAL }.forEach { o ->
            val before = o.rule.reminderDaysBefore ?: return@forEach
            val remindOn = o.dueDate.minusDays(before.toLong())
            if (!s.today.isBefore(remindOn) && !s.today.isAfter(o.dueDate)) {
                out += Reminder.Due("due-${o.rule.id}-${o.dueDate}", o.rule.name, o.dueDate, o.outstandingMinor, o.rule.currency, o.rule.kind == RuleKind.INCOME)
            }
        }
        val overdue = occ.count { it.status == DueStatus.OVERDUE }
        // One overdue summary per day at most; the key includes the count so a new overdue item re-notifies.
        if (overdue > 0) out += Reminder.Overdue("overdue-${s.today}-$overdue", overdue)
        return out
    }

    private suspend fun budgetReminders(s: FinanceSnapshot): List<Reminder> = PlanningMath.budgetStatuses(s, analytics, s.today)
        .filter { it.budget.alertThresholds.isNotEmpty() && it.limitMinor > 0 }
        .mapNotNull { b ->
            val percent = (b.usedMinor * 100 / b.limitMinor).toInt()
            val crossed = b.budget.alertThresholds.filter { percent >= it }.maxOrNull() ?: return@mapNotNull null
            Reminder.BudgetThreshold("budget-${b.budget.id}-${b.period.start}-$crossed", b.budget.name, crossed)
        }

    companion object {
        const val TRASH_DAYS = 30L
        const val LOG_KEEP_DAYS = 400L
        const val AUTO_POST_LOOKBACK_DAYS = 60L
        const val OVERDUE_LOOKBACK_DAYS = 60L
        const val MAX_REMINDER_DAYS = 31L
        private const val DAY_MS = 86_400_000L
    }
}

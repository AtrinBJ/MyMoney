package com.mymoney.app.feature.planning

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mymoney.app.R
import com.mymoney.app.ui.common.DateField
import com.mymoney.app.ui.common.editableAmount
import com.mymoney.app.ui.common.errorMessage
import com.mymoney.app.ui.common.parseAmountOrNull
import com.mymoney.core.designsystem.component.AmountColor
import com.mymoney.core.designsystem.component.AmountField
import com.mymoney.core.designsystem.component.LoadingSkeleton
import com.mymoney.core.designsystem.component.MoneyText
import com.mymoney.core.designsystem.component.ProgressBar
import com.mymoney.core.designsystem.component.SectionCard
import com.mymoney.core.designsystem.format.LocalFormats
import com.mymoney.core.designsystem.theme.MyMoneyTheme
import com.mymoney.core.domain.calc.BudgetStatus
import com.mymoney.core.domain.calc.DueStatus
import com.mymoney.core.domain.calc.GoalProgress
import com.mymoney.core.domain.calc.Occurrence
import com.mymoney.core.domain.model.RuleKind
import java.time.LocalDate

@Composable
fun PlanningScreen(
    contentPadding: PaddingValues,
    onOpenBudget: (String?) -> Unit,
    onOpenRules: () -> Unit,
    onOpenRule: (String?) -> Unit,
    onOpenSubscriptions: () -> Unit,
    onOpenGoal: (String) -> Unit,
    onNewGoal: () -> Unit,
    onOpenTools: () -> Unit,
    viewModel: PlanningViewModel = hiltViewModel(),
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val spacing = MyMoneyTheme.spacing
    var paying by remember { mutableStateOf<Occurrence?>(null) }
    if (s.loading || s.snapshot == null) {
        LoadingSkeleton(Modifier.padding(contentPadding))
        return
    }
    val base = s.snapshot!!.base
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = spacing.screenPadding, end = spacing.screenPadding,
            top = contentPadding.calculateTopPadding() + spacing.s, bottom = contentPadding.calculateBottomPadding() + 32.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(spacing.cardGap),
    ) {
        item(key = "due") {
            SectionCard(title = stringResource(R.string.plan_upcoming), actionLabel = stringResource(R.string.plan_all_rules), onAction = onOpenRules) {
                if (s.due.isEmpty()) {
                    Text(stringResource(R.string.plan_upcoming_empty), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { onOpenRule(null) }) { Text(stringResource(R.string.rule_add)) }
                }
                s.due.take(8).forEach { o -> DueRow(o, onPay = { paying = o }, onSkip = { viewModel.skip(o) }) }
            }
        }
        item(key = "budgets") {
            SectionCard(title = stringResource(R.string.budget_title), actionLabel = stringResource(R.string.action_add), onAction = { onOpenBudget(null) }) {
                if (s.budgets.isEmpty()) Text(stringResource(R.string.budget_empty), style = MaterialTheme.typography.bodyMedium)
                s.budgets.forEach { b -> BudgetRow(b) { onOpenBudget(b.budget.id) } }
            }
        }
        item(key = "subs") {
            SectionCard(title = stringResource(R.string.sub_title), onClick = onOpenSubscriptions) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.sub_monthly_total, s.subscriptionCount), modifier = Modifier.weight(1f))
                    MoneyText(s.subscriptionsMonthlyBase, base, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        item(key = "goals") {
            SectionCard(title = stringResource(R.string.goal_title), actionLabel = stringResource(R.string.action_add), onAction = onNewGoal) {
                if (s.goals.isEmpty()) Text(stringResource(R.string.goal_empty), style = MaterialTheme.typography.bodyMedium)
                s.goals.forEach { g -> GoalRow(g) { onOpenGoal(g.goal.id) } }
            }
        }
        item(key = "tools") {
            SectionCard(title = stringResource(R.string.tools_title)) {
                ListItem(
                    modifier = Modifier.clickable(onClick = onOpenTools),
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = { Icon(Icons.Outlined.Calculate, null) },
                    headlineContent = { Text(stringResource(R.string.tools_loan)) },
                    supportingContent = { Text(stringResource(R.string.tools_subtitle)) },
                    trailingContent = { Icon(Icons.Outlined.ContentCut, null) },
                )
            }
        }
    }
    paying?.let { o -> PayDialog(o, viewModel) { paying = null; viewModel.clearError() } }
}

@Composable
fun DueRow(o: Occurrence, onPay: () -> Unit, onSkip: () -> Unit) {
    val formats = LocalFormats.current
    val overdue = o.status == DueStatus.OVERDUE
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(o.rule.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                formats.dates.medium(o.dueDate) + if (overdue) " · " + stringResource(R.string.plan_overdue) else if (o.status == DueStatus.PARTIAL) " · " + stringResource(R.string.plan_partial) else "",
                style = MaterialTheme.typography.bodySmall,
                color = if (overdue) MyMoneyTheme.finance.expense else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            MoneyText(
                o.outstandingMinor, o.rule.currency,
                color = if (o.rule.kind == RuleKind.INCOME) AmountColor.INCOME else AmountColor.NEUTRAL,
            )
            Row {
                TextButton(onClick = onSkip) { Text(stringResource(R.string.plan_skip)) }
                TextButton(onClick = onPay) {
                    Text(stringResource(if (o.rule.kind == RuleKind.INCOME) R.string.plan_receive else R.string.plan_pay))
                }
            }
        }
    }
}

@Composable
private fun BudgetRow(b: BudgetStatus, onClick: () -> Unit) {
    val formats = LocalFormats.current
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(b.budget.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            MoneyText(b.usedMinor, b.budget.currency, style = MaterialTheme.typography.bodyMedium)
            Text(" / ", style = MaterialTheme.typography.bodyMedium)
            MoneyText(b.limitMinor, b.budget.currency, style = MaterialTheme.typography.bodyMedium, withCurrency = false)
        }
        ProgressBar(b.fraction, Modifier.padding(vertical = 4.dp))
        Row {
            Text(
                if (b.remainingMinor >= 0) stringResource(R.string.budget_remaining, formats.money.format(b.remainingMinor, b.budget.currency))
                else stringResource(R.string.budget_over, formats.money.format(-b.remainingMinor, b.budget.currency)),
                style = MaterialTheme.typography.bodySmall,
                color = if (b.remainingMinor < 0) MyMoneyTheme.finance.expense else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (b.pendingMinor > 0) {
                Text(stringResource(R.string.budget_pending, formats.money.format(b.pendingMinor, b.budget.currency)), style = MaterialTheme.typography.bodySmall, color = MyMoneyTheme.finance.warning)
            }
        }
    }
}

@Composable
fun GoalRow(g: GoalProgress, onClick: () -> Unit) {
    val formats = LocalFormats.current
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(g.goal.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            MoneyText(g.currentMinor, g.goal.currency, style = MaterialTheme.typography.bodyMedium)
            Text(" / ", style = MaterialTheme.typography.bodyMedium)
            MoneyText(g.goal.targetMinor, g.goal.currency, style = MaterialTheme.typography.bodyMedium, withCurrency = false)
        }
        ProgressBar(g.fraction, Modifier.padding(vertical = 4.dp), warnAt = 2f)
        g.requiredPerMonthMinor?.let {
            Text(stringResource(R.string.goal_required_per_month, formats.money.format(it, g.goal.currency)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun PayDialog(o: Occurrence, vm: PlanningViewModel, onDismiss: () -> Unit) {
    val formats = LocalFormats.current
    val error by vm.payError.collectAsStateWithLifecycle()
    val isLoan = o.rule.kind == RuleKind.LOAN_INSTALLMENT
    var amount by rememberSaveable { mutableStateOf(formats.editableAmount(o.outstandingMinor, o.rule.currency)) }
    var interest by rememberSaveable {
        mutableStateOf(if (isLoan) formats.editableAmount(vm.suggestedInterest(o, o.outstandingMinor), o.rule.currency, blankZero = true) else "")
    }
    var date by rememberSaveable { mutableStateOf(LocalDate.now().toEpochDay()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(o.rule.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MyMoneyTheme.spacing.s)) {
                Text(stringResource(R.string.plan_due_on, formats.dates.medium(o.dueDate)), style = MaterialTheme.typography.bodySmall)
                AmountField(amount, { amount = it }, o.rule.currency, stringResource(R.string.editor_amount), Modifier.fillMaxWidth())
                if (isLoan) {
                    AmountField(interest, { interest = it }, o.rule.currency, stringResource(R.string.plan_interest_part), Modifier.fillMaxWidth(), supportingText = stringResource(R.string.plan_interest_hint))
                }
                DateField(stringResource(R.string.plan_paid_on), LocalDate.ofEpochDay(date), LocalDate.now(), { date = it.toEpochDay() }, Modifier.fillMaxWidth())
                error?.let { Text(errorMessage(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val a = formats.parseAmountOrNull(amount, o.rule.currency) ?: return@TextButton
                val i = if (isLoan) formats.parseAmountOrNull(interest, o.rule.currency) ?: 0 else 0
                vm.pay(o, a, i, LocalDate.ofEpochDay(date), onDismiss)
            }) { Text(stringResource(R.string.action_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

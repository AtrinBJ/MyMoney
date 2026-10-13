package com.mymoney.app.feature.planning

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mymoney.app.R
import com.mymoney.app.ui.common.BackTopBar
import com.mymoney.core.designsystem.component.LoadingSkeleton
import com.mymoney.core.designsystem.component.MoneyText
import com.mymoney.core.designsystem.format.LocalFormats
import com.mymoney.core.designsystem.theme.MyMoneyTheme
import com.mymoney.core.domain.calc.DueStatus
import com.mymoney.core.domain.calc.Recurrence
import com.mymoney.core.domain.model.RuleKind
import com.mymoney.core.domain.model.RuleStatus
import com.mymoney.core.domain.money.Digits
import java.time.DayOfWeek
import java.time.LocalDate

/** All recurring rules (list) and a month calendar of due dates with their status (docs/05 Bills & Recurring). */
@Composable
fun RulesScreen(onBack: () -> Unit, onOpenRule: (String?) -> Unit, viewModel: PlanningViewModel = hiltViewModel()) {
    val st by viewModel.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        topBar = { BackTopBar(stringResource(R.string.rules_title), onBack) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { onOpenRule(null) }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text(stringResource(R.string.rule_add)) })
        },
    ) { padding ->
        val s = st.snapshot
        if (s == null) { LoadingSkeleton(Modifier.padding(padding)); return@Scaffold }
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(tab == 0, { tab = 0 }, text = { Text(stringResource(R.string.rules_list)) })
                Tab(tab == 1, { tab = 1 }, text = { Text(stringResource(R.string.rules_calendar)) })
            }
            if (tab == 0) {
                val rules = s.rules.sortedWith(compareBy({ it.status != RuleStatus.ACTIVE }, { it.kind }, { it.name }))
                LazyColumn(Modifier.fillMaxSize()) {
                    if (rules.isEmpty()) item { Text(stringResource(R.string.plan_upcoming_empty), modifier = Modifier.padding(16.dp)) }
                    items(rules, key = { it.id }) { r ->
                        val next = Recurrence.dueDates(r, com.mymoney.core.domain.calendar.DateRange(s.today, s.today.plusYears(2))).firstOrNull()
                        ListItem(
                            modifier = Modifier.clickable { onOpenRule(r.id) },
                            headlineContent = { Text(r.name) },
                            supportingContent = {
                                Text(
                                    stringResource(ruleKindLabel(r.kind)) + " · " + stringResource(frequencyLabel(r.frequency)) +
                                        (next?.let { " · " + LocalFormats.current.dates.medium(it) } ?: "") +
                                        if (r.status != RuleStatus.ACTIVE) " · " + stringResource(if (r.status == RuleStatus.PAUSED) R.string.rule_paused else R.string.rule_ended) else "",
                                )
                            },
                            trailingContent = { MoneyText(Recurrence.amountOn(r, s.today), r.currency) },
                        )
                    }
                }
            } else {
                CalendarTab(viewModel)
            }
        }
    }
}

@Composable
private fun CalendarTab(viewModel: PlanningViewModel) {
    val st by viewModel.state.collectAsStateWithLifecycle()
    val s = st.snapshot ?: return
    val formats = LocalFormats.current
    val cal = formats.calendar
    var anchor by rememberSaveable { mutableLongStateOf(s.today.toEpochDay()) }
    val month = cal.monthRange(LocalDate.ofEpochDay(anchor))
    val occurrences = s.occurrences(month)
    val byDay = occurrences.groupBy { it.dueDate }
    var selected by rememberSaveable { mutableLongStateOf(s.today.toEpochDay()) }
    val weekStart = formats.settings.weekStart.isoDayOfWeek
    val offset = Math.floorMod(month.start.dayOfWeek.value - weekStart, 7)
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
            IconButton(onClick = { anchor = cal.plusMonths(month.start, -1, 1).toEpochDay() }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.action_previous_month)) }
            Text(formats.dates.monthYear(month.start), Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = { anchor = cal.plusMonths(month.start, 1, 1).toEpochDay() }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.action_next_month)) }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            (0 until 7).forEach {
                Text(formats.dates.weekdayName(DayOfWeek.of(((weekStart - 1 + it) % 7) + 1), short = true), Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium)
            }
        }
        val length = month.lengthInDays.toInt()
        val rows = (offset + length + 6) / 7
        Column(Modifier.padding(horizontal = 8.dp)) {
            for (r in 0 until rows) {
                Row(Modifier.fillMaxWidth()) {
                    for (c in 0 until 7) {
                        val i = r * 7 + c - offset
                        Box(Modifier.weight(1f).aspectRatio(1f), contentAlignment = Alignment.Center) {
                            if (i in 0 until length) {
                                val d = month.start.plusDays(i.toLong())
                                val items = byDay[d].orEmpty()
                                val isSel = d.toEpochDay() == selected
                                Column(
                                    Modifier.fillMaxSize().padding(2.dp).clip(MaterialTheme.shapes.small)
                                        .background(if (isSel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                                        .clickable { selected = d.toEpochDay() },
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    Text(Digits.localize(cal.toYmd(d).day.toString(), formats.settings.digits), style = MaterialTheme.typography.bodyMedium)
                                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                        items.take(3).forEach { o ->
                                            val color = when (o.status) {
                                                DueStatus.PAID -> MyMoneyTheme.finance.income
                                                DueStatus.OVERDUE -> MyMoneyTheme.finance.expense
                                                DueStatus.SKIPPED -> MaterialTheme.colorScheme.outline
                                                else -> if (o.rule.kind == RuleKind.INCOME) MyMoneyTheme.finance.income.copy(alpha = 0.5f) else MyMoneyTheme.finance.warning
                                            }
                                            Spacer(Modifier.size(6.dp).background(color, CircleShape))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        val dayItems = byDay[LocalDate.ofEpochDay(selected)].orEmpty()
        var paying by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<com.mymoney.core.domain.calc.Occurrence?>(null) }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            items(dayItems, key = { it.rule.id + it.dueDate }) { o ->
                if (o.status == DueStatus.PAID || o.status == DueStatus.SKIPPED) {
                    ListItem(headlineContent = { Text(o.rule.name) }, trailingContent = { MoneyText(o.expectedMinor, o.rule.currency) },
                        supportingContent = { Text(stringResource(if (o.status == DueStatus.PAID) R.string.plan_paid else R.string.plan_skipped)) })
                } else {
                    DueRow(o, onPay = { paying = o }, onSkip = { viewModel.skip(o) })
                }
            }
        }
        paying?.let { o -> PayDialog(o, viewModel) { paying = null; viewModel.clearError() } }
    }
}

package com.mymoney.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mymoney.core.designsystem.format.LocalFormats
import com.mymoney.core.domain.money.Digits
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Month-grid date picker in the user's calendar (Jalali or Gregorian) — Material's DatePicker is
 * Gregorian-only. Weeks start on the configured day.
 */
@Composable
fun CalendarDatePickerDialog(
    initial: LocalDate,
    today: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate) -> Unit,
    confirmLabel: String,
    cancelLabel: String,
    todayLabel: String,
    previousMonthLabel: String,
    nextMonthLabel: String,
) {
    val formats = LocalFormats.current
    val cal = formats.calendar
    var selectedEpoch by rememberSaveable { mutableStateOf(initial.toEpochDay()) }
    val start = cal.toYmd(initial)
    var shownYear by rememberSaveable { mutableStateOf(start.year) }
    var shownMonth by rememberSaveable { mutableStateOf(start.month) }
    var pickingYear by rememberSaveable { mutableStateOf(false) }
    val selected = LocalDate.ofEpochDay(selectedEpoch)

    fun shift(months: Int) {
        val d = cal.plusMonths(cal.toDate(shownYear, shownMonth, 1), months, 1)
        val ymd = cal.toYmd(d)
        shownYear = ymd.year
        shownMonth = ymd.month
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(formats.dates.long(selected), style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { shift(-1) }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = previousMonthLabel)
                    }
                    Text(
                        text = Digits.localize("${formats.dates.monthName(shownMonth)} $shownYear", formats.settings.digits),
                        style = MaterialTheme.typography.titleSmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .weight(1f)
                            .clickable(role = Role.Button) { pickingYear = !pickingYear }
                            .padding(vertical = 8.dp),
                    )
                    IconButton(onClick = { shift(1) }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = nextMonthLabel)
                    }
                }
                if (pickingYear) {
                    YearGrid(shownYear) { y -> shownYear = y; pickingYear = false }
                } else {
                    MonthGrid(shownYear, shownMonth, selected, today) { selectedEpoch = it.toEpochDay() }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(selected) }) { Text(confirmLabel) } },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    selectedEpoch = today.toEpochDay()
                    val ymd = cal.toYmd(today)
                    shownYear = ymd.year
                    shownMonth = ymd.month
                }) { Text(todayLabel) }
                TextButton(onClick = onDismiss) { Text(cancelLabel) }
            }
        },
    )
}

@Composable
private fun MonthGrid(year: Int, month: Int, selected: LocalDate, today: LocalDate, onPick: (LocalDate) -> Unit) {
    val formats = LocalFormats.current
    val cal = formats.calendar
    val weekStart = formats.settings.weekStart.isoDayOfWeek
    val first = cal.toDate(year, month, 1)
    val length = cal.monthLength(year, month)
    val offset = Math.floorMod(first.dayOfWeek.value - weekStart, 7)
    val days = (0 until 7).map { DayOfWeek.of(((weekStart - 1 + it) % 7) + 1) }

    Column {
        Row(Modifier.fillMaxWidth()) {
            days.forEach { d ->
                Text(
                    formats.dates.weekdayName(d, short = true),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val cells = offset + length
        val rows = (cells + 6) / 7
        for (r in 0 until rows) {
            Row(Modifier.fillMaxWidth()) {
                for (c in 0 until 7) {
                    val dayNum = r * 7 + c - offset + 1
                    Box(Modifier.weight(1f).aspectRatio(1f), contentAlignment = Alignment.Center) {
                        if (dayNum in 1..length) {
                            val date = first.plusDays((dayNum - 1).toLong())
                            DayCell(dayNum, date == selected, date == today) { onPick(date) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(day: Int, isSelected: Boolean, isToday: Boolean, onClick: () -> Unit) {
    val formats = LocalFormats.current
    val colors = MaterialTheme.colorScheme
    val label = Digits.localize(day.toString(), formats.settings.digits)
    var mod = Modifier
        .padding(2.dp)
        .fillMaxWidth()
        .aspectRatio(1f)
    mod = when {
        isSelected -> mod.background(colors.primary, CircleShape)
        isToday -> mod.border(1.dp, colors.primary, CircleShape)
        else -> mod
    }
    Box(
        mod
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.selected = isSelected; contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (isSelected) colors.onPrimary else colors.onSurface,
            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun YearGrid(current: Int, onPick: (Int) -> Unit) {
    val formats = LocalFormats.current
    val years = remember(current) { (current - 60..current + 20).toList() }
    val state = rememberLazyGridState(initialFirstVisibleItemIndex = (years.indexOf(current) - 6).coerceAtLeast(0))
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        state = state,
        modifier = Modifier.height(280.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(years) { y ->
            TextButton(onClick = { onPick(y) }) {
                Text(
                    Digits.localize(y.toString(), formats.settings.digits),
                    fontWeight = if (y == current) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

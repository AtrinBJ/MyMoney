package com.mymoney.core.domain.csv

import com.mymoney.core.domain.calendar.CalendarSystem
import com.mymoney.core.domain.common.AmountProblem
import com.mymoney.core.domain.common.DomainError
import com.mymoney.core.domain.common.Outcome
import com.mymoney.core.domain.model.EntryKind
import com.mymoney.core.domain.money.AmountParser
import com.mymoney.core.domain.money.Digits
import com.mymoney.core.domain.settings.CalendarType
import com.mymoney.core.domain.settings.IrrUnit
import com.mymoney.core.domain.usecase.FindPossibleDuplicatesUseCase
import java.time.LocalDate

enum class DateOrder { YMD, DMY, MDY }

/**
 * How the columns of a bank/spreadsheet export map to transactions (docs/05 Import CSV → Mapping).
 * Either one signed [amountCol] (negative = expense, unless [invertSign]) or separate [debitCol]/[creditCol].
 */
data class CsvMapping(
    val hasHeader: Boolean = true,
    val dateCol: Int,
    val amountCol: Int? = null,
    val debitCol: Int? = null,
    val creditCol: Int? = null,
    val payeeCol: Int? = null,
    val noteCol: Int? = null,
    val categoryCol: Int? = null,
    val calendar: CalendarType,
    val dateOrder: DateOrder = DateOrder.YMD,
    val accountId: String,
    val currency: String,
    val minorDigits: Int,
    /** Unit of the amounts in the file for IRR (bank exports are usually rial). */
    val irrUnit: IrrUnit = IrrUnit.RIAL,
    val invertSign: Boolean = false,
)

enum class RowProblem { DATE, DATE_RANGE, AMOUNT, ZERO, TOO_LARGE, COLUMNS }

data class ImportRow(
    /** 1-based line number in the file, for error reports. */
    val line: Int,
    val date: LocalDate?,
    val kind: EntryKind?,
    /** Positive amount in minor units of the account currency. */
    val amountMinor: Long,
    val payee: String?,
    val note: String?,
    val categoryText: String?,
    val problem: RowProblem? = null,
    /** Matches a transaction already in the account or an earlier row of the same file (FBR-36). */
    val duplicate: Boolean = false,
) {
    val valid: Boolean get() = problem == null
}

object CsvImport {
    private val MIN_DATE = LocalDate.of(1900, 1, 1)
    private val MAX_DATE = LocalDate.of(2200, 12, 31)

    /** Parses one date in the given calendar and field order; separators / - . and Persian digits allowed. */
    fun parseDate(text: String, calendar: CalendarType, order: DateOrder): LocalDate? {
        val parts = Digits.normalize(text).trim().split(Regex("[/\\-. ]+")).filter { it.isNotEmpty() }.take(3)
        if (parts.size != 3 || parts.any { p -> p.any { !it.isDigit() } }) return null
        val n = parts.map { it.toInt() }
        var (y, m, d) = when (order) {
            DateOrder.YMD -> Triple(n[0], n[1], n[2])
            DateOrder.DMY -> Triple(n[2], n[1], n[0])
            DateOrder.MDY -> Triple(n[2], n[0], n[1])
        }
        // Two-digit years: Jalali 00-99 → 1400s/1300s around the current century; Gregorian → 2000s/1900s.
        if (y < 100) y += if (calendar == CalendarType.JALALI) (if (y < 80) 1400 else 1300) else (if (y < 70) 2000 else 1900)
        val cal = CalendarSystem.of(calendar)
        return if (cal.isValid(y, m, d)) cal.toDate(y, m, d) else null
    }

    /** Suggests calendar and order from sample values: a leading 4-digit year → YMD; 13xx/14xx → Jalali. */
    fun guessDateFormat(samples: List<String>): Pair<CalendarType, DateOrder> {
        val parts = samples.mapNotNull { s -> Digits.normalize(s).trim().split(Regex("[/\\-. ]+")).takeIf { it.size >= 3 } }
        val first = parts.firstOrNull() ?: return CalendarType.JALALI to DateOrder.YMD
        val order = when {
            first[0].length == 4 -> DateOrder.YMD
            parts.any { (it[0].toIntOrNull() ?: 0) > 12 } -> DateOrder.DMY
            parts.any { (it[1].toIntOrNull() ?: 0) > 12 } -> DateOrder.MDY
            else -> DateOrder.DMY
        }
        val year = if (order == DateOrder.YMD) first[0] else first[2]
        val calendar = if ((year.toIntOrNull() ?: 0) in 1200..1599 || (year.length == 2 && year.toInt() in 0..20)) CalendarType.JALALI else CalendarType.GREGORIAN
        return calendar to order
    }

    /** Duplicate key of FBR-36 for CSV: (account, date, signed amount, normalized payee). */
    fun duplicateKey(date: LocalDate, signedMinor: Long, payee: String?): String =
        "${date.toEpochDay()}|$signedMinor|${FindPossibleDuplicatesUseCase.normalizePayee(payee)}"

    /**
     * Validates every data row (FBR-37). [existingKeys] are [duplicateKey]s of the target account's existing
     * transactions around the file's dates. Rows are never dropped silently: invalid ones carry a [RowProblem].
     */
    fun plan(rows: List<List<String>>, m: CsvMapping, existingKeys: Set<String>): List<ImportRow> {
        val seen = HashSet<String>()
        val start = if (m.hasHeader) 1 else 0
        return rows.drop(start).mapIndexed { i, r ->
            val line = i + start + 1
            fun cell(c: Int?) = c?.let { r.getOrNull(it) }?.trim()?.takeIf { it.isNotEmpty() }
            val payee = cell(m.payeeCol)
            val note = cell(m.noteCol)
            val category = cell(m.categoryCol)
            fun bad(p: RowProblem, date: LocalDate? = null) = ImportRow(line, date, null, 0, payee, note, category, p)

            if (r.size <= m.dateCol || (m.amountCol != null && r.size <= m.amountCol)) return@mapIndexed bad(RowProblem.COLUMNS)
            val dateText = cell(m.dateCol) ?: return@mapIndexed bad(RowProblem.DATE)
            val date = parseDate(dateText, m.calendar, m.dateOrder) ?: return@mapIndexed bad(RowProblem.DATE)
            if (date < MIN_DATE || date > MAX_DATE) return@mapIndexed bad(RowProblem.DATE_RANGE, date)

            fun amount(text: String?): Outcome<Long>? = text?.let { AmountParser.parse(it, m.currency, m.minorDigits, m.irrUnit, allowNegative = true) }
            val signed: Long = if (m.amountCol != null) {
                when (val a = amount(cell(m.amountCol)) ?: return@mapIndexed bad(RowProblem.AMOUNT, date)) {
                    is Outcome.Success -> if (m.invertSign) -a.value else a.value
                    is Outcome.Failure -> return@mapIndexed bad(
                        if ((a.error as? DomainError.InvalidAmount)?.reason == AmountProblem.TOO_LARGE) RowProblem.TOO_LARGE else RowProblem.AMOUNT,
                        date,
                    )
                }
            } else {
                val debit = amount(cell(m.debitCol))
                val credit = amount(cell(m.creditCol))
                if (debit is Outcome.Failure || credit is Outcome.Failure) return@mapIndexed bad(RowProblem.AMOUNT, date)
                val d = (debit as? Outcome.Success)?.value ?: 0
                val c = (credit as? Outcome.Success)?.value ?: 0
                if (debit == null && credit == null) return@mapIndexed bad(RowProblem.AMOUNT, date)
                kotlin.math.abs(c) - kotlin.math.abs(d)
            }
            if (signed == 0L) return@mapIndexed bad(RowProblem.ZERO, date)
            val key = duplicateKey(date, signed, payee)
            val dup = key in existingKeys || !seen.add(key)
            ImportRow(line, date, if (signed < 0) EntryKind.EXPENSE else EntryKind.INCOME, kotlin.math.abs(signed), payee, note, category, duplicate = dup)
        }
    }
}

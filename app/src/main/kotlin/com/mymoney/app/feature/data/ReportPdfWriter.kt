package com.mymoney.app.feature.data

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.res.ResourcesCompat
import com.mymoney.app.R
import com.mymoney.app.locale.LocaleController
import com.mymoney.app.ui.common.systemCategoryName
import com.mymoney.core.data.exchange.ExportFilter
import com.mymoney.core.data.repository.EntryFilter
import com.mymoney.core.data.repository.EntryQueries
import com.mymoney.core.designsystem.format.Formats
import com.mymoney.core.domain.calc.IncomeStatementCalculator
import com.mymoney.core.domain.calc.RateBook
import com.mymoney.core.domain.calendar.PeriodType
import com.mymoney.core.domain.common.TimeProvider
import com.mymoney.core.domain.model.EntryKind
import com.mymoney.core.domain.money.Digits
import com.mymoney.core.domain.repository.CategoryRepository
import com.mymoney.core.domain.repository.CurrencyRepository
import com.mymoney.core.domain.repository.ExchangeRateRepository
import com.mymoney.core.domain.repository.LedgerRepository
import com.mymoney.core.domain.settings.AppLanguage
import com.mymoney.core.domain.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.OutputStream
import java.time.LocalDate
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * A printable statement for a period (A4 PDF via android.graphics.pdf, no extra library): totals, spending by
 * category and the transaction list. Text uses Vazirmatn and StaticLayout, so Persian is shaped and laid out
 * right-to-left. Numbers come from the same calculators as the reports (FBR-39).
 */
class ReportPdfWriter @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val ledger: LedgerRepository,
    private val queries: EntryQueries,
    private val categories: CategoryRepository,
    private val currencies: CurrencyRepository,
    private val rates: ExchangeRateRepository,
    private val settings: SettingsRepository,
    private val locale: LocaleController,
    private val clock: TimeProvider,
) {
    suspend fun write(out: OutputStream, filter: ExportFilter): Int = withContext(Dispatchers.Default) {
        val s = settings.settings.first()
        val language = locale.current()
        val ctx = appContext.createConfigurationContext(Configuration(appContext.resources.configuration).apply { setLocale(Locale.forLanguageTag(language.tag)) })
        val currencyMap = currencies.getAll().associateBy { it.code }
        val f = Formats(s, language, currencyMap)
        val today = clock.today()
        val month = f.periods.rangeOf(PeriodType.MONTH, today)
        val from = filter.from ?: month.start
        val to = filter.to ?: month.endInclusive
        val book = RateBook(rates.getAll(), currencyMap, s.baseCurrency)
        val statement = IncomeStatementCalculator.compute(ledger.categoryAmounts(from, to), book, s.baseCurrency)
        val cats = categories.getAll().associateBy { it.id }
        // Same rule as the on-screen categoryLabel: a user-given name wins over the translated system name.
        fun label(systemKey: String?, name: String?): String =
            name?.takeIf { it.isNotBlank() } ?: systemCategoryName(systemKey)?.let { ctx.getString(it) } ?: ctx.getString(R.string.category_uncategorized)
        fun catName(id: String?): String {
            val c = id?.let { cats[it] } ?: return ctx.getString(R.string.category_uncategorized)
            return label(c.systemKey, c.name)
        }
        val rows = queries.list(EntryFilter(from = from, to = to), MAX_ROWS)
            .filter { filter.accountIds.isEmpty() || it.accountId in filter.accountIds }
            .sortedWith(compareBy({ it.date }, { it.createdAt }))

        val rtl = language == AppLanguage.FA
        val regular = ResourcesCompat.getFont(ctx, com.mymoney.core.designsystem.R.font.vazirmatn_ui_regular)
        val bold = ResourcesCompat.getFont(ctx, com.mymoney.core.designsystem.R.font.vazirmatn_ui_bold)
        fun paint(size: Float, b: Boolean = false, color: Int = Color.rgb(28, 32, 31)) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            typeface = if (b) bold else regular
            this.color = color
        }
        val doc = PdfDocument()
        var pageNo = 0
        var page: PdfDocument.Page? = null
        var canvas: Canvas? = null
        var y = 0f
        val left = 40f
        val width = PAGE_W - 80f

        fun finishPage() {
            page?.let {
                val footer = Digits.localize("${pageNo}", s.digits)
                text(it.canvas, footer, paint(9f, color = Color.GRAY), left, PAGE_H - 30f, width, Layout.Alignment.ALIGN_CENTER, rtl)
                doc.finishPage(it)
            }
        }
        fun newPage() {
            finishPage()
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
            canvas = page!!.canvas
            y = 40f
        }
        fun ensure(h: Float) { if (y + h > PAGE_H - 50f) newPage() }
        fun line(t: String, p: TextPaint, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL, gap: Float = 4f) {
            val h = measure(t, p, width, rtl)
            ensure(h)
            text(canvas!!, t, p, left, y, width, align, rtl)
            y += h + gap
        }
        /** Columns as fractions of the width, laid out right-to-left in Persian. */
        fun row(cells: List<String>, weights: List<Float>, p: TextPaint, ends: Set<Int> = emptySet()) {
            val h = cells.indices.maxOf { measure(cells[it], p, width * weights[it] - 6, rtl) }
            ensure(h)
            var x = if (rtl) left + width else left
            cells.forEachIndexed { i, c ->
                val w = width * weights[i]
                val cx = if (rtl) x - w else x
                val align = if (i in ends) Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_NORMAL
                text(canvas!!, c, p, cx + 3, y, w - 6, align, rtl)
                x = if (rtl) x - w else x + w
            }
            y += h + 3
            canvas!!.drawLine(left, y, left + width, y, Paint().apply { color = Color.rgb(225, 230, 228); strokeWidth = 0.5f })
            y += 3
        }

        newPage()
        line(ctx.getString(R.string.app_name) + " — " + ctx.getString(R.string.pdf_title), paint(18f, true))
        line(ctx.getString(R.string.pdf_period, f.dates.long(from), f.dates.long(to)), paint(11f, color = Color.DKGRAY))
        line(ctx.getString(R.string.pdf_generated, f.dates.long(today), s.baseCurrency.let { f.money.label(it) }), paint(9f, color = Color.GRAY), gap = 14f)

        line(ctx.getString(R.string.pdf_summary), paint(13f, true), gap = 6f)
        val body = paint(10.5f)
        row(listOf(ctx.getString(R.string.home_income), f.money.format(statement.incomeMinor, s.baseCurrency)), listOf(0.6f, 0.4f), body, setOf(1))
        row(listOf(ctx.getString(R.string.home_expense), f.money.format(statement.expenseMinor, s.baseCurrency)), listOf(0.6f, 0.4f), body, setOf(1))
        row(listOf(ctx.getString(R.string.home_net), f.money.format(statement.netMinor, s.baseCurrency)), listOf(0.6f, 0.4f), paint(10.5f, true), setOf(1))
        statement.savingsRatePercent?.let {
            row(listOf(ctx.getString(R.string.rep_savings_rate), Digits.localize(String.format(Locale.ROOT, "%.1f%%", it), s.digits)), listOf(0.6f, 0.4f), body, setOf(1))
        }
        if (statement.missing.isNotEmpty()) line(ctx.getString(R.string.rpt_missing_rates), paint(9f, color = Color.rgb(160, 90, 0)))
        y += 10

        val byCat = statement.byCategory.entries.groupBy({ it.key?.let { k -> cats[k]?.parentId ?: k } }, { it.value.netExpenseMinor })
            .mapValues { it.value.sum() }.filterValues { it > 0 }.entries.sortedByDescending { it.value }
        if (byCat.isNotEmpty()) {
            line(ctx.getString(R.string.pdf_by_category), paint(13f, true), gap = 6f)
            val total = byCat.sumOf { it.value }.toDouble()
            byCat.forEach { (id, v) ->
                row(
                    listOf(catName(id), Digits.localize(String.format(Locale.ROOT, "%.1f%%", v * 100 / total), s.digits), f.money.format(v, s.baseCurrency)),
                    listOf(0.5f, 0.15f, 0.35f), body, setOf(1, 2),
                )
            }
            y += 10
        }

        line(ctx.getString(R.string.pdf_transactions), paint(13f, true), gap = 6f)
        val head = paint(9.5f, true, Color.DKGRAY)
        val weights = listOf(0.17f, 0.38f, 0.2f, 0.25f)
        row(listOf(ctx.getString(R.string.pdf_col_date), ctx.getString(R.string.pdf_col_desc), ctx.getString(R.string.pdf_col_account), ctx.getString(R.string.pdf_col_amount)), weights, head, setOf(3))
        val small = paint(9.5f)
        rows.forEach { r ->
            val desc = listOfNotNull(
                r.payee,
                if (r.kind == EntryKind.TRANSFER) ctx.getString(R.string.kind_transfer) + (r.counterpartAccountName?.let { " → $it" } ?: "")
                else label(r.categorySystemKey, r.categoryName),
            ).joinToString(" · ")
            row(listOf(f.dates.medium(LocalDate.ofEpochDay(r.date)), desc, r.accountName, f.money.format(r.amountMinor, r.currency)), weights, small, setOf(3))
        }
        if (rows.isEmpty()) line(ctx.getString(R.string.rep_no_data), small)
        if (rows.size >= MAX_ROWS) line(ctx.getString(R.string.pdf_truncated, Digits.localize(MAX_ROWS.toString(), s.digits)), paint(9f, color = Color.GRAY))
        finishPage()
        out.use { doc.writeTo(it) }
        doc.close()
        rows.size
    }

    private fun layout(t: String, p: TextPaint, w: Float, rtl: Boolean, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL) =
        StaticLayout.Builder.obtain(t, 0, t.length, p, w.toInt().coerceAtLeast(10))
            .setAlignment(align)
            .setTextDirection(if (rtl) android.text.TextDirectionHeuristics.RTL else android.text.TextDirectionHeuristics.LTR)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setMaxLines(3)
            .build()

    private fun measure(t: String, p: TextPaint, w: Float, rtl: Boolean): Float = layout(t, p, w, rtl).height.toFloat()

    private fun text(c: Canvas, t: String, p: TextPaint, x: Float, y: Float, w: Float, align: Layout.Alignment, rtl: Boolean) {
        val l = layout(t, p, w, rtl, align)
        c.save()
        c.translate(x, y)
        l.draw(c)
        c.restore()
    }

    private companion object {
        const val PAGE_W = 595 // A4 at 72 dpi
        const val PAGE_H = 842
        const val MAX_ROWS = 5_000
    }
}

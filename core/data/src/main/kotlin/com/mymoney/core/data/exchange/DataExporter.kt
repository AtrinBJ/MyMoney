package com.mymoney.core.data.exchange

import android.util.JsonWriter
import com.mymoney.core.data.db.MyMoneyDatabase
import com.mymoney.core.domain.csv.Csv
import com.mymoney.core.domain.money.Currency
import com.mymoney.core.domain.settings.IrrUnit
import java.io.OutputStream
import java.math.BigDecimal
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What to export (docs/05 Export): a date range and optionally some accounts. */
data class ExportFilter(val from: LocalDate? = null, val to: LocalDate? = null, val accountIds: Set<String> = emptySet())

/** Localized labels the data layer cannot produce itself. */
class ExportLabels(
    val category: (systemKey: String?, name: String?) -> String,
    val kind: (String) -> String,
    val localDate: (LocalDate) -> String,
)

/**
 * Transaction export for spreadsheets (CSV, UTF-8 with BOM so Excel shows Persian correctly) and for other
 * tools (JSON). One row per leg, so each account's balance can be rebuilt; legs of one operation share
 * `group_id`. Amounts are plain numbers with a `.` decimal point and an explicit unit column (FBR-22).
 */
@Singleton
class DataExporter @Inject constructor(private val db: MyMoneyDatabase) {

    private data class Row(
        val id: String, val groupId: String, val date: Long, val time: Int?, val kind: String, val status: String,
        val amount: Long, val currency: String, val account: String, val categorySystemKey: String?, val categoryName: String?,
        val payee: String?, val note: String?, val tags: String?, val splits: String?,
    )

    private fun rows(f: ExportFilter): Sequence<Row> = sequence {
        val sql = StringBuilder(
            """SELECT e.id, e.groupId, e.date, e.timeMinutes, e.kind, e.status, e.amountMinor, a.currencyCode, a.name,
                      c.systemKey, c.name, e.payee, e.note,
                      (SELECT GROUP_CONCAT(t.name, ' | ') FROM entry_tag et JOIN tag t ON t.id = et.tagId WHERE et.entryId = e.id),
                      (SELECT GROUP_CONCAT(COALESCE(sc.name, sc.systemKey) || '=' || s.amountMinor, ' | ') FROM entry_split s
                         JOIN category sc ON sc.id = s.categoryId WHERE s.entryId = e.id)
               FROM entry e JOIN account a ON a.id = e.accountId LEFT JOIN category c ON c.id = e.categoryId
               WHERE e.deletedAt IS NULL""",
        )
        val args = mutableListOf<Any>()
        f.from?.let { sql.append(" AND e.date >= ?"); args += it.toEpochDay() }
        f.to?.let { sql.append(" AND e.date <= ?"); args += it.toEpochDay() }
        if (f.accountIds.isNotEmpty()) {
            sql.append(" AND e.accountId IN (${f.accountIds.joinToString { "?" }})")
            args.addAll(f.accountIds)
        }
        sql.append(" ORDER BY e.date, COALESCE(e.timeMinutes, 0), e.createdAt, e.id")
        db.openHelper.readableDatabase.query(sql.toString(), args.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                yield(
                    Row(
                        c.getString(0), c.getString(1), c.getLong(2), if (c.isNull(3)) null else c.getInt(3), c.getString(4), c.getString(5),
                        c.getLong(6), c.getString(7), c.getString(8), c.getString(9), c.getString(10), c.getString(11), c.getString(12),
                        c.getString(13), c.getString(14),
                    ),
                )
            }
        }
    }

    /** Amount in the unit the user works in: toman for IRR when chosen (exactly ÷10), else major units. */
    private fun amountText(minor: Long, currency: String, digits: Int, irrUnit: IrrUnit): Pair<String, String> {
        val tomanMode = currency == Currency.IRR && irrUnit == IrrUnit.TOMAN
        val value = BigDecimal.valueOf(minor).movePointLeft(if (tomanMode) 1 else digits).stripTrailingZeros()
        val unit = when {
            currency == Currency.IRR && tomanMode -> "toman"
            currency == Currency.IRR -> "rial"
            else -> currency
        }
        return value.toPlainString() to unit
    }

    suspend fun exportCsv(out: OutputStream, filter: ExportFilter, labels: ExportLabels, irrUnit: IrrUnit, minorDigits: Map<String, Int>): Int =
        withContext(Dispatchers.IO) {
            var n = 0
            // UTF-8 byte order mark so Excel opens Persian text correctly.
            out.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
            out.bufferedWriter(Charsets.UTF_8).use { w ->
                w.write(
                    Csv.line(
                        listOf(
                            "date", "local_date", "time", "account", "type", "category", "payee", "note", "amount", "unit", "currency",
                            "status", "tags", "splits", "group_id", "entry_id",
                        ),
                    ),
                )
                rows(filter).forEach { r ->
                    val date = LocalDate.ofEpochDay(r.date)
                    val (amount, unit) = amountText(r.amount, r.currency, minorDigits[r.currency] ?: 2, irrUnit)
                    w.write(
                        Csv.line(
                            listOf(
                                date.toString(), labels.localDate(date), r.time?.let { String.format(java.util.Locale.ROOT, "%02d:%02d", it / 60, it % 60) }, r.account,
                                labels.kind(r.kind), if (r.categorySystemKey != null || r.categoryName != null) labels.category(r.categorySystemKey, r.categoryName) else null,
                                r.payee, r.note, amount, unit, r.currency, r.status, r.tags, r.splits, r.groupId, r.id,
                            ),
                        ),
                    )
                    n++
                }
            }
            n
        }

    suspend fun exportJson(out: OutputStream, filter: ExportFilter, labels: ExportLabels): Int = withContext(Dispatchers.IO) {
        var n = 0
        JsonWriter(out.bufferedWriter(Charsets.UTF_8)).use { w ->
            w.setIndent("  ")
            w.beginObject()
            w.name("format").value("mymoney-transactions")
            w.name("version").value(1)
            w.name("amountUnit").value("minor units of the account currency (IRR: rial)")
            w.name("transactions").beginArray()
            rows(filter).forEach { r ->
                w.beginObject()
                w.name("id").value(r.id)
                w.name("groupId").value(r.groupId)
                w.name("date").value(LocalDate.ofEpochDay(r.date).toString())
                r.time?.let { w.name("timeMinutes").value(it.toLong()) }
                w.name("type").value(r.kind)
                w.name("status").value(r.status)
                w.name("account").value(r.account)
                w.name("currency").value(r.currency)
                w.name("amountMinor").value(r.amount)
                if (r.categorySystemKey != null || r.categoryName != null) w.name("category").value(labels.category(r.categorySystemKey, r.categoryName))
                r.payee?.let { w.name("payee").value(it) }
                r.note?.let { w.name("note").value(it) }
                r.tags?.let { w.name("tags").value(it) }
                r.splits?.let { w.name("splits").value(it) }
                w.endObject()
                n++
            }
            w.endArray()
            w.endObject()
        }
        n
    }
}

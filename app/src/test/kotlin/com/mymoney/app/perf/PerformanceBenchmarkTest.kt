package com.mymoney.app.perf

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mymoney.app.feature.dashboard.DashboardViewModel
import com.mymoney.app.feature.reports.PeriodKind
import com.mymoney.app.feature.reports.ReportBuilder
import com.mymoney.app.feature.reports.ReportType
import com.mymoney.app.screenshots.ScreenshotHarness
import com.mymoney.core.data.debug.DevDataSeeder
import com.mymoney.core.data.exchange.DataExporter
import com.mymoney.core.data.exchange.ExportFilter
import com.mymoney.core.data.exchange.ExportLabels
import com.mymoney.core.data.repository.EntryFilter
import com.mymoney.core.domain.settings.IrrUnit
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 100,000-transaction benchmark on the JVM (Robolectric + in-memory SQLite). Numbers are indicative only:
 * the phone uses SQLCipher on flash storage and a different CPU, so the real check is on the A55 (QA_CHECKLIST).
 * Run with: PERF=1 ./gradlew :app:testDebugUnitTest --tests "*PerformanceBenchmarkTest*"
 * Results: app/build/perf-report.txt
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class PerformanceBenchmarkTest {
    private lateinit var h: ScreenshotHarness

    @Before
    fun setUp() {
        Assume.assumeTrue("set PERF=1 to run the benchmark", System.getenv("PERF") == "1")
        Dispatchers.setMain(Dispatchers.Unconfined)
        h = ScreenshotHarness()
    }

    @After
    fun tearDown() {
        if (::h.isInitialized) h.close()
        Dispatchers.resetMain()
    }

    private val report = StringBuilder()

    private inline fun <T> time(name: String, repeat: Int = 3, block: () -> T): T {
        var result: T? = null
        val ms = (1..repeat).map {
            val t0 = System.nanoTime()
            result = block()
            (System.nanoTime() - t0) / 1_000_000
        }
        report.append(String.format(java.util.Locale.ROOT, "%-48s first %6d ms   median %6d ms%n", name, ms.first(), ms.sorted()[ms.size / 2]))
        return result!!
    }

    @Test
    fun benchmark100k() = runBlocking {
        h.settings.update { it.copy(onboardingDone = true) }
        val rows = time("seed 100,000 transactions", repeat = 1) { DevDataSeeder(h.db, h.accounts, h.clock).seedPerformanceData(100_000) }
        report.append("entry rows: $rows\n\n")

        val snapshot = time("finance snapshot (balances, rules, assets)") { h.provider.snapshot.first() }
        time("dashboard view model: first full state") {
            val vm = DashboardViewModel(h.provider, h.settings, h.ledger, h.analytics, h.queries)
            runBlocking {
                kotlinx.coroutines.withTimeout(60_000) { vm.state.first { !it.loading } }
            }
        }
        val reports = ReportBuilder(h.ledger, h.analytics)
        time("report: income/expense 12 months") { runBlocking { reports.build(snapshot, ReportType.INCOME_EXPENSE, PeriodKind.MONTH, 0) } }
        time("report: net worth 12 points + FX") { runBlocking { reports.build(snapshot, ReportType.NET_WORTH, PeriodKind.MONTH, 0) } }
        time("report: cash flow, one year") { runBlocking { reports.build(snapshot, ReportType.CASH_FLOW, PeriodKind.YEAR, 0) } }
        time("insights (13 months)") { runBlocking { reports.insights(snapshot) } }
        time("transactions: first page (80 rows)") { runBlocking { h.queries.list(EntryFilter(), 80) } }
        time("transactions: account filter, first page") { runBlocking { h.queries.list(EntryFilter(accountId = "perf-cash"), 80) } }
        time("search \"سوپر\" (50 rows)") { runBlocking { h.queries.list(EntryFilter(text = "سوپر"), 50) } }
        val labels = ExportLabels({ k, n -> n ?: k.orEmpty() }, { it }, { it.toString() })
        val n = time("CSV export of everything", repeat = 1) {
            runBlocking { DataExporter(h.db).exportCsv(NullOut, ExportFilter(), labels, IrrUnit.TOMAN, mapOf("IRR" to 0)) }
        }
        report.append("exported rows: $n\n")

        File("build").mkdirs()
        File("build/perf-report.txt").writeText(report.toString())
        println(report)
    }

    private object NullOut : OutputStream() {
        override fun write(b: Int) = Unit
        override fun write(b: ByteArray, off: Int, len: Int) = Unit
    }
}

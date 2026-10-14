package com.mymoney.app.screenshots

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mymoney.app.feature.dashboard.DashboardScreen
import com.mymoney.app.feature.dashboard.DashboardViewModel
import com.mymoney.app.feature.reports.InsightsViewModel
import com.mymoney.app.feature.reports.ReportsHomeScreen
import com.mymoney.app.feature.settings.SettingsContent
import com.mymoney.app.feature.settings.SettingsUiState
import com.mymoney.app.lock.AppLockManager
import com.mymoney.app.lock.LockScreen
import com.mymoney.app.lock.LockViewModel
import com.mymoney.core.data.db.SeedData
import com.mymoney.core.data.security.CryptoProvider
import com.mymoney.core.data.security.PinStore
import com.mymoney.core.domain.model.Account
import com.mymoney.core.domain.model.AccountType
import com.mymoney.core.domain.model.EntryKind
import com.mymoney.core.domain.settings.AppLanguage
import com.mymoney.core.domain.usecase.IncomeExpenseDraft
import java.time.LocalDate
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * docs/06 accessibility rules checked automatically: every tappable element is at least 48×48dp, and the main
 * screens still lay out at 200% font size on a narrow (320dp) phone (screenshots a11y_*.png for review).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccessibilityTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var h: ScreenshotHarness
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        h = ScreenshotHarness()
        runBlocking {
            h.settings.update { it.copy(onboardingDone = true) }
            h.accounts.create(Account("bank", "حساب جاری بانک ملی ایران شعبه مرکزی", AccountType.BANK, "IRR", startDate = LocalDate.of(2026, 1, 1)), 987_654_321_000)
            repeat(6) { i ->
                h.record(IncomeExpenseDraft("g$i", EntryKind.EXPENSE, "bank", 123_456_789L * (i + 1), LocalDate.of(2026, 10, 1).plusDays(i.toLong()), SeedData.categoryId("expense.food"), payee = "فروشگاه زنجیره‌ای بسیار بزرگ شماره ${i + 1}"))
            }
        }
    }

    @After
    fun tearDown() {
        h.close()
        Dispatchers.resetMain()
    }

    private fun settle() = repeat(3) { Thread.sleep(400); compose.waitForIdle() }

    /** Clickable nodes smaller than 48dp in either direction (Material components report their touch area). */
    private fun smallTargets(): List<String> {
        val density = compose.density
        return compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().mapNotNull { n ->
            if (n.boundsInRoot.isEmpty) return@mapNotNull null // scrolled out of the test window, not visible
            val w = with(density) { n.touchBoundsInRoot.width.toDp() }
            val hh = with(density) { n.touchBoundsInRoot.height.toDp() }
            if (w >= 47.5.dp && hh >= 47.5.dp) return@mapNotNull null
            val label = n.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
                ?: n.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text ?: "?"
            "$label (${w.value.toInt()}×${hh.value.toInt()}dp)"
        }
    }

    private fun check(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent { ScreenFrame(h.formats(AppLanguage.FA)) { content() } }
        settle()
        compose.saveScreenshot(name)
        val small = smallTargets()
        assertTrue(small.isEmpty(), "$name: touch targets under 48dp: $small")
    }

    @Test
    @Config(qualifiers = "fa-w320dp-h1600dp-xhdpi", fontScale = 2.0f)
    fun a11y_dashboard_fa_font200() {
        val vm = DashboardViewModel(h.provider, h.settings, h.ledger, h.analytics, h.queries)
        check("a11y_dashboard_fa_font200") { DashboardScreen(PaddingValues(), {}, {}, {}, {}, {}, {}, {}, {}, viewModel = vm) }
    }

    @Test
    @Config(qualifiers = "fa-w320dp-h2400dp-xhdpi", fontScale = 2.0f)
    fun a11y_settings_fa_font200() {
        val s = runBlocking { h.settings.settings.first() }
        check("a11y_settings_fa_font200") { SettingsContent(SettingsUiState(s, AppLanguage.FA, pinSet = true), {}, {}, {}) }
    }

    @Test
    @Config(qualifiers = "fa-w320dp-h1400dp-xhdpi", fontScale = 2.0f)
    fun a11y_reports_fa_font200() {
        val vm = InsightsViewModel(h.provider, h.ledger, h.analytics)
        check("a11y_reports_fa_font200") { ReportsHomeScreen(PaddingValues(), {}, {}, viewModel = vm) }
    }

    @Test
    @Config(qualifiers = "fa-w320dp-h640dp-xhdpi", fontScale = 1.3f)
    fun a11y_lock_small_screen() {
        val pins = PinStore(context, CryptoProvider(context), h.clock)
        val vm = LockViewModel(pins, AppLockManager(pins, h.settings, h.clock), h.clock, h.settings)
        check("a11y_lock_small_screen") { LockScreen(viewModel = vm) }
    }
}

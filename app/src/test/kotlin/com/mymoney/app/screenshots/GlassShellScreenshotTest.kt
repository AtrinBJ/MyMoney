package com.mymoney.app.screenshots

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mymoney.app.feature.dashboard.DashboardScreen
import com.mymoney.app.feature.dashboard.DashboardViewModel
import com.mymoney.app.navigation.TopLevelDestination
import com.mymoney.app.ui.GlassNavBar
import com.mymoney.app.ui.GlassTopBar
import com.mymoney.core.data.db.SeedData
import com.mymoney.core.designsystem.theme.AmbientBackground
import com.mymoney.core.designsystem.theme.backdropSource
import com.mymoney.core.designsystem.theme.rememberBackdrop
import com.mymoney.core.domain.model.Account
import com.mymoney.core.domain.model.AccountType
import com.mymoney.core.domain.model.EntryKind
import com.mymoney.core.domain.settings.AppLanguage
import com.mymoney.core.domain.usecase.IncomeExpenseDraft
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

/** The glass shell (blurred top bar, floating tab bar, content scrolling under them) in light, dark and solid. */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "fa-w411dp-h891dp-xxhdpi", sdk = [36])
class GlassShellScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var h: ScreenshotHarness

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        h = ScreenshotHarness()
        runBlocking {
            h.settings.update { it.copy(onboardingDone = true) }
            h.accounts.create(Account("bank", "بانک ملت", AccountType.BANK, "IRR", startDate = LocalDate.of(2026, 1, 1)), 850_000_000)
            repeat(8) { i ->
                h.record(IncomeExpenseDraft("g$i", EntryKind.EXPENSE, "bank", 4_500_000L + i * 700_000, LocalDate.of(2026, 10, 1).plusDays(i.toLong()), SeedData.categoryId("expense.food"), payee = "سوپرمارکت ${i + 1}"))
            }
            h.record(IncomeExpenseDraft("sal", EntryKind.INCOME, "bank", 450_000_000, LocalDate.of(2026, 10, 1), SeedData.categoryId("income.salary")))
        }
    }

    @After
    fun tearDown() {
        h.close()
        Dispatchers.resetMain()
    }

    private fun shell(name: String, dark: Boolean, glass: Boolean) {
        val vm = DashboardViewModel(h.provider, h.settings, h.ledger, h.analytics, h.queries)
        compose.setContent {
            ScreenFrame(h.formats(AppLanguage.FA), dark = dark, glass = glass) {
                val backdrop = rememberBackdrop()
                Scaffold(
                    containerColor = Color.Transparent,
                    topBar = {
                        GlassTopBar(backdrop) {
                            CenterAlignedTopAppBar(
                                title = { Text("خانه") },
                                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent),
                            )
                        }
                    },
                    bottomBar = { GlassNavBar(TopLevelDestination.HOME, {}, backdrop) },
                ) { padding ->
                    Box(Modifier.fillMaxSize().backdropSource(backdrop)) {
                        AmbientBackground()
                        DashboardScreen(padding, {}, {}, {}, {}, {}, {}, {}, {}, viewModel = vm)
                    }
                }
            }
        }
        repeat(3) { Thread.sleep(400); compose.waitForIdle() }
        compose.saveScreenshot(name)
    }

    @Test fun glass_shell_light() = shell("glass_shell_light_fa", dark = false, glass = true)

    @Test fun glass_shell_dark() = shell("glass_shell_dark_fa", dark = true, glass = true)

    @Test fun glass_shell_solid() = shell("glass_shell_solid_fa", dark = false, glass = false)
}

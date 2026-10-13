package com.mymoney.app.feature.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mymoney.core.designsystem.theme.MyMoneyTheme
import com.mymoney.core.domain.settings.AppLanguage
import com.mymoney.core.domain.settings.AppSettings
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
class SettingsContentTest {
    @get:Rule val compose = createComposeRule()

    @Test
    @Config(qualifiers = "fa")
    fun `persian RTL settings toggle secure screen`() {
        var settings = AppSettings(secureScreen = true)
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MyMoneyTheme {
                    SettingsContent(
                        state = SettingsUiState(settings = settings, language = AppLanguage.FA),
                        onBack = {},
                        onLanguage = {},
                        onUpdate = { transform -> settings = transform(settings) },
                    )
                }
            }
        }
        compose.onNodeWithText("تنظیمات").assertExists()
        compose.onNodeWithText("جلوگیری از اسکرین‌شات").performScrollTo().assertIsOn().performClick()
        assertEquals(false, settings.secureScreen)
    }

    @Test
    fun `english settings show current choices`() {
        compose.setContent {
            MyMoneyTheme {
                SettingsContent(
                    state = SettingsUiState(settings = AppSettings(secureScreen = false), language = AppLanguage.EN),
                    onBack = {},
                    onLanguage = {},
                    onUpdate = {},
                )
            }
        }
        compose.onNodeWithText("Toman").assertExists()
        compose.onNodeWithText("Solar Hijri (Jalali)").assertExists()
        compose.onNodeWithText("Block screenshots").assertIsOff()
    }
}

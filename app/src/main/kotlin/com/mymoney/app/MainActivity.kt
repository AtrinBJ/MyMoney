package com.mymoney.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mymoney.app.ui.MyMoneyRoot
import com.mymoney.core.designsystem.format.Formats
import com.mymoney.core.designsystem.format.LocalFormats
import com.mymoney.core.designsystem.theme.MyMoneyTheme
import com.mymoney.core.domain.settings.AppLanguage
import com.mymoney.core.domain.settings.AppSettings
import com.mymoney.core.domain.settings.ThemeMode
import com.mymoney.app.lock.AppLockManager
import com.mymoney.app.lock.LockScreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Single activity. Extends AppCompatActivity (a FragmentActivity) for per-app locales on API < 33
 * and for androidx.biometric's BiometricPrompt.
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    @Inject lateinit var lockManager: AppLockManager

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            val locked by lockManager.locked.collectAsStateWithLifecycle()
            val settings = state.settings

            // FLAG_SECURE hides content from screenshots and the recents preview (setting, default on).
            LaunchedEffect(settings?.secureScreen) {
                if (settings?.secureScreen != false) {
                    window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }

            val dark = when (settings?.themeMode ?: ThemeMode.SYSTEM) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // The resource configuration reflects the per-app locale chosen by the user.
            val language = AppLanguage.fromTag(LocalConfiguration.current.locales[0]?.language) ?: AppLanguage.EN
            val formats = remember(settings, state.currencies, language) {
                Formats(settings ?: AppSettings(), language, state.currencies)
            }
            MyMoneyTheme(darkTheme = dark, dynamicColor = settings?.dynamicColor == true, glass = settings?.glassEffects ?: true) {
                CompositionLocalProvider(LocalFormats provides formats) {
                    // Ambient colours behind onboarding, the lock screen and every screen without its own.
                    com.mymoney.core.designsystem.theme.AmbientBackground()
                    // While locked nothing of the app is composed, so no data can show behind the lock screen.
                    if (locked && state.settings?.onboardingDone == true) {
                        LockScreen()
                    } else {
                        MyMoneyRoot(state = state, onToggleHideAmounts = viewModel::toggleHideAmounts)
                    }
                }
            }
        }
    }
}

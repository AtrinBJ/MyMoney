package com.mymoney.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mymoney.app.ui.MyMoneyRoot
import com.mymoney.core.designsystem.theme.MyMoneyTheme
import com.mymoney.core.domain.settings.ThemeMode
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single activity. Extends AppCompatActivity (a FragmentActivity) for per-app locales on API < 33
 * and for androidx.biometric's BiometricPrompt.
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
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
            MyMoneyTheme(darkTheme = dark, dynamicColor = settings?.dynamicColor == true) {
                MyMoneyRoot(state = state)
            }
        }
    }
}

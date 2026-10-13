package com.mymoney.app.locale

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.mymoney.core.domain.settings.AppLanguage
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-app language. The platform (Android 13+) or AppCompat (older) persists the choice and
 * recreates the activity, so layout direction (RTL/LTR) follows automatically.
 */
interface LocaleController {
    fun current(): AppLanguage
    fun set(language: AppLanguage)
}

@Singleton
class AppCompatLocaleController @Inject constructor() : LocaleController {
    override fun current(): AppLanguage {
        val appLocales = AppCompatDelegate.getApplicationLocales()
        val tag = if (appLocales.isEmpty) Locale.getDefault().language else appLocales[0]?.language
        return AppLanguage.fromTag(tag) ?: AppLanguage.EN
    }

    override fun set(language: AppLanguage) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language.tag))
    }
}

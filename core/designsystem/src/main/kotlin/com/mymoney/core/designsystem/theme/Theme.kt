package com.mymoney.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightScheme = with(BrandPalette) {
    lightColorScheme(
        primary = PrimaryLight, onPrimary = OnPrimaryLight,
        primaryContainer = PrimaryContainerLight, onPrimaryContainer = OnPrimaryContainerLight,
        secondary = SecondaryLight, onSecondary = OnSecondaryLight,
        secondaryContainer = SecondaryContainerLight, onSecondaryContainer = OnSecondaryContainerLight,
        tertiary = TertiaryLight, onTertiary = OnTertiaryLight,
        tertiaryContainer = TertiaryContainerLight, onTertiaryContainer = OnTertiaryContainerLight,
        background = BackgroundLight, onBackground = OnBackgroundLight,
        surface = SurfaceLight, onSurface = OnSurfaceLight,
        surfaceVariant = SurfaceVariantLight, onSurfaceVariant = OnSurfaceVariantLight,
        surfaceContainerLowest = SurfaceContainerLowestLight, surfaceContainerLow = SurfaceContainerLowLight,
        surfaceContainer = SurfaceContainerLight, surfaceContainerHigh = SurfaceContainerHighLight,
        surfaceContainerHighest = SurfaceContainerHighestLight,
        outline = OutlineLight, outlineVariant = OutlineVariantLight,
        error = ErrorLight, onError = OnErrorLight,
        errorContainer = ErrorContainerLight, onErrorContainer = OnErrorContainerLight,
    )
}

private val DarkScheme = with(BrandPalette) {
    darkColorScheme(
        primary = PrimaryDark, onPrimary = OnPrimaryDark,
        primaryContainer = PrimaryContainerDark, onPrimaryContainer = OnPrimaryContainerDark,
        secondary = SecondaryDark, onSecondary = OnSecondaryDark,
        secondaryContainer = SecondaryContainerDark, onSecondaryContainer = OnSecondaryContainerDark,
        tertiary = TertiaryDark, onTertiary = OnTertiaryDark,
        tertiaryContainer = TertiaryContainerDark, onTertiaryContainer = OnTertiaryContainerDark,
        background = BackgroundDark, onBackground = OnBackgroundDark,
        surface = SurfaceDark, onSurface = OnSurfaceDark,
        surfaceVariant = SurfaceVariantDark, onSurfaceVariant = OnSurfaceVariantDark,
        surfaceContainerLowest = SurfaceContainerLowestDark, surfaceContainerLow = SurfaceContainerLowDark,
        surfaceContainer = SurfaceContainerDark, surfaceContainerHigh = SurfaceContainerHighDark,
        surfaceContainerHighest = SurfaceContainerHighestDark,
        outline = OutlineDark, outlineVariant = OutlineVariantDark,
        error = ErrorDark, onError = OnErrorDark,
        errorContainer = ErrorContainerDark, onErrorContainer = OnErrorContainerDark,
    )
}

@Composable
fun MyMoneyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    /** "Glass effects" setting: ambient background, translucent surfaces, blurred bars. */
    glass: Boolean = false,
    content: @Composable () -> Unit,
) {
    val base: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkScheme
        else -> LightScheme
    }
    // With glass, screens and plain surfaces are see-through so the ambient background shows; dialogs, sheets and
    // menus keep their opaque container colours.
    val colorScheme = if (glass) base.copy(background = Color.Transparent, surface = Color.Transparent) else base
    CompositionLocalProvider(
        LocalFinanceColors provides if (darkTheme) DarkFinanceColors else LightFinanceColors,
        LocalSpacing provides Spacing(),
        LocalGlass provides GlassStyle(enabled = glass, dark = darkTheme),
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = MyMoneyTypography,
            shapes = MyMoneyShapes,
            content = content,
        )
    }
}

/** Convenience accessors: `MyMoneyTheme.finance.income`, `MyMoneyTheme.spacing.l`. */
object MyMoneyTheme {
    val finance: FinanceColors
        @Composable @ReadOnlyComposable get() = LocalFinanceColors.current
    val spacing: Spacing
        @Composable @ReadOnlyComposable get() = LocalSpacing.current
    val glass: GlassStyle
        @Composable @ReadOnlyComposable get() = LocalGlass.current
}

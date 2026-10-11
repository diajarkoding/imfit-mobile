package com.diajarkoding.imfit.theme

import android.app.Activity
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryContainer,
    onPrimaryContainer = PrimaryDark,

    secondary = Primary,
    onSecondary = OnPrimary,
    secondaryContainer = PrimaryContainer,
    onSecondaryContainer = PrimaryDark,

    tertiary = Success,
    onTertiary = OnSuccess,
    tertiaryContainer = SuccessContainer,
    onTertiaryContainer = Success,

    background = BackgroundLight,
    onBackground = OnBackgroundLight,

    surface = SurfaceLight,
    onSurface = OnSurfaceLight,

    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariantLight,

    error = Error,
    onError = OnError,
    errorContainer = ErrorContainer,
    onErrorContainer = Error,

    outline = OutlineLight,
    outlineVariant = OutlineVariantLight
)

private val DarkColorScheme = darkColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryDark,
    onPrimaryContainer = OnPrimary,

    secondary = Primary,
    onSecondary = OnPrimary,
    secondaryContainer = PrimaryDark,
    onSecondaryContainer = OnPrimary,

    tertiary = Success,
    onTertiary = OnSuccess,
    tertiaryContainer = Success,
    onTertiaryContainer = SuccessContainer,

    background = BackgroundDark,
    onBackground = OnBackgroundDark,

    surface = SurfaceDark,
    onSurface = OnSurfaceDark,

    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,

    error = Error,
    onError = OnError,
    errorContainer = Error,
    onErrorContainer = ErrorContainer,

    outline = OutlineDark,
    outlineVariant = OutlineVariantDark
)

@Composable
private fun animatedColorScheme(darkTheme: Boolean): ColorScheme {
    val transition = updateTransition(
        targetState = darkTheme,
        label = "themeTransition"
    )
    val darkThemeFraction by transition.animateFloat(
        transitionSpec = { tween(IMFITMotion.ThemeTransitionDurationMillis) },
        label = "darkThemeFraction"
    ) { isDark ->
        if (isDark) 1f else 0f
    }

    val targetColorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    return targetColorScheme.copy(
        primaryContainer = lerp(LightColorScheme.primaryContainer, DarkColorScheme.primaryContainer, darkThemeFraction),
        onPrimaryContainer = lerp(LightColorScheme.onPrimaryContainer, DarkColorScheme.onPrimaryContainer, darkThemeFraction),
        secondaryContainer = lerp(LightColorScheme.secondaryContainer, DarkColorScheme.secondaryContainer, darkThemeFraction),
        onSecondaryContainer = lerp(LightColorScheme.onSecondaryContainer, DarkColorScheme.onSecondaryContainer, darkThemeFraction),
        tertiaryContainer = lerp(LightColorScheme.tertiaryContainer, DarkColorScheme.tertiaryContainer, darkThemeFraction),
        onTertiaryContainer = lerp(LightColorScheme.onTertiaryContainer, DarkColorScheme.onTertiaryContainer, darkThemeFraction),
        background = lerp(LightColorScheme.background, DarkColorScheme.background, darkThemeFraction),
        onBackground = lerp(LightColorScheme.onBackground, DarkColorScheme.onBackground, darkThemeFraction),
        surface = lerp(LightColorScheme.surface, DarkColorScheme.surface, darkThemeFraction),
        onSurface = lerp(LightColorScheme.onSurface, DarkColorScheme.onSurface, darkThemeFraction),
        surfaceVariant = lerp(LightColorScheme.surfaceVariant, DarkColorScheme.surfaceVariant, darkThemeFraction),
        onSurfaceVariant = lerp(LightColorScheme.onSurfaceVariant, DarkColorScheme.onSurfaceVariant, darkThemeFraction),
        errorContainer = lerp(LightColorScheme.errorContainer, DarkColorScheme.errorContainer, darkThemeFraction),
        onErrorContainer = lerp(LightColorScheme.onErrorContainer, DarkColorScheme.onErrorContainer, darkThemeFraction),
        outline = lerp(LightColorScheme.outline, DarkColorScheme.outline, darkThemeFraction),
        outlineVariant = lerp(LightColorScheme.outlineVariant, DarkColorScheme.outlineVariant, darkThemeFraction),
        inverseSurface = lerp(LightColorScheme.inverseSurface, DarkColorScheme.inverseSurface, darkThemeFraction),
        inverseOnSurface = lerp(LightColorScheme.inverseOnSurface, DarkColorScheme.inverseOnSurface, darkThemeFraction),
        inversePrimary = lerp(LightColorScheme.inversePrimary, DarkColorScheme.inversePrimary, darkThemeFraction),
        surfaceDim = lerp(LightColorScheme.surfaceDim, DarkColorScheme.surfaceDim, darkThemeFraction),
        surfaceBright = lerp(LightColorScheme.surfaceBright, DarkColorScheme.surfaceBright, darkThemeFraction),
        surfaceContainerLowest = lerp(LightColorScheme.surfaceContainerLowest, DarkColorScheme.surfaceContainerLowest, darkThemeFraction),
        surfaceContainerLow = lerp(LightColorScheme.surfaceContainerLow, DarkColorScheme.surfaceContainerLow, darkThemeFraction),
        surfaceContainer = lerp(LightColorScheme.surfaceContainer, DarkColorScheme.surfaceContainer, darkThemeFraction),
        surfaceContainerHigh = lerp(LightColorScheme.surfaceContainerHigh, DarkColorScheme.surfaceContainerHigh, darkThemeFraction),
        surfaceContainerHighest = lerp(LightColorScheme.surfaceContainerHighest, DarkColorScheme.surfaceContainerHighest, darkThemeFraction)
    )
}

@Composable
fun IMFITTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = animatedColorScheme(darkTheme)
    val view = LocalView.current

    if (!view.isInEditMode) {
        LaunchedEffect(darkTheme) {
            val window = (view.context as Activity).window
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = Shapes,
        content = content
    )
}

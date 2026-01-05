package com.diajarkoding.imfit.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
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
fun IMFITTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
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

package com.diajarkoding.imfit.theme


import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryBlue,
    onPrimary = TextOnPrimaryDark,
    primaryContainer = PrimaryBlueDark,
    onPrimaryContainer = TextPrimaryDark,

    secondary = AccentTeal,
    onSecondary = TextOnPrimaryDark,
    secondaryContainer = AccentTealDark,
    onSecondaryContainer = TextPrimaryDark,

    tertiary = AccentTealLight,
    onTertiary = TextPrimaryDark,

    background = BackgroundPrimaryDark,
    onBackground = TextPrimaryDark,

    surface = SurfaceDark,
    onSurface = TextPrimaryDark,

    surfaceVariant = BackgroundSecondaryDark,
    onSurfaceVariant = TextSecondaryDark,

    error = ErrorRed,
    onError = TextOnPrimaryDark,

    outline = DividerDark,
    outlineVariant = DisabledGrayDark
)

private val LightColorScheme = lightColorScheme(
    primary = PrimaryBlue,
    onPrimary = TextOnPrimaryLight,
    primaryContainer = PrimaryBlueLight,
    onPrimaryContainer = TextPrimaryLight,

    secondary = AccentTeal,
    onSecondary = TextOnPrimaryLight,
    secondaryContainer = AccentTealLight,
    onSecondaryContainer = TextPrimaryLight,

    tertiary = AccentTealDark,
    onTertiary = TextOnPrimaryLight,

    background = BackgroundPrimaryLight,
    onBackground = TextPrimaryLight,

    surface = SurfaceLight,
    onSurface = TextPrimaryLight,

    surfaceVariant = BackgroundSecondaryLight,
    onSurfaceVariant = TextSecondaryLight,

    error = ErrorRed,
    onError = TextOnPrimaryLight,

    outline = DividerLight,
    outlineVariant = DisabledGrayLight,

    // Custom colors for specific use cases
    inverseSurface = TextPrimaryLight,
    inverseOnSurface = BackgroundPrimaryLight,
    inversePrimary = PrimaryBlueLight,

    surfaceTint = PrimaryBlue,
    scrim = CardShadowLight
)

@Composable
fun IMFITTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = false, // Disabled to use custom colors
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            val insetsController = WindowCompat.getInsetsController(window, view)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                // Android 15+ - Use edge-to-edge but handle insets properly
                WindowCompat.setDecorFitsSystemWindows(window, false)
                window.statusBarColor = Color.Transparent.toArgb()
                window.navigationBarColor = Color.Transparent.toArgb()

                // Dark icons for better visibility
                insetsController.isAppearanceLightStatusBars = !darkTheme
                insetsController.isAppearanceLightNavigationBars = !darkTheme

            } else {
                // Android 14 and below - Use traditional approach
                WindowCompat.setDecorFitsSystemWindows(window, true)

                @Suppress("DEPRECATION")
                window.statusBarColor = colorScheme.primary.toArgb()
                @Suppress("DEPRECATION")
                window.navigationBarColor = colorScheme.background.toArgb()

                insetsController.isAppearanceLightStatusBars = darkTheme
                insetsController.isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
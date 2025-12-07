package com.diajarkoding.imfit.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val IMFITColorScheme = lightColorScheme(
    primary = Primary,
    onPrimary = TextOnPrimaryLight,
    primaryContainer = PrimaryContainer,
    onPrimaryContainer = PrimaryDark,

    secondary = Secondary,
    onSecondary = TextOnPrimaryLight,
    secondaryContainer = SecondaryContainer,
    onSecondaryContainer = SecondaryDark,

    tertiary = Accent,
    onTertiary = TextOnPrimaryLight,
    tertiaryContainer = AccentLight,
    onTertiaryContainer = AccentDark,

    background = BackgroundPrimaryLight,
    onBackground = TextPrimaryLight,

    surface = SurfaceLight,
    onSurface = TextPrimaryLight,

    surfaceVariant = BackgroundSecondaryLight,
    onSurfaceVariant = TextSecondaryLight,

    error = ErrorRed,
    onError = TextOnPrimaryLight,

    outline = DividerLight,
    outlineVariant = DisabledGrayLight
)

@Composable
fun IMFITTheme(
    content: @Composable () -> Unit
) {
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = true
        }
    }

    MaterialTheme(
        colorScheme = IMFITColorScheme,
        typography = Typography,
        shapes = Shapes,
        content = content
    )
}
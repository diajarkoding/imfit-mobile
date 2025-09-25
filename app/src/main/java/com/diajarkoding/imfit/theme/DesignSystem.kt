package com.diajarkoding.imfit.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Extension untuk mendapatkan warna custom berdasarkan tema
object IMFITColors {
    val gradientBrush: Brush
        @Composable
        @ReadOnlyComposable
        get() = Brush.horizontalGradient(
            colors = listOf(GradientStart, GradientEnd)
        )

    val successColor: Color get() = SuccessGreen
    val warningColor: Color get() = WarningOrange
    val errorColor: Color get() = ErrorRed

    // Dynamic colors based on theme
    val textPrimary: Color
        @Composable
        @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) TextPrimaryDark else TextPrimaryLight

    val textSecondary: Color
        @Composable
        @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) TextSecondaryDark else TextSecondaryLight

    val textTertiary: Color
        @Composable
        @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) TextTertiaryDark else TextTertiaryLight

    val backgroundPrimary: Color
        @Composable
        @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) BackgroundPrimaryDark else BackgroundPrimaryLight

    val backgroundSecondary: Color
        @Composable
        @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) BackgroundSecondaryDark else BackgroundSecondaryLight

    val bottomNavActive: Color get() = BottomNavActive
    val bottomNavInactive: Color
        @Composable
        @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) BottomNavInactiveDark else BottomNavInactiveLight

    val bottomNavBackground: Color
        @Composable
        @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) BottomNavBackgroundDark else BottomNavBackgroundLight

    val bottomNavShadow: Color
        @Composable
        @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) BottomNavShadowDark else BottomNavShadowLight

    val cardShadow: Color
        @Composable
        @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) CardShadowDark else CardShadowLight

    val surfaceElevated: Color
        @Composable
        @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) SurfaceElevatedDark else SurfaceElevatedLight

    val divider: Color
        @Composable
        @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) DividerDark else DividerLight
}

// Extension untuk MaterialTheme colors
val MaterialTheme.customColors: IMFITCustomColors
    @Composable
    @ReadOnlyComposable
    get() = IMFITCustomColors

object IMFITCustomColors {
    val gradient: Brush
        @Composable
        @ReadOnlyComposable
        get() = IMFITColors.gradientBrush

    val success: Color get() = IMFITColors.successColor
    val warning: Color get() = IMFITColors.warningColor

    val textPrimary: Color
        @Composable
        @ReadOnlyComposable
        get() = IMFITColors.textPrimary

    val textSecondary: Color
        @Composable
        @ReadOnlyComposable
        get() = IMFITColors.textSecondary

    val textTertiary: Color
        @Composable
        @ReadOnlyComposable
        get() = IMFITColors.textTertiary

    val backgroundPrimary: Color
        @Composable
        @ReadOnlyComposable
        get() = IMFITColors.backgroundPrimary

    val backgroundSecondary: Color
        @Composable
        @ReadOnlyComposable
        get() = IMFITColors.backgroundSecondary

    val bottomNavActive: Color get() = IMFITColors.bottomNavActive
    val bottomNavInactive: Color
        @Composable
        @ReadOnlyComposable
        get() = IMFITColors.bottomNavInactive

    val bottomNavBackground: Color
        @Composable
        @ReadOnlyComposable
        get() = IMFITColors.bottomNavBackground

    val bottomNavShadow: Color
        @Composable
        @ReadOnlyComposable
        get() = IMFITColors.bottomNavShadow

    val surfaceElevated: Color
        @Composable
        @ReadOnlyComposable
        get() = IMFITColors.surfaceElevated

    val cardShadow: Color
        @Composable
        @ReadOnlyComposable
        get() = IMFITColors.cardShadow

    val divider: Color
        @Composable
        @ReadOnlyComposable
        get() = IMFITColors.divider
}

// Utility untuk shadows dan elevations
object IMFITElevation {
    val card = 4.dp
    val fab = 6.dp
    val navigation = 3.dp        // Bottom nav elevation
    val bottomNav = 8.dp         // Specific for bottom navigation
}

// Utility untuk spacing
object IMFITSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp
    val xxl = 48.dp
}

// Utility untuk corner radius
object IMFITCornerRadius {
    val small = 8.dp
    val medium = 12.dp
    val large = 16.dp
    val xlarge = 24.dp
}

// Bottom Navigation Dimensions - Based on image analysis
object IMFITBottomNavDimensions {
    val height = 80.dp           // Total height
    val iconSize = 24.dp         // Icon size
    val iconTextSpacing = 4.dp   // Space between icon and text
    val horizontalPadding = 12.dp
    val verticalPadding = 8.dp
}
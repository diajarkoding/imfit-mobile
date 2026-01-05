package com.diajarkoding.imfit.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val LocalIsDarkTheme = compositionLocalOf { false }

object IMFITColors {
    val primary: Color get() = Primary

    val gradientBrush: Brush
        get() = Brush.horizontalGradient(
            colors = listOf(Primary, PrimaryLight)
        )

    val success: Color get() = Success
    val warning: Color get() = Warning
    val error: Color get() = Error
    val info: Color get() = Info

    @Composable
    @ReadOnlyComposable
    fun textPrimary(isDark: Boolean = LocalIsDarkTheme.current): Color =
        if (isDark) TextPrimaryDark else TextPrimaryLight

    @Composable
    @ReadOnlyComposable
    fun textSecondary(isDark: Boolean = LocalIsDarkTheme.current): Color =
        if (isDark) TextSecondaryDark else TextSecondaryLight

    @Composable
    @ReadOnlyComposable
    fun textTertiary(isDark: Boolean = LocalIsDarkTheme.current): Color =
        if (isDark) TextTertiaryDark else TextTertiaryLight

    @Composable
    @ReadOnlyComposable
    fun backgroundPrimary(isDark: Boolean = LocalIsDarkTheme.current): Color =
        if (isDark) BackgroundDark else BackgroundLight

    @Composable
    @ReadOnlyComposable
    fun backgroundSecondary(isDark: Boolean = LocalIsDarkTheme.current): Color =
        if (isDark) BackgroundSecondaryDark else BackgroundSecondaryLight

    @Composable
    @ReadOnlyComposable
    fun navActive(isDark: Boolean = LocalIsDarkTheme.current): Color =
        if (isDark) NavActiveDark else NavActiveLight

    @Composable
    @ReadOnlyComposable
    fun navInactive(isDark: Boolean = LocalIsDarkTheme.current): Color =
        if (isDark) NavInactiveDark else NavInactiveLight

    @Composable
    @ReadOnlyComposable
    fun navBackground(isDark: Boolean = LocalIsDarkTheme.current): Color =
        if (isDark) NavBackgroundDark else NavBackgroundLight

    @Composable
    @ReadOnlyComposable
    fun cardBackground(isDark: Boolean = LocalIsDarkTheme.current): Color =
        if (isDark) CardBackgroundDark else CardBackgroundLight

    @Composable
    @ReadOnlyComposable
    fun cardBorder(isDark: Boolean = LocalIsDarkTheme.current): Color =
        if (isDark) CardBorderDark else CardBorderLight

    @Composable
    @ReadOnlyComposable
    fun divider(isDark: Boolean = LocalIsDarkTheme.current): Color =
        if (isDark) DividerDark else DividerLight

    @Composable
    @ReadOnlyComposable
    fun disabled(isDark: Boolean = LocalIsDarkTheme.current): Color =
        if (isDark) DisabledDark else DisabledLight

    @Composable
    @ReadOnlyComposable
    fun surface(isDark: Boolean = LocalIsDarkTheme.current): Color =
        if (isDark) SurfaceDark else SurfaceLight

    @Composable
    @ReadOnlyComposable
    fun surfaceVariant(isDark: Boolean = LocalIsDarkTheme.current): Color =
        if (isDark) SurfaceVariantDark else SurfaceVariantLight
}

val MaterialTheme.customColors: IMFITCustomColors
    @Composable
    @ReadOnlyComposable
    get() = IMFITCustomColors

object IMFITCustomColors {
    val gradient: Brush
        get() = IMFITColors.gradientBrush

    val success: Color get() = IMFITColors.success
    val warning: Color get() = IMFITColors.warning
    val error: Color get() = IMFITColors.error
    val info: Color get() = IMFITColors.info

    val textPrimary: Color
        @Composable
        @ReadOnlyComposable
        get() = IMFITColors.textPrimary()

    val textSecondary: Color
        @Composable
        @ReadOnlyComposable
        get() = IMFITColors.textSecondary()

    val textTertiary: Color
        @Composable
        @ReadOnlyComposable
        get() = IMFITColors.textTertiary()

    val backgroundPrimary: Color
        @Composable
        @ReadOnlyComposable
        get() = IMFITColors.backgroundPrimary()

    val backgroundSecondary: Color
        @Composable
        @ReadOnlyComposable
        get() = IMFITColors.backgroundSecondary()

    val navActive: Color
        @Composable
        @ReadOnlyComposable
        get() = IMFITColors.navActive()

    val navInactive: Color
        @Composable
        @ReadOnlyComposable
        get() = IMFITColors.navInactive()

    val navBackground: Color
        @Composable
        @ReadOnlyComposable
        get() = IMFITColors.navBackground()

    val surfaceElevated: Color
        @Composable
        @ReadOnlyComposable
        get() = IMFITColors.surface()

    val cardShadow: Color
        @Composable
        @ReadOnlyComposable
        get() = if (LocalIsDarkTheme.current) Color(0x33000000) else Color(0x1A000000)

    val divider: Color
        @Composable
        @ReadOnlyComposable
        get() = IMFITColors.divider()
}

object IMFITElevation {
    val card = 4.dp
    val fab = 6.dp
    val navigation = 3.dp
    val bottomNav = 8.dp
}

object IMFITSpacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp
    val huge = 48.dp
    
    // Screen padding
    val screenHorizontal = 20.dp
    val screenVertical = 16.dp
    
    // Card internal padding
    val cardPadding = 16.dp
    val cardPaddingLarge = 20.dp
    
    // Section spacing
    val sectionSpacing = 24.dp
    val itemSpacing = 12.dp
}

object IMFITCornerRadius {
    val xs = 6.dp
    val small = 10.dp
    val medium = 14.dp
    val large = 18.dp
    val xlarge = 24.dp
    val round = 50.dp
}

object IMFITSizes {
    // Button heights
    val buttonHeight = 54.dp
    val buttonHeightSmall = 44.dp
    val buttonHeightLarge = 60.dp
    
    // Icon sizes
    val iconXs = 16.dp
    val iconSm = 20.dp
    val iconMd = 24.dp
    val iconLg = 28.dp
    val iconXl = 32.dp
    val iconXxl = 48.dp
    val iconHuge = 64.dp
    
    // Avatar sizes
    val avatarSm = 40.dp
    val avatarMd = 56.dp
    val avatarLg = 72.dp
    
    // Card min heights
    val cardMinHeight = 80.dp
    val listItemHeight = 72.dp
    
    // Input heights
    val textFieldHeight = 56.dp
}

object IMFITBottomNavDimensions {
    val height = 80.dp
    val iconSize = 24.dp
    val iconTextSpacing = 4.dp
    val horizontalPadding = 12.dp
    val verticalPadding = 8.dp
}

object IMFITAnimations {
    const val durationFast = 150
    const val durationMedium = 300
    const val durationSlow = 500
}

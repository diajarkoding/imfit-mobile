package com.diajarkoding.imfit.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.diajarkoding.imfit.R

val LocalIsDarkTheme = compositionLocalOf { false }
val LocalTrainingPalette = compositionLocalOf { TrainingLightPalette }

object IMFITMotion {
    const val ThemeTransitionDurationMillis = 300
}

object IMFITColors {
    val training: IMFITPalette
        @Composable @ReadOnlyComposable get() = LocalTrainingPalette.current
    val strengthBanner: Brush get() = Brush.horizontalGradient(
        0.00f to BannerDeepNavy,
        0.30f to BannerDeepNavy,
        0.53f to BannerMidnightBlue,
        0.73f to BannerMediumBlue,
        1.00f to BannerRoyalBlue
    )
    val alarmIllustration: Color
        @Composable @ReadOnlyComposable get() = if (LocalIsDarkTheme.current) AlarmIllustrationDark else AlarmIllustrationLight
    val statusSuccess: Color
        @Composable @ReadOnlyComposable get() = training.success
    val statusWarning: Color
        @Composable @ReadOnlyComposable get() = training.warning
    val statusError: Color
        @Composable @ReadOnlyComposable get() = training.error
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
        get() = if (LocalIsDarkTheme.current) ShadowDark else ShadowLight

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
    val inline = 5.dp
    val compact = 6.dp
    val sm = 8.dp
    val controlGap = 10.dp
    val md = 12.dp
    val summaryGap = 14.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp
    val huge = 48.dp
    
    // Screen padding
    val screenHorizontal = 20.dp
    val screenVertical = 16.dp
    val headerContentGap = 8.dp
    
    // Card internal padding
    val cardPadding = 16.dp
    val cardPaddingLarge = 20.dp
    
    // Section spacing
    val sectionSpacing = 24.dp
    val itemSpacing = 12.dp
}

object IMFITPreferenceSizes {
    val trackWidth = 72.dp
    val trackHeight = 36.dp
    val thumb = 30.dp
    val thumbInset = 3.dp
    val thumbTravel = trackWidth - thumb - thumbInset * 2
    val languageTouchWidth = 96.dp
}

object IMFITCornerRadius {
    val xs = 6.dp
    val small = 10.dp
    val medium = 14.dp
    val large = 18.dp
    val xlarge = 24.dp
    val round = 50.dp
    val template = 12.dp
    val listItem = 16.dp
    val card = 20.dp
    val dialog = 28.dp
}

object IMFITSizes {
    // Button heights
    val buttonHeight = 48.dp
    val buttonHeightSmall = buttonHeight
    val buttonHeightLarge = 60.dp
    val minimumInteractiveTarget = 48.dp
    val buttonLoadingIcon = 22.dp
    val buttonLoadingStroke = 2.5.dp
    val outlinedButtonStroke = 1.5.dp
    
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

object IMFITFonts {
    val Inter = FontFamily(
        Font(R.font.inter, FontWeight.Normal), Font(R.font.inter, FontWeight.Medium),
        Font(R.font.inter, FontWeight.SemiBold), Font(R.font.inter, FontWeight.Bold),
        Font(R.font.inter, FontWeight.ExtraBold)
    )
}

object IMFITTypography {
    private fun text(size: Int, line: Float, weight: FontWeight = FontWeight.Normal, tracking: Float = 0f) =
        TextStyle(fontFamily = IMFITFonts.Inter, fontSize = size.sp, lineHeight = line.sp,
            fontWeight = weight, letterSpacing = tracking.sp)
    val displayLarge = text(57, 64f)
    val displayMedium = text(45, 52f)
    val displaySmall = text(36, 44f)
    val headlineLarge = text(32, 40f, FontWeight.Bold, -0.5f)
    val headlineMedium = text(28, 36f, FontWeight.Bold)
    val headlineSmall = text(24, 32f, FontWeight.SemiBold)
    val titleLarge = text(22, 28f, FontWeight.SemiBold)
    val titleMedium = text(18, 24f, FontWeight.SemiBold, 0.1f)
    val titleSmall = text(16, 22f, FontWeight.Medium, 0.1f)
    val bodyLarge = text(16, 24f, tracking = 0.15f)
    val bodyMedium = text(14, 20f, tracking = 0.25f)
    val bodySmall = text(12, 16f, tracking = 0.4f)
    val labelLarge = text(14, 20f, FontWeight.SemiBold, 0.1f)
    val labelMedium = text(12, 16f, FontWeight.Medium, 0.5f)
    val labelSmall = text(11, 16f, FontWeight.Medium, 0.5f)
    val brand = text(22, 30.8f, FontWeight.ExtraBold, -0.8f)
    val brandBadge = text(10, 14f, FontWeight.Bold, 0.7f)
    val greeting = text(23, 27.6f, FontWeight.Bold, -0.7f)
    val supporting = text(13, 18.2f)
    val bannerTitle = text(23, 26.45f, FontWeight.Bold, -0.6f)
    val caption = text(12, 16.8f)
    val compactTitle = text(14, 19.6f, FontWeight.SemiBold)
    val historyHeading = text(10, 14f, FontWeight.SemiBold, 0.4f)
    val historyDate = text(10, 14f)
    val metricCaption = text(11, 13.2f)
    val metricValue = text(22, 25.3f, FontWeight.SemiBold, -0.6f)
    val textAction = text(13, 18.2f, FontWeight.SemiBold)
    val sectionTitle = text(20, 28f, FontWeight.Bold, -0.5f)
    val templateTitle = text(17, 20.4f, FontWeight.SemiBold)
    val statusTitle = text(14, 20.3f, FontWeight.SemiBold)
    val statusBody = text(12, 17.4f)
    val screenTitle = text(25, 30f, FontWeight.Bold, -0.7f)
    val categoryLabel = text(14, 20f, FontWeight.SemiBold)
    val calendarDay = text(10, 14f)
    val switchLabel = text(10, 14f, FontWeight.Bold)
    val switchThumb = text(9, 12f, FontWeight.Bold)
    val splashBrand = text(42, 52f, FontWeight.Bold, 4f)
    val alarmExercise = text(16, 23f, FontWeight.Medium)
    val alarmBody = text(13, 19f)
    val alarmAction = text(15, 21f, FontWeight.SemiBold)
    val alarmHint = text(12, 18f)
    val progressTitle = text(24, 33.6f, FontWeight.Bold, -0.7f)
    val profileName = text(17, 23.8f, FontWeight.SemiBold)
    val progressMetricCaption = text(12, 16.8f, FontWeight.Medium)
    val progressMetricValue = text(25, 27.5f, FontWeight.Bold, -0.8f)
    val progressPeriod = text(11, 15.4f)
    val calendarMonth = text(19, 26.6f, FontWeight.SemiBold, -0.4f)
    val calendarWeekday = text(11, 15.4f, FontWeight.Medium)
    val progressDate = text(12, 16.8f)
}

object IMFITProgress {
    val headerHeight = 64.dp
    val profileHeight = 56.dp
    val metricHeight = 116.dp
    val dateIndicator = 34.dp
    val legendMarker = 8.dp
    val selectedDayHeight = 64.dp
    val identityGap = 3.dp
    val calendarRadius = IMFITCornerRadius.listItem
}

object WorkoutTypography {
    val timerDisplay = IMFITTypography.displayLarge.copy(fontSize = 72.sp, lineHeight = 80.sp, fontWeight = FontWeight.Bold, letterSpacing = (-2).sp)
    val timerLabel = IMFITTypography.titleSmall.copy(fontWeight = FontWeight.Medium, lineHeight = 20.sp)
    val setNumber = IMFITTypography.headlineSmall.copy(fontWeight = FontWeight.Bold, lineHeight = 28.sp)
    val weightInput = IMFITTypography.headlineLarge.copy(lineHeight = 38.sp, letterSpacing = (-1).sp)
    val repsInput = weightInput
    val volumeDisplay = IMFITTypography.displaySmall.copy(fontWeight = FontWeight.Bold, lineHeight = 44.sp, letterSpacing = (-1).sp)
    val durationDisplay = IMFITTypography.headlineMedium.copy(fontWeight = FontWeight.SemiBold, lineHeight = 34.sp, letterSpacing = (-0.5).sp)
    val exerciseName = IMFITTypography.sectionTitle.copy(fontWeight = FontWeight.SemiBold, lineHeight = 26.sp, letterSpacing = 0.sp)
    val muscleGroup = IMFITTypography.bodyMedium
}

object IMFITComponentSizes {
    val homeHeader = 64.dp
    val exerciseHeader = 76.dp
    val brandTile = 36.dp
    val brandIcon = 22.dp
    val demoBadgeWidth = 56.dp
    val demoBadgeHeight = 26.dp
    val bannerHeight = 176.dp
    const val bannerTextFraction = 0.62f
    val templateThumbnail = 56.dp
    val templateArtwork = 48.dp
    val addDayWidth = 130.dp
    val categoryArtwork = 112.dp
    val categoryRowMinHeight = 154.dp
    val adaptiveGridWidth = 360.dp
    const val adaptiveFontScale = 1.3f
    val sectionStackWidth = 320.dp
    val metricIcon = 14.dp
    val metricDivider = 38.dp
    val border = 1.dp
    val activeBorder = 2.dp
}

object IMFITOpacity {
    // One Compose scrim for dialog hosts, Material bottom sheets and fullscreen sync.
    // Dialog native dim/blur must remain disabled to avoid stacking with this layer.
    const val scrim = 0.50f
    const val disabled = 0.4f
    const val disabledContent = 0.6f
    const val secondaryDisabled = 0.5f
    const val pressed = 0.08f
    const val overlaySecondary = 0.8f
    const val overlayTrack = 0.2f
}

object IMFITBottomNavDimensions {
    val height = 80.dp
    val iconSize = 24.dp
    val iconTextSpacing = 4.dp
    val horizontalPadding = 12.dp
    val verticalPadding = 8.dp
}

object IMFITGradients {
    val brandLinear: Brush get() = Brush.linearGradient(listOf(Primary, PrimaryLight))
    val brandVertical: Brush get() = Brush.verticalGradient(listOf(Primary, PrimaryLight))
    val brandHorizontal: Brush get() = IMFITColors.gradientBrush
    val disabledBrand: Brush get() = Brush.horizontalGradient(
        listOf(Primary.copy(alpha = IMFITOpacity.disabled), PrimaryLight.copy(alpha = IMFITOpacity.disabled)))
    val dialogWash: Brush get() = Brush.linearGradient(listOf(Primary.copy(alpha = 0.15f), PrimaryLight.copy(alpha = 0.1f)))
    val avatarWash: Brush get() = Brush.linearGradient(listOf(Primary.copy(alpha = 0.1f), PrimaryLight.copy(alpha = 0.1f)))
    val surfaceWash: Brush get() = Brush.verticalGradient(listOf(Primary.copy(alpha = 0.1f), Primary.copy(alpha = 0.02f)))
    val metricWash: Brush get() = Brush.verticalGradient(listOf(Primary.copy(alpha = 0.1f), Primary.copy(alpha = 0.03f)))
    val workoutWash: Brush get() = Brush.horizontalGradient(listOf(Primary.copy(alpha = 0.1f), PrimaryLight.copy(alpha = 0.05f)))
    val completed: Brush get() = Brush.linearGradient(listOf(SetComplete, SetComplete.copy(alpha = 0.8f)))
}

object IMFITBanner {
    val height = 176.dp
    val radius = 20.dp
    const val ratio = 2.22f
    const val textFraction = 0.60f
    const val illustrationHeightFraction = 0.74f
    const val illustrationOffsetFraction = 0.06f
    const val stopDeepNavy = 0.00f
    const val stopDeepNavyHold = 0.30f
    const val stopMidnight = 0.53f
    const val stopMedium = 0.73f
    const val stopRoyal = 1.00f
    const val glowCenterX = 0.78f
    const val glowCenterY = 0.47f
    const val glowRadiusFraction = 0.27f
    const val glowMaxAlpha = 0.75f
    const val glowMidAlpha = 0.30f
    const val glowMidStop = 0.45f
    const val vignetteTopAlpha = 0.12f
    const val vignetteBottomAlpha = 0.12f
    const val vignetteMidStop = 0.50f
}

object IMFITAnimations {
    const val durationFast = 150
    const val durationMedium = 300
    const val durationSlow = 500
}

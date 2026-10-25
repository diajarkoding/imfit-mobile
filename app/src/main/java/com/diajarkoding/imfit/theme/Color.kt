package com.diajarkoding.imfit.theme

import androidx.compose.ui.graphics.Color

// Primary/Accent Color - Blue (same for both themes)
val Primary = Color(0xFF3179FF)
val PrimaryDark = Color(0xFF2563EB)
val PrimaryLight = Color(0xFF5C9AFF)
val PrimaryContainer = Color(0xFFD6E4FF)
val OnPrimary = Color(0xFFFFFFFF)

// Light Theme Colors
val BackgroundLight = Color(0xFFF7F9FC)
val BackgroundSecondaryLight = Color(0xFFF3F4F8)
val SurfaceLight = Color(0xFFFFFFFF)
val SurfaceVariantLight = Color(0xFFF3F4F8)
val OnBackgroundLight = Color(0xFF1A1A1A)
val OnSurfaceLight = Color(0xFF1A1A1A)
val OnSurfaceVariantLight = Color(0xFF6B7280)
val OutlineLight = Color(0xFFE5E7EB)
val OutlineVariantLight = Color(0xFFD1D5DB)

// Dark Theme Colors
val BackgroundDark = Color(0xFF121212)
val BackgroundSecondaryDark = Color(0xFF1F1F1F)
val SurfaceDark = Color(0xFF1F1F1F)
val SurfaceVariantDark = Color(0xFF2A2A2A)
val OnBackgroundDark = Color(0xFFF5F5F5)
val OnSurfaceDark = Color(0xFFF5F5F5)
val OnSurfaceVariantDark = Color(0xFF9CA3AF)
val OutlineDark = Color(0xFF374151)
val OutlineVariantDark = Color(0xFF4B5563)

// Status Colors
val Success = Color(0xFF22C55E)
val SuccessContainer = Color(0xFFDCFCE7)
val OnSuccess = Color(0xFFFFFFFF)
val Warning = Color(0xFFF59E0B)
val WarningContainer = Color(0xFFFEF3C7)
val OnWarning = Color(0xFFFFFFFF)
val Error = Color(0xFFEF4444)
val ErrorContainer = Color(0xFFFEE2E2)
val OnError = Color(0xFFFFFFFF)
val Info = Color(0xFF3179FF)
val InfoContainer = Color(0xFFD6E4FF)

// Text Colors
val TextPrimaryLight = Color(0xFF1A1A1A)
val TextSecondaryLight = Color(0xFF6B7280)
val TextTertiaryLight = Color(0xFF9CA3AF)

val TextPrimaryDark = Color(0xFFF5F5F5)
val TextSecondaryDark = Color(0xFF9CA3AF)
val TextTertiaryDark = Color(0xFF6B7280)

// Card Colors
val CardBackgroundLight = Color(0xFFFFFFFF)
val CardBackgroundDark = Color(0xFF1F1F1F)
val CardBorderLight = Color(0xFFE5E7EB)
val CardBorderDark = Color(0xFF374151)

// Divider Colors
val DividerLight = Color(0xFFE5E7EB)
val DividerDark = Color(0xFF374151)

// Disabled Colors
val DisabledLight = Color(0xFFD1D5DB)
val DisabledDark = Color(0xFF4B5563)

// Navigation Colors
val NavActiveLight = Color(0xFF3179FF)
val NavActiveDark = Color(0xFF3179FF)
val NavInactiveLight = Color(0xFF9CA3AF)
val NavInactiveDark = Color(0xFF6B7280)
val NavBackgroundLight = Color(0xFFFFFFFF)
val NavBackgroundDark = Color(0xFF121212)

// Workout Specific Colors
val SetComplete = Color(0xFF22C55E)
val SetPending = Color(0xFF6B7280)
val RestTimer = Color(0xFF3179FF)
val TimerActive = Color(0xFF3179FF)
val TimerWarning = Color(0xFFF59E0B)

// Delete Action Color
val DeletePink = Color(0xFFE91E63)

// Special-purpose colors are still owned here, not by individual screens.
val TrainingMutedLight = Color(0xFF5F6878)
val TrainingMutedDark = Color(0xFFB0B7C3)
val TrainingOnPrimaryDark = Color(0xFF101D34)
val PrimaryContainerDark = Color(0xFF19345E)
val OnPrimaryContainerLight = Color(0xFF1746A2)
val OnPrimaryContainerDark = Color(0xFFC6DCFF)
val SuccessForegroundLight = Color(0xFF15803D)
val SuccessForegroundDark = Color(0xFF4ADE80)
val SuccessContainerDark = Color(0xFF143523)
val WarningForegroundLight = Color(0xFF92400E)
val WarningForegroundDark = Color(0xFFFBBF24)
val WarningContainerDark = Color(0xFF382B13)
val ErrorForegroundLight = Color(0xFFB91C1C)
val ErrorForegroundDark = Color(0xFFFCA5A5)
val ErrorContainerDark = Color(0xFF421E20)
val BannerDeepNavy = Color(0xFF142342)
val BannerMidnightBlue = Color(0xFF182E60)
val BannerMediumBlue = Color(0xFF1D438C)
val BannerRoyalBlue = Color(0xFF234AA8)
val BannerCyanGlow = Color(0xFF3484CD)
val BannerStrengthStart = BannerDeepNavy
val BannerStrengthEnd = BannerRoyalBlue
val BannerStrengthForeground = OnPrimary
val BannerStrengthCaption = PrimaryContainer
val AlarmIllustrationLight = Color(0xFFEDF5FF)
val AlarmIllustrationDark = Color(0xFF182D47)
val ShadowLight = Color(0x1A000000)
val ShadowDark = Color(0x33000000)
val Scrim = Color.Black
val OverlayForeground = OnPrimary

data class IMFITPalette(
    val background: Color, val surface: Color, val raised: Color,
    val text: Color, val muted: Color, val border: Color,
    val primary: Color, val onPrimary: Color, val primaryContainer: Color, val onPrimaryContainer: Color,
    val success: Color, val successContainer: Color, val warning: Color, val warningContainer: Color,
    val error: Color, val errorContainer: Color
)

val TrainingLightPalette = IMFITPalette(
    BackgroundLight, SurfaceLight, SurfaceVariantLight, OnSurfaceLight, TrainingMutedLight, CardBorderLight,
    PrimaryDark, OnPrimary, PrimaryContainer, OnPrimaryContainerLight,
    SuccessForegroundLight, SuccessContainer, WarningForegroundLight, WarningContainer,
    ErrorForegroundLight, ErrorContainer
)
val TrainingDarkPalette = IMFITPalette(
    BackgroundDark, SurfaceDark, SurfaceVariantDark, OnSurfaceDark, TrainingMutedDark, CardBorderDark,
    PrimaryLight, TrainingOnPrimaryDark, PrimaryContainerDark, OnPrimaryContainerDark,
    SuccessForegroundDark, SuccessContainerDark, WarningForegroundDark, WarningContainerDark,
    ErrorForegroundDark, ErrorContainerDark
)

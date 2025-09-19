package com.diajarkoding.imfit.presentation.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColorScheme = lightColorScheme(
    primary = PrimaryBlue,
    onPrimary = SurfaceLight,
    secondary = AccentTeal,
    background = BackgroundLight,
    surface = SurfaceLight,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextSecondary
)

@Composable
fun IMFITTheme(
    content: @Composable () -> Unit
) {
    val colorScheme = LightColorScheme
//    val view = LocalView.current
//
//    if (!view.isInEditMode) {
//        SideEffect {
//            val window = (view.context as Activity).window
//            val insetsController = WindowCompat.getInsetsController(window, view)
//
//            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
//                // Android 15+ - Use edge-to-edge but handle insets properly
//                WindowCompat.setDecorFitsSystemWindows(window, false)
//                @Suppress("DEPRECATION")
//                window.statusBarColor = Color.Transparent.toArgb()
//                @Suppress("DEPRECATION")
//                window.navigationBarColor = Color.Transparent.toArgb()
//
//                // Dark icons for better visibility
//                insetsController.isAppearanceLightStatusBars = true
//                insetsController.isAppearanceLightNavigationBars = true
//
//            } else {
//                // Android 14 and below - Use traditional approach
//                WindowCompat.setDecorFitsSystemWindows(window, true)
//
//                @Suppress("DEPRECATION")
//                window.statusBarColor = colorScheme.primary.toArgb()
//                @Suppress("DEPRECATION")
//                window.navigationBarColor = colorScheme.background.toArgb()
//
//                insetsController.isAppearanceLightStatusBars = false
//                insetsController.isAppearanceLightNavigationBars = true
//            }
//        }
//    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
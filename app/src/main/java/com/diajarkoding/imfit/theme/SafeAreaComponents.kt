package com.diajarkoding.imfit.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

@Composable
fun StatusBarSpacer(
    backgroundColor: Color = MaterialTheme.customColors.backgroundPrimary
) {
    Spacer(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsTopHeight(WindowInsets.statusBars)
            .background(backgroundColor)
    )
}

@Composable
fun GradientStatusBarSpacer() {
    Spacer(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsTopHeight(WindowInsets.statusBars)
            .background(MaterialTheme.customColors.gradient)
    )
}

@Composable
fun NavigationBarSpacer(
    backgroundColor: Color = MaterialTheme.customColors.backgroundPrimary
) {
    Spacer(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsBottomHeight(WindowInsets.navigationBars)
            .background(backgroundColor)
    )
}

@Composable
fun SystemBarsSafeArea(
    modifier: Modifier = Modifier,
    statusBarColor: Color = MaterialTheme.customColors.backgroundPrimary,
    navigationBarColor: Color = MaterialTheme.customColors.backgroundPrimary,
    useGradientStatusBar: Boolean = false,
    content: @Composable () -> Unit
) {
    Column(modifier = modifier.fillMaxSize()) {
        if (useGradientStatusBar) {
            GradientStatusBarSpacer()
        } else {
            StatusBarSpacer(backgroundColor = statusBarColor)
        }
        Box(
            modifier = Modifier.weight(1f)
        ) {
            content()
        }
        // Navigation bar spacing akan ditangani oleh custom bottom navigation
    }
}

@Composable
fun SafeAreaContent(
    modifier: Modifier = Modifier,
    backgroundColor: Color = MaterialTheme.customColors.backgroundPrimary,
    includeNavigationBarPadding: Boolean = true,
    content: @Composable () -> Unit
) {
    val windowInsets = if (includeNavigationBarPadding) {
        WindowInsets.systemBars
    } else {
        WindowInsets.statusBars
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
            .padding(windowInsets.asPaddingValues())
    ) {
        content()
    }
}

// Khusus untuk workout screens dengan gradient header
@Composable
fun WorkoutScreenContainer(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    SystemBarsSafeArea(
        modifier = modifier,
        useGradientStatusBar = true
    ) {
        content()
    }
}

// Container untuk screen dengan background secondary (abu-abu terang/gelap)
@Composable
fun SecondaryScreenContainer(
    modifier: Modifier = Modifier,
    includeNavigationBarPadding: Boolean = true,
    content: @Composable () -> Unit
) {
    SafeAreaContent(
        modifier = modifier,
        backgroundColor = MaterialTheme.customColors.backgroundSecondary,
        includeNavigationBarPadding = includeNavigationBarPadding
    ) {
        content()
    }
}

// Container khusus untuk top-level screens yang menggunakan custom bottom navigation
@Composable
fun TopLevelScreenContainer(
    modifier: Modifier = Modifier,
    backgroundColor: Color = MaterialTheme.customColors.backgroundPrimary,
    content: @Composable () -> Unit
) {
    SafeAreaContent(
        modifier = modifier,
        backgroundColor = backgroundColor,
        includeNavigationBarPadding = false  // Custom bottom nav akan handle ini
    ) {
        content()
    }
}
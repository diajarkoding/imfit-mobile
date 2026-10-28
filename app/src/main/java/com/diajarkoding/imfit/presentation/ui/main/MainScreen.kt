package com.diajarkoding.imfit.presentation.ui.main

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import com.diajarkoding.imfit.presentation.components.common.CalendarMonth
import com.diajarkoding.imfit.presentation.components.common.FitnessCenter
import com.diajarkoding.imfit.presentation.components.common.Home
import com.diajarkoding.imfit.presentation.components.common.Symbols
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.presentation.components.common.ImfitMainAppBar
import com.diajarkoding.imfit.presentation.components.common.SyncProgressDialog
import com.diajarkoding.imfit.presentation.ui.exercise.ExerciseBrowserScreen
import com.diajarkoding.imfit.presentation.ui.home.HomeScreen
import com.diajarkoding.imfit.presentation.ui.home.HomeViewModel
import com.diajarkoding.imfit.presentation.ui.progress.ProgressScreen
import com.diajarkoding.imfit.theme.Primary
import com.diajarkoding.imfit.theme.IMFITColors
import com.diajarkoding.imfit.theme.IMFITBottomNavDimensions
import com.diajarkoding.imfit.theme.LocaleManager
import java.time.LocalDate
import kotlinx.serialization.Serializable

private data class BottomNavItem(
    val key: NavKey,
    @androidx.annotation.StringRes val titleRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

@Serializable
private data object HomeTab : NavKey

@Serializable
private data object ExerciseTab : NavKey

@Serializable
private data object ProgressTab : NavKey

@Composable
fun MainScreen(
    onNavigateToWorkoutDetail: (String) -> Unit,
    onNavigateToActiveWorkout: (String) -> Unit = {},
    onNavigateToExerciseList: (String) -> Unit,
    onNavigateToWorkoutHistory: (LocalDate) -> Unit = {},
    onNavigateToYearlyCalendar: (java.time.YearMonth) -> Unit = {},
    onNavigateToProfile: () -> Unit = {},
    homeViewModel: HomeViewModel = hiltViewModel()
) {
    val items = listOf(
        BottomNavItem(HomeTab, com.diajarkoding.imfit.R.string.nav_home, Symbols.Filled.Home, Symbols.Outlined.Home),
        BottomNavItem(ExerciseTab, com.diajarkoding.imfit.R.string.nav_exercises, Symbols.Filled.FitnessCenter, Symbols.Outlined.FitnessCenter),
        BottomNavItem(ProgressTab, com.diajarkoding.imfit.R.string.nav_progress, Symbols.Filled.CalendarMonth, Symbols.Outlined.CalendarMonth)
    )
    val bottomBackStack = rememberNavBackStack(HomeTab)
    val currentTab = bottomBackStack.lastOrNull()
    
    // Get sync state from HomeViewModel
    val homeState by homeViewModel.state.collectAsStateWithLifecycle()
    val appBarTitleRes = when (currentTab) {
        is ExerciseTab -> R.string.nav_exercises
        is ProgressTab -> R.string.nav_progress
        else -> R.string.app_name
    }
    val showProfileAction = currentTab is HomeTab

    // Keep resource locale live without recreating the navigation backstack.
    // LocalContext stays the Activity context required by the existing Hilt owners.
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val localeVersion = LocaleManager.configurationVersion
    val localizedResources = remember(context, configuration, localeVersion) {
        LocaleManager.getUpdatedContext(context).resources
    }
    CompositionLocalProvider(LocalResources provides localizedResources,
        LocalConfiguration provides localizedResources.configuration) {
    // Wrap everything in Box for full-screen overlay capability
    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                ImfitMainAppBar(
                    title = androidx.compose.ui.res.stringResource(appBarTitleRes),
                    modifier = Modifier.windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
                    ),
                    profilePhotoUri = if (showProfileAction) homeState.userProfilePhotoUri else null,
                    onProfileClick = if (showProfileAction) onNavigateToProfile else null
                )
            },
            bottomBar = {
                NavigationBar(
                    modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = IMFITColors.navActive(),
                    tonalElevation = 0.dp
                ) {
                    items.forEach { item ->
                        val selected = currentTab == item.key
                        
                        NavigationBarItem(
                            icon = {
                                Icon(
                                    if (selected) item.selectedIcon else item.unselectedIcon,
                                    contentDescription = androidx.compose.ui.res.stringResource(item.titleRes)
                                )
                            },
                            label = {
                                Text(
                                    androidx.compose.ui.res.stringResource(item.titleRes),
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                )
                            },
                            selected = selected,
                            colors = NavigationBarItemDefaults.colors(
                                 selectedIconColor = IMFITColors.navActive(),
                                 selectedTextColor = IMFITColors.navActive(),
                                indicatorColor = Primary.copy(alpha = 0.12f),
                                 unselectedIconColor = IMFITColors.navInactive(),
                                 unselectedTextColor = IMFITColors.navInactive()
                            ),
                            onClick = {
                                bottomBackStack.clear()
                                bottomBackStack.add(HomeTab)
                                if (item.key != HomeTab) {
                                    bottomBackStack.add(item.key)
                                }
                            }
                        )
                    }
                }
            }
        ) { innerPadding ->
            NavDisplay(
                backStack = bottomBackStack,
                onBack = {
                    if (bottomBackStack.size > 1) {
                        bottomBackStack.removeLastOrNull()
                    }
                },
                modifier = Modifier
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding),
                entryDecorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator()
                ),
                entryProvider = entryProvider {
                    entry<HomeTab> {
                        HomeScreen(
                            onNavigateToWorkoutDetail = onNavigateToWorkoutDetail,
                            onNavigateToActiveWorkout = onNavigateToActiveWorkout,
                            onNavigateToProfile = onNavigateToProfile,
                            viewModel = homeViewModel
                        )
                    }
                    entry<ExerciseTab> {
                        ExerciseBrowserScreen(
                            onCategorySelected = { category ->
                                onNavigateToExerciseList(category.name)
                            }
                        )
                    }
                    entry<ProgressTab> {
                        ProgressScreen(
                            onNavigateToWorkoutHistory = onNavigateToWorkoutHistory,
                            onNavigateToYearlyCalendar = onNavigateToYearlyCalendar,
                            onNavigateToProfile = onNavigateToProfile
                        )
                    }
                }
            )
        }
        
        // Full-screen sync overlay - covers entire screen including bottom nav
    }
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun MainScreenPreview() {
    com.diajarkoding.imfit.theme.IMFITTheme(darkTheme = false) {
        MainScreen(
            onNavigateToWorkoutDetail = {},
            onNavigateToActiveWorkout = {},
            onNavigateToExerciseList = {},
            onNavigateToWorkoutHistory = {},
            onNavigateToYearlyCalendar = {},
            onNavigateToProfile = {}
        )
    }
}

package com.diajarkoding.imfit.presentation.ui.main

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.getValue
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
import com.diajarkoding.imfit.presentation.components.common.SyncProgressDialog
import com.diajarkoding.imfit.presentation.ui.exercise.ExerciseBrowserScreen
import com.diajarkoding.imfit.presentation.ui.home.HomeScreen
import com.diajarkoding.imfit.presentation.ui.home.HomeViewModel
import com.diajarkoding.imfit.presentation.ui.progress.ProgressScreen
import com.diajarkoding.imfit.theme.Primary
import java.time.LocalDate
import kotlinx.serialization.Serializable

private data class BottomNavItem(
    val key: NavKey,
    val title: String, 
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
    onNavigateToYearlyCalendar: () -> Unit = {},
    onNavigateToProfile: () -> Unit = {},
    homeViewModel: HomeViewModel = hiltViewModel()
) {
    val items = listOf(
        BottomNavItem(HomeTab, "Home", Symbols.Filled.Home, Symbols.Outlined.Home),
        BottomNavItem(ExerciseTab, "Exercise", Symbols.Filled.FitnessCenter, Symbols.Outlined.FitnessCenter),
        BottomNavItem(ProgressTab, "Progress", Symbols.Filled.CalendarMonth, Symbols.Outlined.CalendarMonth)
    )
    val bottomBackStack = rememberNavBackStack(HomeTab)
    val currentTab = bottomBackStack.lastOrNull()
    
    // Get sync state from HomeViewModel
    val syncState by homeViewModel.syncState.collectAsStateWithLifecycle()

    // Wrap everything in Box for full-screen overlay capability
    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            bottomBar = {
                NavigationBar(
                    modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = Primary,
                    tonalElevation = 0.dp
                ) {
                    items.forEach { item ->
                        val selected = currentTab == item.key
                        
                        NavigationBarItem(
                            icon = { 
                                Icon(
                                    if (selected) item.selectedIcon else item.unselectedIcon, 
                                    contentDescription = item.title
                                ) 
                            },
                            label = { 
                                Text(
                                    item.title,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                ) 
                            },
                            selected = selected,
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Primary,
                                selectedTextColor = Primary,
                                indicatorColor = Primary.copy(alpha = 0.12f),
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
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
                modifier = Modifier.padding(innerPadding),
                entryDecorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator()
                ),
                entryProvider = entryProvider {
                    entry<HomeTab> {
                        HomeScreen(
                            onNavigateToWorkoutDetail = onNavigateToWorkoutDetail,
                            onNavigateToActiveWorkout = onNavigateToActiveWorkout,
                            viewModel = homeViewModel
                        )
                    }
                    entry<ExerciseTab> {
                        ExerciseBrowserScreen(
                            onNavigateBack = { },
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
        SyncProgressDialog(syncState = syncState)
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

package com.diajarkoding.imfit.presentation.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.diajarkoding.imfit.presentation.components.common.SyncProgressDialog
import com.diajarkoding.imfit.presentation.ui.exercise.ExerciseBrowserScreen
import com.diajarkoding.imfit.presentation.ui.home.HomeScreen
import com.diajarkoding.imfit.presentation.ui.home.HomeViewModel
import com.diajarkoding.imfit.presentation.ui.progress.ProgressScreen
import com.diajarkoding.imfit.theme.Primary
import java.time.LocalDate

sealed class BottomNavItem(
    val route: String, 
    val title: String, 
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    object Home : BottomNavItem("home_tab", "Home", Icons.Filled.Home, Icons.Outlined.Home)
    object Exercise : BottomNavItem("exercise_tab", "Exercise", Icons.Filled.FitnessCenter, Icons.Outlined.FitnessCenter)
    object Progress : BottomNavItem("progress_tab", "Progress", Icons.Filled.CalendarMonth, Icons.Outlined.CalendarMonth)
}

@Composable
fun MainScreen(
    onNavigateToWorkoutDetail: (String) -> Unit,
    onNavigateToActiveWorkout: (String) -> Unit = {},
    onNavigateToExerciseList: (String) -> Unit,
    onNavigateToWorkoutHistory: (LocalDate) -> Unit = {},
    onNavigateToYearlyCalendar: () -> Unit = {},
    onNavigateToProfile: () -> Unit = {},
    onLogout: () -> Unit,
    bottomNavController: NavHostController = rememberNavController(),
    homeViewModel: HomeViewModel = hiltViewModel()
) {
    val items = listOf(
        BottomNavItem.Home,
        BottomNavItem.Exercise,
        BottomNavItem.Progress
    )
    
    // Get sync state from HomeViewModel
    val syncState by homeViewModel.syncState.collectAsState()

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
                    val navBackStackEntry by bottomNavController.currentBackStackEntryAsState()
                    val currentDestination = navBackStackEntry?.destination

                    items.forEach { item ->
                        val selected = currentDestination?.hierarchy?.any { it.route == item.route } == true
                        
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
                                bottomNavController.navigate(item.route) {
                                    popUpTo(bottomNavController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        ) { innerPadding ->
            NavHost(
                navController = bottomNavController,
                startDestination = BottomNavItem.Home.route,
                modifier = Modifier.padding(innerPadding)
            ) {
                composable(BottomNavItem.Home.route) {
                    HomeScreen(
                        onNavigateToWorkoutDetail = onNavigateToWorkoutDetail,
                        onNavigateToActiveWorkout = onNavigateToActiveWorkout,
                        viewModel = homeViewModel
                    )
                }
                composable(BottomNavItem.Exercise.route) {
                    ExerciseBrowserScreen(
                        onNavigateBack = { },
                        onCategorySelected = { category ->
                            onNavigateToExerciseList(category.name)
                        }
                    )
                }
                composable(BottomNavItem.Progress.route) {
                    ProgressScreen(
                        onNavigateToWorkoutHistory = onNavigateToWorkoutHistory,
                        onNavigateToYearlyCalendar = onNavigateToYearlyCalendar,
                        onNavigateToProfile = onNavigateToProfile
                    )
                }
            }
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
            onNavigateToProfile = {},
            onLogout = {}
        )
    }
}

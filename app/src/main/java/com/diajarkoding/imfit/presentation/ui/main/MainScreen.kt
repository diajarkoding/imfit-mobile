package com.diajarkoding.imfit.presentation.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.diajarkoding.imfit.MainViewModel
import com.diajarkoding.imfit.presentation.components.GlobalLoadingIndicator
import com.diajarkoding.imfit.presentation.components.IMFITAppBar
import com.diajarkoding.imfit.presentation.components.IMFITBottomNavigation
import com.diajarkoding.imfit.presentation.navigation.BottomNavItem
import com.diajarkoding.imfit.presentation.navigation.MainNavigation
import com.diajarkoding.imfit.theme.customColors


@Composable
fun activityViewModel(): MainViewModel {
    val context = LocalContext.current
    return androidx.lifecycle.viewmodel.compose.viewModel(context as ComponentActivity)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onLogout: () -> Unit,
    viewModel: MainViewModel = activityViewModel()
) {
    val state by viewModel.state.collectAsState()
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val topLevelRoutes = remember {
        setOf(
            BottomNavItem.Home.route,
            BottomNavItem.Workout.route,
            BottomNavItem.Exercises.route,
            BottomNavItem.Progress.route
        )
    }

    val bottomNavItems = listOf(
        BottomNavItem.Home,
        BottomNavItem.Workout,
        BottomNavItem.Exercises,
        BottomNavItem.Progress
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                IMFITAppBar(
                    currentDestination = currentDestination,
                    navController = navController,
                    topLevelRoutes = topLevelRoutes,
                    bottomNavItems = bottomNavItems
                )
            },
            bottomBar = {
                if (currentDestination?.route in topLevelRoutes) {
                    IMFITBottomNavigation(
                        items = bottomNavItems,
                        selectedItem = currentDestination?.route,
                        onItemClick = { item ->
                            navController.navigate(item.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            },
            containerColor = MaterialTheme.customColors.backgroundPrimary
        ) { innerPadding ->
            MainNavigation(
                navController = navController,
                modifier = Modifier.padding(innerPadding),
                onLogout = onLogout,
                showLoading = { viewModel.showGlobalLoading() },
                hideLoading = { viewModel.hideGlobalLoading() }
            )
        }

        if (state.isGlobalLoading) {
            GlobalLoadingIndicator()
        }
    }
}
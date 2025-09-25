package com.diajarkoding.imfit.presentation.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.diajarkoding.imfit.MainViewModel
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.presentation.components.GlobalLoadingIndicator
import com.diajarkoding.imfit.presentation.components.IMFITBottomNavigation
import com.diajarkoding.imfit.presentation.components.TwoToneTitle
import com.diajarkoding.imfit.presentation.navigation.BottomNavItem
import com.diajarkoding.imfit.presentation.navigation.MainNavigation
import com.diajarkoding.imfit.presentation.navigation.Routes
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

    // LANGKAH 1: Definisikan semua rute top-level di satu tempat.
    val topLevelRoutes = remember {
        setOf(
            BottomNavItem.Home.route,
            BottomNavItem.Workout.route,
            BottomNavItem.Exercises.route,
            BottomNavItem.Progress.route
        )
    }

    // LANGKAH 2: Cek apakah layar saat ini adalah layar top-level.
    val isTopLevelScreen = currentDestination?.route in topLevelRoutes

    val bottomNavItems = listOf(
        BottomNavItem.Home,
        BottomNavItem.Workout,
        BottomNavItem.Exercises,
        BottomNavItem.Progress
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                Surface(
                    color = MaterialTheme.customColors.backgroundPrimary,
                    shadowElevation = 0.dp
                ) {
                    TopAppBar(
                        title = {
                            // Logika untuk menentukan judul AppBar
                            val title = when (currentDestination?.route) {
                                Routes.PROFILE -> stringResource(R.string.title_profile)
                                // TODO: Tambahkan case untuk layar detail lain di masa depan
                                else -> {
                                    val titleResId =
                                        bottomNavItems.find { it.route == currentDestination?.route }?.titleResId
                                    stringResource(id = titleResId ?: R.string.app_name)
                                }
                            }
                            TwoToneTitle(
                                title
                            )
                        },
                        // LANGKAH 3: Tampilkan tombol kembali jika BUKAN layar top-level
                        navigationIcon = {
                            if (!isTopLevelScreen) {
                                IconButton(onClick = { navController.navigateUp() }) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Kembali",
                                        tint = MaterialTheme.customColors.textPrimary
                                    )
                                }
                            }
                        },
                        actions = {
                            // Logika untuk menampilkan tombol aksi (ke profil)
                            if (currentDestination?.route == BottomNavItem.Progress.route) {
                                IconButton(onClick = { navController.navigate(Routes.PROFILE) }) {
                                    Icon(
                                        imageVector = Icons.Default.AccountCircle,
                                        contentDescription = stringResource(R.string.action_go_to_profile),
                                        tint = MaterialTheme.customColors.textPrimary
                                    )
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.customColors.backgroundPrimary,
                            titleContentColor = MaterialTheme.customColors.textPrimary,
                            navigationIconContentColor = MaterialTheme.customColors.textPrimary,
                            actionIconContentColor = MaterialTheme.customColors.textPrimary
                        )
                    )
                }
            },
            bottomBar = {
                // LANGKAH 4: Tampilkan BottomBar HANYA JIKA INI adalah layar top-level
                if (isTopLevelScreen) {
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
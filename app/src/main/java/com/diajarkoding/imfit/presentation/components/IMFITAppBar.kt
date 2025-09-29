package com.diajarkoding.imfit.presentation.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.presentation.navigation.BottomNavItem
import com.diajarkoding.imfit.presentation.navigation.Routes
import com.diajarkoding.imfit.theme.customColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IMFITAppBar(
    currentDestination: NavDestination?,
    navController: NavController,
    topLevelRoutes: Set<String>,
    bottomNavItems: List<BottomNavItem>
) {
    // Daftar rute yang akan mengelola AppBar-nya sendiri
    val screensWithCustomAppBar = setOf(
        Routes.EDIT_WORKOUT_DAY_PREFIX
        // Tambahkan rute prefix lain di sini jika perlu AppBar kustom
    )

    val currentRoutePrefix = currentDestination?.route?.substringBefore('/')
    val isCustomAppBarScreen = currentRoutePrefix in screensWithCustomAppBar

    if (!isCustomAppBarScreen) {
        Surface(
            color = MaterialTheme.customColors.backgroundPrimary,
            shadowElevation = 0.dp
        ) {
            TopAppBar(
                title = {
                    val title =
                        when {
                            currentDestination?.route == Routes.PROFILE -> stringResource(R.string.title_profile)
                            currentDestination?.route?.startsWith(Routes.ADD_EXERCISES_PREFIX) == true -> stringResource(
                                R.string.add_exercises_title
                            )

                            else -> {
                                val titleResId =
                                    bottomNavItems.find { it.route == currentDestination?.route }?.titleResId
                                stringResource(id = titleResId ?: R.string.app_name)
                            }
                        }
                    TwoToneTitle(text = title)
                },
                navigationIcon = {
                    val isTopLevelScreen = currentDestination?.route in topLevelRoutes
                    if (!isTopLevelScreen) {
                        IconButton(onClick = { navController.navigateUp() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.action_back),
                                tint = MaterialTheme.customColors.textPrimary
                            )
                        }
                    }
                },
                actions = {
                    when {
                        currentDestination?.route == BottomNavItem.Progress.route -> {
                            IconButton(onClick = { navController.navigate(Routes.PROFILE) }) {
                                Icon(
                                    imageVector = Icons.Default.AccountCircle,
                                    contentDescription = stringResource(R.string.action_go_to_profile),
                                    tint = MaterialTheme.customColors.textPrimary
                                )
                            }
                        }

                        currentDestination?.route?.startsWith(Routes.ADD_EXERCISES_PREFIX) == true -> {
                            IconButton(onClick = { /* TODO: Buka halaman buat latihan kustom */ }) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "Tambah Latihan Kustom",
                                    tint = MaterialTheme.customColors.textPrimary
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.customColors.backgroundPrimary
                )
            )
        }
    }
}
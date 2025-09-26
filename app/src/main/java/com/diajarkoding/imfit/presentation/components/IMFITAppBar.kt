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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
    val isTopLevelScreen = currentDestination?.route in topLevelRoutes

    Surface(
        color = MaterialTheme.customColors.backgroundPrimary,
        shadowElevation = 0.dp
    ) {
        TopAppBar(
            title = {
                val backStackEntry = navController.currentBackStackEntry
                val title =
                    when { // Gunakan when tanpa argumen untuk pengecekan route yang lebih kompleks
                        currentDestination?.route == Routes.PROFILE -> stringResource(R.string.title_profile)
                        currentDestination?.route?.startsWith(Routes.EDIT_WORKOUT_DAY_PREFIX) == true -> stringResource(
                            R.string.workout_edit_day_title
                        )

                        currentDestination?.route?.startsWith(Routes.ADD_EXERCISES_PREFIX) == true -> stringResource(
                            R.string.add_exercises_title
                        ) // <-- TAMBAHKAN INI
                        else -> {
                            val titleResId =
                                bottomNavItems.find { it.route == currentDestination?.route }?.titleResId
                            stringResource(id = titleResId ?: R.string.app_name)
                        }
                    }
                TwoToneTitle(text = title)
            },
            navigationIcon = {
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

                    currentDestination?.route?.startsWith(Routes.EDIT_WORKOUT_DAY) == true -> {
                        TextButton(onClick = { /* TODO: Panggil fungsi onSaveClick */ }) {
                            Text(
                                stringResource(R.string.workout_edit_day_save),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
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

// file: presentation/navigation/MainNavigation.kt
package com.diajarkoding.imfit.presentation.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.presentation.ui.home.HomeScreen
import com.diajarkoding.imfit.presentation.ui.profile.ProfileScreen

// Perbarui object Routes
object Routes {
    const val SPLASH = "splash"
    const val LOGIN = "login"
    const val REGISTER = "register"
    const val HOME = "home_root" // Ganti nama root untuk menghindari konflik

    // Rute baru setelah login
    const val MAIN_GRAPH = "main_graph"
    const val PROFILE = "profile"
}


@Composable
fun MainNavigation(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    onLogout: () -> Unit,
    showLoading: () -> Unit,
    hideLoading: () -> Unit
) {
    NavHost(
        navController = navController,
        startDestination = BottomNavItem.Home.route,
        modifier = modifier
    ) {
        composable(BottomNavItem.Home.route) {
            HomeScreen(modifier = Modifier)
        }
        composable(BottomNavItem.Workout.route) {
            PlaceholderScreen(
                stringResource(R.string.nav_workout),
                stringResource(R.string.desc_workout_screen)
            )
        }
        composable(BottomNavItem.Exercises.route) {
            PlaceholderScreen(
                stringResource(R.string.nav_exercises),
                stringResource(R.string.desc_exercises_screen)
            )
        }
        composable(BottomNavItem.Progress.route) {
            PlaceholderScreen(
                stringResource(R.string.nav_progress),
                stringResource(R.string.desc_progress_screen)
            )
        }
        composable(Routes.PROFILE) {
            ProfileScreen(
                onLogout = onLogout,
                modifier = modifier,
                showLoading = showLoading,
                hideLoading = hideLoading
            )
        }
    }
}

@Composable
fun PlaceholderScreen(title: String, description: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "$title\n\n$description",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(24.dp)
        )
    }
}
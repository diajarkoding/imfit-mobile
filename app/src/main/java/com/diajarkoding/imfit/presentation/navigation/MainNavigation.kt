// file: presentation/navigation/MainNavigation.kt
package com.diajarkoding.imfit.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.presentation.ui.home.HomeScreen
import com.diajarkoding.imfit.presentation.ui.main.PlaceholderScreen
import com.diajarkoding.imfit.presentation.ui.profile.ProfileScreen
import com.diajarkoding.imfit.presentation.ui.workout.EditWorkoutDayScreen
import com.diajarkoding.imfit.presentation.ui.workout.WorkoutScreen

object Routes {
    const val SPLASH = "splash"
    const val LOGIN = "login"
    const val REGISTER = "register"
    const val HOME = "home_root" // Ganti nama root untuk menghindari konflik

    // Rute baru setelah login
    const val MAIN_GRAPH = "main_graph"

    const val EDIT_WORKOUT_DAY = "edit_workout_day"
    fun editWorkoutDay(dayId: String, dayName: String) = "$EDIT_WORKOUT_DAY/$dayId?dayName=$dayName"

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
            WorkoutScreen(
                navController = navController,
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

        composable(
            route = Routes.editWorkoutDay("{dayId}", "{dayName}"),

//                "${Routes.EDIT_WORKOUT_DAY}/{dayId}?dayName={dayName}"
        ) { backStackEntry ->
            val dayId = backStackEntry.arguments?.getString("dayId")
            val dayName = backStackEntry.arguments?.getString("dayName")
            EditWorkoutDayScreen(
                dayName = dayName ?: "MON",
                workoutTitle = "Push Day",
                onBackClick = { navController.navigateUp() },
                onSaveClick = { /* TODO */ },
                onAddExercise = { /* TODO */ },
                onDeleteDay = { /* TODO */ }
            )
        }

    }
}


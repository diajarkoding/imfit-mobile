// file: presentation/navigation/MainNavigation.kt
package com.diajarkoding.imfit.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.presentation.ui.exercises.AddExercisesScreen
import com.diajarkoding.imfit.presentation.ui.home.HomeScreen
import com.diajarkoding.imfit.presentation.ui.main.PlaceholderScreen
import com.diajarkoding.imfit.presentation.ui.profile.ProfileScreen
import com.diajarkoding.imfit.presentation.ui.workout.EditWorkoutDayScreen
import com.diajarkoding.imfit.presentation.ui.workout.WorkoutScreen
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object Routes {
    const val SPLASH = "splash"
    const val LOGIN = "login"
    const val REGISTER = "register"
    const val HOME = "home_root" // Ganti nama root untuk menghindari konflik

    // Rute baru setelah login
    const val MAIN_GRAPH = "main_graph"

    const val EDIT_WORKOUT_DAY_PREFIX = "edit_workout_day"
    const val EDIT_WORKOUT_DAY = "$EDIT_WORKOUT_DAY_PREFIX/{dayId}?dayName={dayName}"
    fun editWorkoutDay(dayId: String, dayName: String): String {
        // Encode dayName agar aman untuk URL
        val encodedDayName = URLEncoder.encode(dayName, StandardCharsets.UTF_8.name())
        return "$EDIT_WORKOUT_DAY_PREFIX/$dayId?dayName=$encodedDayName"
    }

    const val ADD_EXERCISES_PREFIX = "add_exercises"
    const val ADD_EXERCISES = "$ADD_EXERCISES_PREFIX/{dayId}"
    fun addExercises(dayId: String) = "$ADD_EXERCISES_PREFIX/$dayId"
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
            route = Routes.EDIT_WORKOUT_DAY,
            arguments = listOf(
                navArgument("dayId") { type = NavType.StringType },
                navArgument("dayName") { type = NavType.StringType; nullable = true }
            )
        ) { backStackEntry ->
            val dayName = backStackEntry.arguments?.getString("dayName")?.let {
                URLDecoder.decode(it, StandardCharsets.UTF_8.name())
            }
            EditWorkoutDayScreen(
                navController = navController, // Teruskan NavController
                workoutTitle = "Push Day", // TODO: Ambil dari ViewModel berdasarkan dayId
                onBackClick = { navController.navigateUp() }
            )
        }

        composable(
            route = Routes.ADD_EXERCISES,
            arguments = listOf(navArgument("dayId") { type = NavType.StringType })
        ) { backStackEntry ->
            // val dayId = backStackEntry.arguments?.getString("dayId") // Anda bisa ambil ID di sini
            AddExercisesScreen(
                onBackClick = { navController.navigateUp() },
                onAddSelectedExercises = { selectedIds ->
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set("selected_exercise_ids", selectedIds.toList())
                    navController.popBackStack()
                }
            )
        }
    }


}


package com.diajarkoding.imfit.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.diajarkoding.imfit.presentation.ui.auth.LoginScreen
import com.diajarkoding.imfit.presentation.ui.auth.RegisterScreen
import com.diajarkoding.imfit.presentation.ui.exercise.ExerciseBrowserScreen
import com.diajarkoding.imfit.presentation.ui.exercise.ExerciseSelectionScreen
import com.diajarkoding.imfit.presentation.ui.home.HomeScreen
import com.diajarkoding.imfit.presentation.ui.splash.SplashScreen
import com.diajarkoding.imfit.presentation.ui.template.CreateTemplateScreen
import com.diajarkoding.imfit.presentation.ui.workout.ActiveWorkoutScreen
import com.diajarkoding.imfit.presentation.ui.workout.WorkoutSummaryScreen

@Composable
fun NavGraph(
    navController: NavHostController = rememberNavController(),
    startDestination: String = Routes.SPLASH
) {
    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Routes.SPLASH) {
            SplashScreen(
                onNavigateToLogin = {
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(Routes.SPLASH) { inclusive = true }
                    }
                },
                onNavigateToHome = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.SPLASH) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.LOGIN) {
            LoginScreen(
                onNavigateToRegister = {
                    navController.navigate(Routes.REGISTER)
                },
                onLoginSuccess = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.REGISTER) {
            RegisterScreen(
                onNavigateToLogin = {
                    navController.navigateUp()
                },
                onRegisterSuccess = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.REGISTER) { inclusive = true }
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.HOME) { backStackEntry ->
            val refreshTrigger = backStackEntry.savedStateHandle.get<Boolean>("refresh")
            HomeScreen(
                onNavigateToCreateTemplate = {
                    navController.navigate(Routes.CREATE_TEMPLATE)
                },
                onNavigateToActiveWorkout = { templateId ->
                    navController.navigate(Routes.activeWorkout(templateId))
                },
                onLogout = {
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(Routes.HOME) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.CREATE_TEMPLATE) { backStackEntry ->
            val selectedExercises = backStackEntry.savedStateHandle.get<List<com.diajarkoding.imfit.domain.model.Exercise>>("selected_exercises")
            CreateTemplateScreen(
                onNavigateBack = { navController.navigateUp() },
                onNavigateToExerciseSelection = { templateId ->
                    navController.navigate(Routes.exerciseSelection(templateId))
                },
                onTemplateSaved = {
                    navController.navigateUp()
                },
                selectedExercises = selectedExercises
            )
        }

        composable(Routes.EXERCISE_BROWSER) {
            ExerciseBrowserScreen(
                onNavigateBack = { navController.navigateUp() }
            )
        }

        composable(
            route = Routes.EXERCISE_SELECTION,
            arguments = listOf(navArgument("templateId") { type = NavType.StringType })
        ) { backStackEntry ->
            val templateId = backStackEntry.arguments?.getString("templateId") ?: ""
            ExerciseSelectionScreen(
                templateId = templateId,
                onNavigateBack = { navController.navigateUp() },
                onExercisesSelected = { selectedExercises ->
                    navController.getBackStackEntry(Routes.CREATE_TEMPLATE)
                        .savedStateHandle
                        .set("selected_exercises", selectedExercises)
                    navController.navigateUp()
                }
            )
        }

        composable(
            route = Routes.ACTIVE_WORKOUT,
            arguments = listOf(navArgument("templateId") { type = NavType.StringType })
        ) { backStackEntry ->
            val templateId = backStackEntry.arguments?.getString("templateId") ?: ""
            ActiveWorkoutScreen(
                templateId = templateId,
                onNavigateBack = { navController.navigateUp() },
                onWorkoutFinished = { workoutLogId ->
                    navController.navigate(Routes.workoutSummary(workoutLogId)) {
                        popUpTo(Routes.HOME)
                    }
                }
            )
        }

        composable(
            route = Routes.WORKOUT_SUMMARY,
            arguments = listOf(navArgument("workoutLogId") { type = NavType.StringType })
        ) { backStackEntry ->
            val workoutLogId = backStackEntry.arguments?.getString("workoutLogId") ?: ""
            WorkoutSummaryScreen(
                workoutLogId = workoutLogId,
                onNavigateToHome = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.HOME) { inclusive = true }
                    }
                }
            )
        }
    }
}

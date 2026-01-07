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
import com.diajarkoding.imfit.presentation.ui.exercise.ExerciseListScreen
import com.diajarkoding.imfit.presentation.ui.exercise.ExerciseSelectionScreen
import com.diajarkoding.imfit.presentation.ui.home.HomeScreen
import com.diajarkoding.imfit.presentation.ui.main.MainScreen
import com.diajarkoding.imfit.presentation.ui.profile.ProfileScreen
import com.diajarkoding.imfit.presentation.ui.workout.WorkoutDetailScreen
import com.diajarkoding.imfit.presentation.ui.splash.SplashScreen
import com.diajarkoding.imfit.presentation.ui.workout.ActiveWorkoutScreen
import com.diajarkoding.imfit.presentation.ui.workout.EditWorkoutScreen
import com.diajarkoding.imfit.presentation.ui.workout.WorkoutSummaryScreen
import com.diajarkoding.imfit.presentation.ui.progress.WorkoutHistoryDetailScreen
import com.diajarkoding.imfit.presentation.ui.progress.YearlyCalendarScreen
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

@Composable
fun NavGraph(
    navController: NavHostController = rememberNavController(),
    startDestination: String = Routes.SPLASH,
    isDarkMode: Boolean = false,
    onToggleTheme: () -> Unit = {},
    isIndonesian: Boolean = true,
    onToggleLanguage: () -> Unit = {},
    openActiveWorkout: Boolean = false,
    activeWorkoutTemplateId: String? = null,
    onActiveWorkoutOpened: () -> Unit = {}
) {
    // Handle navigation to active workout from notification
    androidx.compose.runtime.LaunchedEffect(openActiveWorkout, activeWorkoutTemplateId) {
        if (openActiveWorkout) {
            // Check if we're already on ActiveWorkoutScreen
            val currentRoute = navController.currentBackStackEntry?.destination?.route
            val isAlreadyOnActiveWorkout = currentRoute?.startsWith("active_workout") == true
            
            if (!isAlreadyOnActiveWorkout) {
                // Navigate to active workout - use templateId if available, otherwise use a placeholder
                // The ActiveWorkoutScreen will check for existing session anyway
                val templateId = activeWorkoutTemplateId ?: "active"
                navController.navigate(Routes.activeWorkout(templateId)) {
                    popUpTo(Routes.MAIN) { inclusive = false }
                    launchSingleTop = true
                }
            }
            onActiveWorkoutOpened()
        }
    }

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
                    navController.navigate(Routes.MAIN) {
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
                    navController.navigate(Routes.MAIN) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                },
                isDarkMode = isDarkMode,
                onToggleTheme = onToggleTheme,
                isIndonesian = isIndonesian,
                onToggleLanguage = onToggleLanguage
            )
        }

        composable(Routes.REGISTER) {
            RegisterScreen(
                onNavigateToLogin = {
                    navController.navigateUp()
                },
                onRegisterSuccess = {
                    navController.navigate(Routes.MAIN) {
                        popUpTo(Routes.REGISTER) { inclusive = true }
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.MAIN) {
            MainScreen(
                onNavigateToWorkoutDetail = { workoutId ->
                    navController.navigate(Routes.workoutDetail(workoutId))
                },
                onNavigateToActiveWorkout = { templateId ->
                    navController.navigate(Routes.activeWorkout(templateId))
                },
                onNavigateToExerciseList = { categoryName ->
                    navController.navigate(Routes.exerciseList(categoryName))
                },
                onNavigateToWorkoutHistory = { date ->
                    navController.navigate(Routes.workoutHistory(date.toString()))
                },
                onNavigateToYearlyCalendar = {
                    navController.navigate(Routes.YEARLY_CALENDAR)
                },
                onNavigateToProfile = {
                    navController.navigate(Routes.PROFILE)
                },
                onLogout = {
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(Routes.MAIN) { inclusive = true }
                    }
                }
            )
        }

        composable(
            route = Routes.WORKOUT_DETAIL,
            arguments = listOf(navArgument("workoutId") { type = NavType.StringType })
        ) { backStackEntry ->
            val workoutId = backStackEntry.arguments?.getString("workoutId") ?: ""
            val selectedExercises = backStackEntry.savedStateHandle.get<List<com.diajarkoding.imfit.domain.model.Exercise>>("selected_exercises")
            WorkoutDetailScreen(
                workoutId = workoutId,
                onNavigateBack = { navController.navigateUp() },
                onNavigateToExerciseSelection = { id ->
                    navController.navigate(Routes.exerciseSelection(id))
                },
                onStartWorkout = { templateId ->
                    navController.navigate(Routes.activeWorkout(templateId))
                },
                onNavigateToEdit = { id ->
                    navController.navigate(Routes.editWorkout(id))
                },
                selectedExercises = selectedExercises,
                onClearSelectedExercises = {
                    backStackEntry.savedStateHandle.remove<List<com.diajarkoding.imfit.domain.model.Exercise>>("selected_exercises")
                }
            )
        }

        composable(
            route = Routes.EDIT_WORKOUT,
            arguments = listOf(navArgument("workoutId") { type = NavType.StringType })
        ) { backStackEntry ->
            val workoutId = backStackEntry.arguments?.getString("workoutId") ?: ""
            EditWorkoutScreen(
                workoutId = workoutId,
                onNavigateBack = { navController.navigateUp() }
            )
        }

        composable(
            route = Routes.EXERCISE_LIST,
            arguments = listOf(navArgument("categoryName") { type = NavType.StringType })
        ) { backStackEntry ->
            val categoryName = backStackEntry.arguments?.getString("categoryName") ?: ""
            ExerciseListScreen(
                categoryName = categoryName,
                onNavigateBack = { navController.navigateUp() }
            )
        }

        composable(Routes.EXERCISE_BROWSER) {
            ExerciseBrowserScreen(
                onNavigateBack = { navController.navigateUp() },
                onCategorySelected = { /* handled in MainScreen */ }
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
                    navController.previousBackStackEntry
                        ?.savedStateHandle
                        ?.set("selected_exercises", selectedExercises)
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
                        popUpTo(Routes.MAIN)
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
                    navController.navigate(Routes.MAIN) {
                        popUpTo(Routes.MAIN) { inclusive = true }
                    }
                }
            )
        }

        composable(
            route = Routes.WORKOUT_HISTORY,
            arguments = listOf(navArgument("date") { type = NavType.StringType })
        ) { backStackEntry ->
            val date = backStackEntry.arguments?.getString("date") ?: SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Calendar.getInstance().time)
            WorkoutHistoryDetailScreen(
                date = date,
                onNavigateBack = { navController.navigateUp() }
            )
        }

        composable(Routes.YEARLY_CALENDAR) {
            YearlyCalendarScreen(
                onNavigateBack = { navController.navigateUp() },
                onDateSelected = { date ->
                    navController.navigate(Routes.workoutHistory(date.toString()))
                }
            )
        }

        composable(Routes.PROFILE) {
            ProfileScreen(
                onNavigateBack = { navController.navigateUp() },
                onLogout = {
                    navController.navigate(Routes.LOGIN) {
                        popUpTo(Routes.MAIN) { inclusive = true }
                    }
                },
                isDarkMode = isDarkMode,
                onToggleTheme = onToggleTheme,
                isIndonesian = isIndonesian,
                onToggleLanguage = onToggleLanguage
            )
        }
    }
}

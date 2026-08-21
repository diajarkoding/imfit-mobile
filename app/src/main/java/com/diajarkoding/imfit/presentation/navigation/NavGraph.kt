package com.diajarkoding.imfit.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.diajarkoding.imfit.presentation.ui.auth.LoginScreen
import com.diajarkoding.imfit.presentation.ui.auth.RegisterScreen
import com.diajarkoding.imfit.presentation.ui.exercise.ExerciseListScreen
import com.diajarkoding.imfit.presentation.ui.exercise.ExerciseSelectionScreen
import com.diajarkoding.imfit.presentation.ui.main.MainScreen
import com.diajarkoding.imfit.presentation.ui.profile.ProfileScreen
import com.diajarkoding.imfit.presentation.ui.progress.WorkoutHistoryDetailScreen
import com.diajarkoding.imfit.presentation.ui.progress.YearlyCalendarScreen
import com.diajarkoding.imfit.presentation.ui.splash.SplashScreen
import com.diajarkoding.imfit.presentation.ui.workout.ActiveWorkoutScreen
import com.diajarkoding.imfit.presentation.ui.workout.EditWorkoutScreen
import com.diajarkoding.imfit.presentation.ui.workout.WorkoutDetailScreen
import com.diajarkoding.imfit.presentation.ui.workout.WorkoutSummaryScreen

@Composable
fun NavGraph(
    isDarkMode: Boolean = false,
    onToggleTheme: () -> Unit = {},
    isIndonesian: Boolean = true,
    onToggleLanguage: () -> Unit = {},
    openActiveWorkout: Boolean = false,
    activeWorkoutTemplateId: String? = null,
    onActiveWorkoutOpened: () -> Unit = {}
) {
    val backStack = rememberNavBackStack(Splash)
    var exerciseSelectionResult by rememberSaveable {
        mutableStateOf<ArrayList<String>?>(null)
    }

    LaunchedEffect(
        openActiveWorkout,
        activeWorkoutTemplateId,
        backStack.size,
        backStack.lastOrNull()
    ) {
        if (!openActiveWorkout || backStack.none { it is Main }) return@LaunchedEffect

        if (backStack.lastOrNull() !is ActiveWorkout) {
            backStack.trimToMain()
            backStack.add(ActiveWorkout(activeWorkoutTemplateId ?: "active"))
        }
        onActiveWorkoutOpened()
    }

    NavDisplay(
        backStack = backStack,
        onBack = {
            if (backStack.size > 1) {
                backStack.removeLastOrNull()
            }
        },
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator()
        ),
        entryProvider = entryProvider {
            entry<Splash> {
                SplashScreen(
                    onNavigateToLogin = { backStack.resetTo(Login) },
                    onNavigateToHome = { backStack.resetTo(Main) }
                )
            }

            entry<Login> {
                LoginScreen(
                    onNavigateToRegister = { backStack.add(Register) },
                    onLoginSuccess = { backStack.resetTo(Main) },
                    isDarkMode = isDarkMode,
                    onToggleTheme = onToggleTheme,
                    isIndonesian = isIndonesian,
                    onToggleLanguage = onToggleLanguage
                )
            }

            entry<Register> {
                RegisterScreen(
                    onNavigateToLogin = { backStack.removeLastOrNull() },
                    onRegisterSuccess = { backStack.resetTo(Main) }
                )
            }

            entry<Main> {
                MainScreen(
                    onNavigateToWorkoutDetail = { workoutId ->
                        backStack.add(WorkoutDetail(workoutId))
                    },
                    onNavigateToActiveWorkout = { templateId ->
                        backStack.add(ActiveWorkout(templateId))
                    },
                    onNavigateToExerciseList = { categoryName ->
                        backStack.add(ExerciseList(categoryName))
                    },
                    onNavigateToWorkoutHistory = { date ->
                        backStack.add(WorkoutHistory(date.toString()))
                    },
                    onNavigateToYearlyCalendar = {
                        backStack.add(YearlyCalendar)
                    },
                    onNavigateToProfile = {
                        backStack.add(Profile)
                    }
                )
            }

            entry<WorkoutDetail> { key ->
                WorkoutDetailScreen(
                    workoutId = key.workoutId,
                    onNavigateBack = { backStack.removeLastOrNull() },
                    onNavigateToExerciseSelection = { templateId ->
                        exerciseSelectionResult = null
                        backStack.add(ExerciseSelection(templateId))
                    },
                    onStartWorkout = { templateId ->
                        backStack.add(ActiveWorkout(templateId))
                    },
                    onNavigateToEdit = { workoutId ->
                        backStack.add(EditWorkout(workoutId))
                    },
                    selectedExerciseIds = exerciseSelectionResult,
                    onSelectedExercisesConsumed = { exerciseSelectionResult = null }
                )
            }

            entry<EditWorkout> { key ->
                EditWorkoutScreen(
                    workoutId = key.workoutId,
                    onNavigateBack = { backStack.removeLastOrNull() }
                )
            }

            entry<ExerciseList> { key ->
                ExerciseListScreen(
                    categoryName = key.categoryName,
                    onNavigateBack = { backStack.removeLastOrNull() }
                )
            }

            entry<ExerciseSelection> { key ->
                ExerciseSelectionScreen(
                    templateId = key.templateId,
                    onNavigateBack = { backStack.removeLastOrNull() },
                    onExercisesSelected = { selectedExercises ->
                        exerciseSelectionResult = ArrayList(selectedExercises.map { it.id })
                        backStack.removeLastOrNull()
                    }
                )
            }

            entry<ActiveWorkout> { key ->
                ActiveWorkoutScreen(
                    templateId = key.templateId,
                    onNavigateBack = { backStack.removeLastOrNull() },
                    onWorkoutFinished = { workoutLogId ->
                        backStack.trimToMain()
                        backStack.add(WorkoutSummary(workoutLogId))
                    }
                )
            }

            entry<WorkoutSummary> { key ->
                WorkoutSummaryScreen(
                    workoutLogId = key.workoutLogId,
                    onNavigateToHome = { backStack.resetTo(Main) }
                )
            }

            entry<WorkoutHistory> { key ->
                WorkoutHistoryDetailScreen(
                    date = key.date,
                    onNavigateBack = { backStack.removeLastOrNull() }
                )
            }

            entry<YearlyCalendar> {
                YearlyCalendarScreen(
                    onNavigateBack = { backStack.removeLastOrNull() },
                    onDateSelected = { date ->
                        backStack.add(WorkoutHistory(date.toString()))
                    }
                )
            }

            entry<Profile> {
                ProfileScreen(
                    onNavigateBack = { backStack.removeLastOrNull() },
                    onLogout = { backStack.resetTo(Login) },
                    isDarkMode = isDarkMode,
                    onToggleTheme = onToggleTheme,
                    isIndonesian = isIndonesian,
                    onToggleLanguage = onToggleLanguage
                )
            }
        }
    )
}

private fun MutableList<NavKey>.resetTo(destination: NavKey) {
    clear()
    add(destination)
}

private fun MutableList<NavKey>.trimToMain() {
    val mainIndex = indexOfLast { it is Main }
    if (mainIndex < 0) return

    while (lastIndex > mainIndex) {
        removeAt(lastIndex)
    }
}

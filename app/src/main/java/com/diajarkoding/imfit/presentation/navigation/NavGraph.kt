package com.diajarkoding.imfit.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.diajarkoding.imfit.domain.model.RoutineFlowContext
import com.diajarkoding.imfit.presentation.ui.workout.ActiveSelectionResult
import kotlinx.coroutines.launch
import java.util.UUID
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.diajarkoding.imfit.presentation.ui.auth.LoginScreen
import com.diajarkoding.imfit.presentation.ui.auth.RegisterScreen
import com.diajarkoding.imfit.presentation.ui.auth.RegisterConfirmationScreen
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
import com.diajarkoding.imfit.domain.model.RoutineFormMode
import com.diajarkoding.imfit.presentation.ui.workout.RoutineFlowManager
import com.diajarkoding.imfit.presentation.ui.workout.WorkoutSummaryScreen

@Composable
fun NavGraph(
    isDarkMode: Boolean = false,
    onToggleTheme: () -> Unit = {},
    isIndonesian: Boolean = true,
    onToggleLanguage: () -> Unit = {},
    openActiveWorkout: Boolean = false,
    activeWorkoutTemplateId: String? = null,
    onActiveWorkoutOpened: () -> Unit = {},
    authenticatedFromDeepLink: Boolean = false,
    onAuthenticatedFromDeepLinkConsumed: () -> Unit = {},
    remoteRefresh: RemoteRefreshViewModel = hiltViewModel(),
    routineFlowManager: RoutineFlowManager = hiltViewModel()
) {
    val backStack = rememberNavBackStack(Splash)
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { remoteRefresh.refresh() }
    LaunchedEffect(backStack.lastOrNull()) { remoteRefresh.refresh() }
    val scope = rememberCoroutineScope()
    val flows by routineFlowManager.flows.collectAsStateWithLifecycle()
    val activeResult by routineFlowManager.activeResult.collectAsStateWithLifecycle()
    val accountGeneration by routineFlowManager.accountGeneration.collectAsStateWithLifecycle()

    LaunchedEffect(accountGeneration) {
        if (accountGeneration > 0 && backStack.any {
                it is EditWorkout || it is SetupRoutine || it is ExerciseSelection ||
                    it is ActiveExerciseSelection || it is ActiveWorkout || it is WorkoutDetail
            }) {
            backStack.trimToMain()
        }
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

    LaunchedEffect(authenticatedFromDeepLink) {
        if (authenticatedFromDeepLink) {
            backStack.resetTo(Main)
            onAuthenticatedFromDeepLinkConsumed()
        }
    }

    NavDisplay(
        backStack = backStack,
        onBack = {
            when (val key = backStack.lastOrNull()) {
                is ExerciseSelection -> {
                    if (key.isInitial) {
                        scope.launch {
                            if (!routineFlowManager.isCurrent(key.context)) return@launch
                            routineFlowManager.clearFlow(key.context)
                            backStack.finishRoutineFlow(key.context)
                        }
                    } else backStack.removeLastOrNull()
                }
                else -> if (backStack.size > 1) backStack.removeLastOrNull()
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
                    onAuthenticated = { backStack.resetTo(Main) },
                    onCheckEmail = { backStack.resetTo(RegisterConfirmation) },
                )
            }

            entry<RegisterConfirmation> {
                RegisterConfirmationScreen(
                    onNavigateToLogin = { backStack.resetTo(Login) },
                )
            }

            entry<Main> {
                        MainScreen(
                            onRefreshRemote = remoteRefresh::refresh,
                    onNavigateToWorkoutDetail = { workoutId ->
                        backStack.add(WorkoutDetail(workoutId))
                    },
                    onNavigateToActiveWorkout = { templateId ->
                        backStack.add(ActiveWorkout(templateId))
                    },
                    onNavigateToInitialPicker = navigate@{ routineId, routineName, flowId, ownerId ->
                        val context = routineFlowManager.startFlow(routineId, RoutineFormMode.SETUP, flowId, ownerId)
                            ?: return@navigate false
                        if (backStack.none {
                                (it is ExerciseSelection && it.context == context) ||
                                    (it is SetupRoutine && it.context == context)
                            }) {
                            backStack.add(ExerciseSelection(context, routineName = routineName, isInitial = true))
                        }
                        true
                    },
                    onNavigateToExerciseList = { categoryName ->
                        backStack.add(ExerciseList(categoryName))
                    },
                    onNavigateToWorkoutHistory = { date ->
                        backStack.add(WorkoutHistory(date.toString()))
                    },
                    onNavigateToYearlyCalendar = { month ->
                        backStack.add(YearlyCalendar(month.year, month.monthValue))
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
                    onNavigateToExerciseSelection = { routineId, isSetup ->
                        scope.launch {
                            val mode = if (isSetup) RoutineFormMode.SETUP else RoutineFormMode.EDIT
                            val context = routineFlowManager.startFlow(routineId, mode) ?: return@launch
                            if (isSetup) {
                                backStack.add(ExerciseSelection(context, isInitial = true))
                            } else {
                                backStack.add(EditWorkout(context, openPicker = true))
                            }
                        }
                    },
                    onStartWorkout = { templateId -> backStack.add(ActiveWorkout(templateId)) },
                    onNavigateToEdit = { routineId ->
                        scope.launch {
                            val context = routineFlowManager.startFlow(routineId, RoutineFormMode.EDIT)
                                ?: return@launch
                            backStack.add(EditWorkout(context))
                        }
                    }
                )
            }

            entry<EditWorkout> { key ->
                EditWorkoutScreen(
                    workoutId = key.context.routineId,
                    mode = key.context.mode,
                    flowId = key.context.flowId,
                    ownerId = key.context.ownerId,
                    openPickerOnLoad = key.openPicker,
                    onNavigateBack = {
                        scope.launch {
                            if (!routineFlowManager.isCurrent(key.context)) return@launch
                            routineFlowManager.clearFlow(key.context)
                            backStack.finishRoutineFlow(key.context)
                        }
                    },
                    onSaved = {
                        scope.launch {
                            if (!routineFlowManager.isCurrent(key.context)) return@launch
                            routineFlowManager.clearFlow(key.context)
                            backStack.finishRoutineFlow(key.context)
                        }
                    },
                    onAddExercises = { _, excludedIds, name ->
                        scope.launch {
                            if (!routineFlowManager.isCurrent(key.context)) return@launch
                            backStack.add(ExerciseSelection(key.context, excludedIds, name, isInitial = false))
                        }
                    },
                    selectionResult = flows[key.context.flowId]?.takeIf { it.context == key.context }?.pendingResult,
                    onSelectionAccepted = { routineFlowManager.acknowledgeResult(key.context, it) }
                )
            }

            entry<SetupRoutine> { key ->
                EditWorkoutScreen(
                    workoutId = key.context.routineId,
                    mode = key.context.mode,
                    flowId = key.context.flowId,
                    ownerId = key.context.ownerId,
                    initialName = key.routineName,
                    onNavigateBack = {
                        scope.launch {
                            if (!routineFlowManager.isCurrent(key.context)) return@launch
                            routineFlowManager.clearFlow(key.context)
                            backStack.finishRoutineFlow(key.context)
                        }
                    },
                    onSaved = {
                        scope.launch {
                            if (!routineFlowManager.isCurrent(key.context)) return@launch
                            routineFlowManager.clearFlow(key.context)
                            backStack.finishRoutineFlow(key.context)
                        }
                    },
                    onAddExercises = { _, excludedIds, name ->
                        scope.launch {
                            if (!routineFlowManager.isCurrent(key.context)) return@launch
                            backStack.add(ExerciseSelection(key.context, excludedIds, name, isInitial = false))
                        }
                    },
                    selectionResult = flows[key.context.flowId]?.takeIf { it.context == key.context }?.pendingResult,
                    onSelectionAccepted = { routineFlowManager.acknowledgeResult(key.context, it) }
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
                    templateId = key.context.routineId,
                    selectionContextId = key.context.flowId,
                    excludedExerciseIds = key.excludedIds.toSet(),
                    routineName = key.routineName,
                    onNavigateBack = {
                        if (key.isInitial) {
                            scope.launch {
                                if (!routineFlowManager.isCurrent(key.context)) return@launch
                                routineFlowManager.clearFlow(key.context)
                                backStack.finishRoutineFlow(key.context)
                            }
                        } else if (backStack.lastOrNull() == key) backStack.removeLastOrNull()
                    },
                    onExercisesSelected = { selectedExercises ->
                        scope.launch {
                            if (backStack.lastOrNull() != key) return@launch
                            if (!routineFlowManager.updateSelection(key.context, selectedExercises)) return@launch
                            if (key.isInitial) {
                                backStack[backStack.lastIndex] = SetupRoutine(key.context, key.routineName.orEmpty())
                            } else {
                                backStack.removeLastOrNull()
                            }
                        }
                    }
                )
            }

            entry<ActiveExerciseSelection> { key ->
                ExerciseSelectionScreen(
                    templateId = "",
                    selectionContextId = key.pickerId,
                    excludedExerciseIds = key.excludedIds.toSet(),
                    onNavigateBack = {
                        if (backStack.lastOrNull() == key) backStack.removeLastOrNull()
                    },
                    onExercisesSelected = { selected ->
                        scope.launch {
                            if (backStack.lastOrNull() != key) return@launch
                            val result = ActiveSelectionResult(
                                key.ownerId, key.sessionId, key.pickerId, UUID.randomUUID().toString(),
                                selected.map { it.id }
                            )
                            if (routineFlowManager.publishActiveResult(result)) backStack.removeLastOrNull()
                        }
                    }
                )
            }

            entry<ActiveWorkout> { key ->
                ActiveWorkoutScreen(
                    templateId = key.templateId,
                    onNavigateBack = { backStack.removeLastOrNull() },
                    selectionSessionId = activeResult?.sessionId,
                    selectionOwnerId = activeResult?.ownerId,
                    selectionResultId = activeResult?.resultId,
                    selectedExerciseIds = activeResult?.selectedExerciseIds,
                    onSelectedExercisesConsumed = {
                        activeResult?.resultId?.let(routineFlowManager::acknowledgeActiveResult)
                    },
                    onAddExercises = { sessionId, existingIds ->
                        scope.launch {
                            val owner = routineFlowManager.currentOwnerId() ?: return@launch
                            backStack.add(ActiveExerciseSelection(sessionId, existingIds, owner, UUID.randomUUID().toString()))
                        }
                    },
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

            entry<YearlyCalendar> { key ->
                YearlyCalendarScreen(
                    initialYear = key.year,
                    initialMonth = key.month,
                    onNavigateBack = { backStack.removeLastOrNull() },
                    onDateSelected = { date ->
                        backStack.add(WorkoutHistory(date.toString()))
                    }
                )
            }

            entry<Profile> {
                ProfileScreen(
                    onNavigateBack = { backStack.removeLastOrNull() },
                    onLogout = {
                        routineFlowManager.clear()
                        backStack.resetTo(Login)
                    },
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

/** Replace the entire abandoned/completed flow, reusing the existing Detail position. */
internal fun MutableList<NavKey>.finishRoutineFlow(
    context: RoutineFlowContext,
    refreshId: String = UUID.randomUUID().toString()
) {
    val detailIndex = indexOfFirst { it is WorkoutDetail && it.workoutId == context.routineId }
    val flowIndex = indexOfFirst {
        when (it) {
            is EditWorkout -> it.context == context
            is SetupRoutine -> it.context == context
            is ExerciseSelection -> it.context == context
            else -> false
        }
    }
    val replaceFrom = if (detailIndex >= 0) detailIndex else flowIndex
    if (replaceFrom >= 0) {
        while (size > replaceFrom) removeAt(lastIndex)
    }
    add(WorkoutDetail(context.routineId, refreshId))
}

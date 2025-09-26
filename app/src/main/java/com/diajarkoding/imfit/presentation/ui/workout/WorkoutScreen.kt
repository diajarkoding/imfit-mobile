package com.diajarkoding.imfit.presentation.ui.workout

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import com.diajarkoding.imfit.presentation.ui.workout.viewmodel.WorkoutEvent
import com.diajarkoding.imfit.presentation.ui.workout.viewmodel.WorkoutScreenMode
import com.diajarkoding.imfit.presentation.ui.workout.viewmodel.WorkoutViewModel
import com.diajarkoding.imfit.presentation.ui.workout.views.AddWorkoutView
import com.diajarkoding.imfit.presentation.ui.workout.views.EmptyStateView
import com.diajarkoding.imfit.presentation.ui.workout.views.PlannedWorkoutView

@Composable
fun WorkoutScreen(
    modifier: Modifier = Modifier,
    viewModel: WorkoutViewModel = hiltViewModel(),
    navController: NavHostController
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) {
        navController.currentBackStackEntryFlow.collect { backStackEntry ->
            backStackEntry.savedStateHandle.get<List<String>>("selected_exercise_ids")?.let { ids ->
                viewModel.onEvent(WorkoutEvent.ExercisesAdded(ids))
                // Hapus state agar tidak terpicu lagi
                backStackEntry.savedStateHandle.remove<List<String>>("selected_exercise_ids")
            }
        }
    }

    LaunchedEffect(key1 = state.navigateTo) {
        state.navigateTo?.let { route ->
            navController.navigate(route)
            viewModel.onEvent(WorkoutEvent.NavigationHandled)
        }
    }

    when (state.screenMode) {
        WorkoutScreenMode.EMPTY -> {
            EmptyStateView(
                modifier = modifier,
                onFindPlan = { /* TODO */ },
                onCreateFromScratch = {
                    viewModel.onEvent(WorkoutEvent.CreatePlanFromScratch)
                }
            )
        }

        WorkoutScreenMode.ADD_PLAN -> {
            AddWorkoutView(
                title = state.plan?.title ?: "",
                imageUrl = state.plan?.imageUrl ?: "",
                onAddDay = { viewModel.onEvent(WorkoutEvent.AddDayClicked) }
            )
        }

        WorkoutScreenMode.PLANNED -> {
            val plan = state.plan!!
            PlannedWorkoutView(
                modifier = modifier,
                plan = plan,
                currentView = state.currentView,
                isDropdownExpanded = state.isDropdownMenuExpanded,
                onDayClick = { day -> viewModel.onEvent(WorkoutEvent.DayCardClicked(day)) },
                onTabSelected = { index -> viewModel.onEvent(WorkoutEvent.TabSelected(index)) },
                onAddDay = { viewModel.onEvent(WorkoutEvent.AddDayClicked) },
                onMoreMenuClick = { day -> viewModel.onEvent(WorkoutEvent.MoreMenuClicked(day)) },
                onDismissDropdown = { viewModel.onEvent(WorkoutEvent.DismissDropdownMenu) },
                onEditDayClicked = { day -> viewModel.onEvent(WorkoutEvent.EditDayClicked(day)) },
                onAddExercise = { viewModel.onEvent(WorkoutEvent.AddExerciseClicked) }
            )
        }
    }
}

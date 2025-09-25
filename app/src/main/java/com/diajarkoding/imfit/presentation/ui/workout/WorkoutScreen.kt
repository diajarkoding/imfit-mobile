package com.diajarkoding.imfit.presentation.ui.workout

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.diajarkoding.imfit.presentation.ui.workout.views.AddWorkoutView
import com.diajarkoding.imfit.presentation.ui.workout.views.EmptyStateView
import com.diajarkoding.imfit.presentation.ui.workout.views.PlannedWorkoutView


@Composable
fun WorkoutScreen(
    modifier: Modifier = Modifier,
    viewModel: WorkoutViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    Column(modifier = modifier.fillMaxSize()) {
        when (val uiState = state.uiState) {
            is WorkoutUiState.Empty -> EmptyStateView(
                onFindPlan = { /* TODO */ },
                onCreateFromScratch = { viewModel.simulateStateChange() }
            )
            is WorkoutUiState.AddWorkout -> AddWorkoutView(
                title = uiState.planTitle,
                imageUrl = uiState.imageUrl,
                onAddDay = { viewModel.simulateStateChange() }
            )
            is WorkoutUiState.PlannedWorkout -> PlannedWorkoutView(
                plan = uiState.plan,
                selectedTabIndex = state.selectedContentTabIndex,
                onTabSelected = { /* TODO */ },
                onAddDay = { viewModel.simulateStateChange() }
            )
        }
    }
}
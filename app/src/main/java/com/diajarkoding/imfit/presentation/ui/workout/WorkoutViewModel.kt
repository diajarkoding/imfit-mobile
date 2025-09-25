package com.diajarkoding.imfit.presentation.ui.workout

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

// --- Data Models (bisa dipindah ke domain/model nanti) ---
data class WorkoutDay(
    val id: String,
    val title: String,
    val estimatedTime: String,
    val exerciseCount: String,
    val status: String,
)

data class WorkoutPlan(
    val id: String,
    val title: String,
    val days: List<WorkoutDay>,
    val imageUrl: String,
    val maxDays: Int = 7
)

// --- State untuk merepresentasikan kondisi UI ---
sealed class WorkoutUiState {
    object Empty : WorkoutUiState()
    data class AddWorkout(val planTitle: String, val imageUrl: String) : WorkoutUiState()
    data class PlannedWorkout(val plan: WorkoutPlan) : WorkoutUiState()
}

data class WorkoutState(
    val selectedMainTabIndex: Int = 1, // Default ke tab "Planned"
    val selectedContentTabIndex: Int = 0, // Default ke tab "Overview"
    val uiState: WorkoutUiState = WorkoutUiState.Empty // Kondisi awal adalah empty
)

@HiltViewModel
class WorkoutViewModel @Inject constructor() : ViewModel() {
    private val _state = MutableStateFlow(WorkoutState())
    val state = _state.asStateFlow()

    // TODO: Ganti dengan logika dari API. Ini hanya untuk simulasi.
    fun simulateStateChange() {
        val currentState = _state.value.uiState
        _state.value = _state.value.copy(
            uiState = when (currentState) {
                is WorkoutUiState.Empty -> WorkoutUiState.AddWorkout(
                    "New Workout Plan",
                    "dummy_url"
                )

                is WorkoutUiState.AddWorkout -> WorkoutUiState.PlannedWorkout(
                    plan = WorkoutPlan(
                        id = "1",
                        title = "Push Pull Workout",
                        imageUrl = "dummy_url",
                        days = listOf(
                            WorkoutDay("d1", "TUE Legs", "57m", "6 exercises", "Completed"),
                            WorkoutDay("d2", "ANY Push", "1h 49m", "14 exercises", "Not Started"),
                            WorkoutDay("d3", "ANY Pull", "1h 38m", "13 exercises", "Not Started")
                        )
                    )
                )

                is WorkoutUiState.PlannedWorkout -> WorkoutUiState.Empty
            }
        )
    }
}
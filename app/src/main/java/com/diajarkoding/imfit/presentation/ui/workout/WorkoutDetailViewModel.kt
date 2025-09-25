package com.diajarkoding.imfit.presentation.ui.workout

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

// --- State & Actions ---
data class WorkoutDetailState(
    val dayTitle: String = "Workout Day #1",
    val dayTag: String = "MON",
    val estimatedTime: String = "0m",
    val exerciseCount: Int = 0,
    val showOptionsPopup: Boolean = false,
    val isEditing: Boolean = false
)

sealed class WorkoutDetailEvent {
    object MoreOptionsClicked : WorkoutDetailEvent()
    object DismissPopup : WorkoutDetailEvent()
    object EditDayClicked : WorkoutDetailEvent()
    object CopyDayClicked : WorkoutDetailEvent()
    object DeleteDayClicked : WorkoutDetailEvent()
    object AddExerciseClicked : WorkoutDetailEvent()
    object SaveChangesClicked : WorkoutDetailEvent()
    object BackFromEditClicked : WorkoutDetailEvent()
}

@HiltViewModel
class WorkoutDetailViewModel @Inject constructor() : ViewModel() {
    private val _state = MutableStateFlow(WorkoutDetailState())
    val state = _state.asStateFlow()

    fun onEvent(event: WorkoutDetailEvent) {
        when (event) {
            WorkoutDetailEvent.MoreOptionsClicked -> _state.update { it.copy(showOptionsPopup = true) }
            WorkoutDetailEvent.DismissPopup -> _state.update { it.copy(showOptionsPopup = false) }
            WorkoutDetailEvent.EditDayClicked -> _state.update { it.copy(isEditing = true, showOptionsPopup = false) }
            WorkoutDetailEvent.BackFromEditClicked -> _state.update { it.copy(isEditing = false) }
            // TODO: Implement logic for Copy, Delete, Add, Save
            else -> {}
        }
    }
}
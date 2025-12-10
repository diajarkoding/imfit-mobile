package com.diajarkoding.imfit.presentation.ui.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diajarkoding.imfit.domain.model.WorkoutLog
import com.diajarkoding.imfit.domain.repository.WorkoutRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WorkoutSummaryState(
    val workoutLog: WorkoutLog? = null
)

@HiltViewModel
class WorkoutSummaryViewModel @Inject constructor(
    private val workoutRepository: WorkoutRepository
) : ViewModel() {

    private val _state = MutableStateFlow(WorkoutSummaryState())
    val state = _state.asStateFlow()

    fun loadWorkoutLog(workoutLogId: String) {
        viewModelScope.launch {
            val log = workoutRepository.getWorkoutLogById(workoutLogId)
            _state.update { it.copy(workoutLog = log) }
        }
    }
}

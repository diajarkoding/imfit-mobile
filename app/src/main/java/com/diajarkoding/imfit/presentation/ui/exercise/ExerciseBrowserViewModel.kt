package com.diajarkoding.imfit.presentation.ui.exercise

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diajarkoding.imfit.domain.model.Exercise
import com.diajarkoding.imfit.domain.model.MuscleCategory
import com.diajarkoding.imfit.domain.repository.ExerciseRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ExerciseBrowserState(
    val exercisesByCategory: Map<MuscleCategory, List<Exercise>> = emptyMap(),
    val isLoading: Boolean = false
)

@HiltViewModel
class ExerciseBrowserViewModel @Inject constructor(
    private val exerciseRepository: ExerciseRepository
) : ViewModel() {

    private val _state = MutableStateFlow(ExerciseBrowserState())
    val state = _state.asStateFlow()

    init {
        loadExercises()
    }

    private fun loadExercises() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true)
            val exercisesByCategory = MuscleCategory.entries.associateWith { category ->
                exerciseRepository.getExercisesByCategory(category).distinctBy { it.id }
            }
            _state.value = ExerciseBrowserState(exercisesByCategory = exercisesByCategory, isLoading = false)
        }
    }
}

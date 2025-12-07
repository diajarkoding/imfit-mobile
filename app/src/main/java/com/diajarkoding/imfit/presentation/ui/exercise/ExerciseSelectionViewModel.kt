package com.diajarkoding.imfit.presentation.ui.exercise

import androidx.lifecycle.ViewModel
import com.diajarkoding.imfit.domain.model.Exercise
import com.diajarkoding.imfit.domain.model.MuscleCategory
import com.diajarkoding.imfit.domain.repository.ExerciseRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class ExerciseSelectionState(
    val allExercises: List<Exercise> = emptyList(),
    val filteredExercises: List<Exercise> = emptyList(),
    val selectedExercises: List<Exercise> = emptyList(),
    val selectedCategory: MuscleCategory? = null
)

@HiltViewModel
class ExerciseSelectionViewModel @Inject constructor(
    private val exerciseRepository: ExerciseRepository
) : ViewModel() {

    private val _state = MutableStateFlow(ExerciseSelectionState())
    val state = _state.asStateFlow()

    init {
        loadExercises()
    }

    private fun loadExercises() {
        val exercises = exerciseRepository.getAllExercises()
        _state.update {
            it.copy(
                allExercises = exercises,
                filteredExercises = exercises
            )
        }
    }

    fun selectCategory(category: MuscleCategory?) {
        val allExercises = _state.value.allExercises
        val filtered = if (category == null) {
            allExercises
        } else {
            allExercises.filter { it.muscleCategory == category }
        }

        _state.update {
            it.copy(
                selectedCategory = category,
                filteredExercises = filtered
            )
        }
    }

    fun toggleExercise(exercise: Exercise) {
        val currentSelected = _state.value.selectedExercises.toMutableList()

        if (currentSelected.contains(exercise)) {
            currentSelected.remove(exercise)
        } else {
            currentSelected.add(exercise)
        }

        _state.update { it.copy(selectedExercises = currentSelected) }
    }
}

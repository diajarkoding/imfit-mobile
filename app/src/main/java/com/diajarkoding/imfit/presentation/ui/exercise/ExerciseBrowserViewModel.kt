package com.diajarkoding.imfit.presentation.ui.exercise

import androidx.lifecycle.ViewModel
import com.diajarkoding.imfit.domain.model.Exercise
import com.diajarkoding.imfit.domain.model.MuscleCategory
import com.diajarkoding.imfit.domain.repository.ExerciseRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

data class ExerciseBrowserState(
    val exercisesByCategory: Map<MuscleCategory, List<Exercise>> = emptyMap()
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
        val exercisesByCategory = MuscleCategory.entries.associateWith { category ->
            exerciseRepository.getExercisesByCategory(category)
        }
        _state.value = ExerciseBrowserState(exercisesByCategory = exercisesByCategory)
    }
}

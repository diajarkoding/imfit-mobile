package com.diajarkoding.imfit.presentation.ui.exercise

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diajarkoding.imfit.domain.model.Exercise
import com.diajarkoding.imfit.domain.model.MuscleCategory
import com.diajarkoding.imfit.domain.repository.ExerciseRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ExerciseSelectionState(
    val allExercises: List<Exercise> = emptyList(),
    val filteredExercises: List<Exercise> = emptyList(),
    val selectedExercises: List<Exercise> = emptyList(),
    val selectedCategory: MuscleCategory? = null,
    val searchQuery: String = "",
    val isLoading: Boolean = false
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
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            val exercises = exerciseRepository.getAllExercises()
            _state.update {
                it.copy(
                    allExercises = exercises,
                    filteredExercises = exercises,
                    isLoading = false
                )
            }
        }
    }

    fun selectCategory(category: MuscleCategory?) {
        _state.update { it.copy(selectedCategory = category) }
        applyFilters()
    }

    fun onSearchQueryChange(query: String) {
        _state.update { it.copy(searchQuery = query) }
        applyFilters()
    }

    private fun applyFilters() {
        val allExercises = _state.value.allExercises
        val category = _state.value.selectedCategory
        val query = _state.value.searchQuery.lowercase().trim()

        val filtered = allExercises.filter { exercise ->
            val matchesCategory = category == null || exercise.muscleCategory == category
            val matchesSearch = query.isEmpty() || 
                exercise.name.lowercase().contains(query) ||
                exercise.muscleCategory.displayName.lowercase().contains(query)
            matchesCategory && matchesSearch
        }

        _state.update { it.copy(filteredExercises = filtered) }
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

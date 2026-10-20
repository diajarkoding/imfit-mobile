package com.diajarkoding.imfit.presentation.ui.exercise

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.domain.model.Exercise
import com.diajarkoding.imfit.domain.repository.ExerciseRepository
import com.diajarkoding.imfit.domain.repository.WorkoutRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ExerciseSelectionState(
    val allExercises: List<Exercise> = emptyList(),
    val filteredExercises: List<Exercise> = emptyList(),
    val selectedExercises: List<Exercise> = emptyList(),
    val selectedCategory: ExerciseCategory = ExerciseCategory.ALL,
    val searchQuery: String = "",
    val routineName: String = "",
    val isLoading: Boolean = true,
    @StringRes val errorRes: Int? = null
) {
    internal fun filtered(query: String = searchQuery, category: ExerciseCategory = selectedCategory): ExerciseSelectionState =
        copy(searchQuery = query, selectedCategory = category, filteredExercises = allExercises.filter { exercise ->
            category.includes(exercise) && (query.isBlank() || exercise.name.contains(query.trim(), ignoreCase = true))
        })

    internal fun toggled(exercise: Exercise): ExerciseSelectionState = copy(selectedExercises =
        if (selectedExercises.any { it.id == exercise.id }) selectedExercises.filterNot { it.id == exercise.id }
        else (selectedExercises + exercise).distinctBy { it.id })
}

@HiltViewModel
class ExerciseSelectionViewModel @Inject constructor(
    private val exerciseRepository: ExerciseRepository,
    private val workoutRepository: WorkoutRepository
) : ViewModel() {
    private val _state = MutableStateFlow(ExerciseSelectionState())
    val state = _state.asStateFlow()
    private var contextId: String? = null
    private var exclusions: Set<String> = emptySet()
    private var requestedExclusions: Set<String>? = null
    private var draftName: String? = null
    private var loadJob: Job? = null

    fun initialize(templateId: String, excludedIds: Set<String>? = null, routineName: String? = null) {
        if (contextId == templateId && requestedExclusions == excludedIds && draftName == routineName) return
        contextId = templateId
        requestedExclusions = excludedIds
        draftName = routineName
        _state.update { it.copy(selectedExercises = emptyList()) }
        retry()
    }

    fun retry() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = true, errorRes = null) }
            try {
                val template = contextId?.takeIf { it.isNotBlank() && !it.startsWith("temp_") }?.let { workoutRepository.getTemplateById(it) }
                exclusions = requestedExclusions ?: template?.exercises?.map { it.id }?.toSet().orEmpty()
                val exercises = exerciseRepository.getAllExercises().distinctBy { it.id }.filterNot { it.id in exclusions }
                _state.update { state -> state.copy(allExercises = exercises, routineName = draftName ?: template?.name.orEmpty(),
                    selectedExercises = state.selectedExercises.filter { selected -> exercises.any { it.id == selected.id } }, isLoading = false) }
                applyFilters()

                val refreshed = exerciseRepository.refreshExercises()
                if (refreshed) {
                    val freshExercises = exerciseRepository.getAllExercises().distinctBy { it.id }.filterNot { it.id in exclusions }
                    _state.update { state -> state.copy(allExercises = freshExercises,
                        selectedExercises = state.selectedExercises.filter { selected -> freshExercises.any { it.id == selected.id } }) }
                    applyFilters()
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _state.update { it.copy(isLoading = false, errorRes = R.string.routine_catalog_failed) } }
        }
    }

    fun selectCategory(category: ExerciseCategory) {
        _state.update { it.filtered(category = category) }
    }

    fun onSearchQueryChange(query: String) {
        _state.update { it.filtered(query = query) }
    }

    fun resetSelection() { _state.update { it.copy(selectedExercises = emptyList()) } }
    fun clearFilters() {
        _state.update { it.filtered(query = "", category = ExerciseCategory.ALL) }
    }

    private fun applyFilters() {
        _state.update { it.filtered() }
    }

    fun toggleExercise(exercise: Exercise) {
        if (_state.value.isLoading || exercise.id in exclusions) return
        _state.update { it.toggled(exercise) }
    }
}

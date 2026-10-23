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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

data class ExerciseBrowserState(
    val exercisesByCategory: Map<MuscleCategory, List<Exercise>> = emptyMap(),
    val isLoading: Boolean = false,
    val query: String = "",
    val results: List<Exercise> = emptyList(),
    val hasError: Boolean = false
)

@HiltViewModel
class ExerciseBrowserViewModel @Inject constructor(
    private val exerciseRepository: ExerciseRepository
) : ViewModel() {

    private val _state = MutableStateFlow(ExerciseBrowserState())
    val state = _state.asStateFlow()
    private var loadJob: Job? = null
    private var allExercises: List<Exercise> = emptyList()

    init {
        viewModelScope.launch {
            try { exerciseRepository.observeExercises().collect { publish(it) } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { _state.update { it.copy(isLoading = false, hasError = true) } }
        }
        loadExercises()
    }

    fun setQuery(query: String) {
        _state.value = _state.value.copy(query = query)
        publish(allExercises)
    }

    fun refresh() = loadExercises()

    private fun publish(records: List<Exercise>) {
        allExercises = records
        _state.update { current ->
            val results = records.distinctBy { it.id }.filter {
                current.query.isBlank() || it.name.contains(current.query.trim(), ignoreCase = true)
            }.sortedBy { it.id }
            current.copy(results = results, exercisesByCategory = results.groupBy { it.muscleCategory }, isLoading = false, hasError = false)
        }
    }

    private fun loadExercises() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, hasError = false)
            try {
                publish(exerciseRepository.getAllExercises())
                exerciseRepository.refreshExercises()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.value = _state.value.copy(isLoading = false, hasError = true)
            }
        }
    }
}

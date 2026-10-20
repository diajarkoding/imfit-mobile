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
import kotlinx.coroutines.delay
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

    init {
        loadExercises()
    }

    fun setQuery(query: String) {
        _state.value = _state.value.copy(query = query)
        loadExercises(debounce = true)
    }

    fun refresh() = loadExercises()

    private fun loadExercises(debounce: Boolean = false) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, hasError = false)
            try {
                if (debounce) delay(300)
                val query = _state.value.query.trim()
                val records = if (query.isEmpty()) exerciseRepository.getAllExercises() else exerciseRepository.searchExercises(query)
                val results = records.distinctBy { it.id }.sortedBy { it.id }
                _state.value = _state.value.copy(results = results,
                    exercisesByCategory = results.groupBy { it.muscleCategory }, isLoading = false)

                if (query.isEmpty()) {
                    val refreshed = exerciseRepository.refreshExercises()
                    if (refreshed) {
                        val freshRecords = exerciseRepository.getAllExercises()
                        val freshResults = freshRecords.distinctBy { it.id }.sortedBy { it.id }
                        _state.value = _state.value.copy(
                            results = freshResults,
                            exercisesByCategory = freshResults.groupBy { it.muscleCategory }
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.value = _state.value.copy(isLoading = false, hasError = true)
            }
        }
    }
}

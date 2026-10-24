package com.diajarkoding.imfit.presentation.ui.exercise

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.cachedIn
import com.diajarkoding.imfit.domain.model.Exercise
import com.diajarkoding.imfit.domain.model.MuscleCategory
import com.diajarkoding.imfit.domain.repository.ExerciseRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

data class ExerciseBrowserState(
    val exercisesByCategory: Map<MuscleCategory, List<Exercise>> = emptyMap(),
    val isLoading: Boolean = false,
    val query: String = "",
    val results: List<Exercise> = emptyList(), // Preview/fixture presentation; runtime rows come from Room Paging.
    val hasError: Boolean = false,
    val isRefreshing: Boolean = false,
)

@HiltViewModel
class ExerciseBrowserViewModel @Inject constructor(
    private val exerciseRepository: ExerciseRepository
) : ViewModel() {
    private val _state = MutableStateFlow(ExerciseBrowserState())
    val state = _state.asStateFlow()
    private val category = MutableStateFlow(ExerciseCategory.ALL)
    private var refreshJob: Job? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    val exercises = combine(category, state.map { it.query }.distinctUntilChanged()) { category, query ->
        category.catalogFilter(query)
    }.distinctUntilChanged().flatMapLatest(exerciseRepository::pageExercises).cachedIn(viewModelScope)

    init { onResume() }

    fun setCategory(value: ExerciseCategory) { category.value = value }
    fun setQuery(query: String) { _state.update { it.copy(query = query) } }
    fun refresh() = requestRefresh(force = true)
    fun onResume() = requestRefresh(force = false)

    private fun requestRefresh(force: Boolean) {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            _state.update { it.copy(isRefreshing = true, hasError = false) }
            try {
                val success = exerciseRepository.refreshExercises(force)
                _state.update { it.copy(hasError = !success) }
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { _state.update { it.copy(hasError = true) } }
            finally { _state.update { it.copy(isRefreshing = false) } }
        }
    }
}

package com.diajarkoding.imfit.presentation.ui.exercise

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diajarkoding.imfit.domain.model.Exercise
import com.diajarkoding.imfit.domain.model.MuscleCategory
import com.diajarkoding.imfit.data.remote.online.*
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
    val hasError: Boolean = false,
    val refreshing: Boolean = false,
    val stale: Boolean = false,
    val appending: Boolean = false,
    val appendError: Boolean = false,
    val endReached: Boolean = false
)

@HiltViewModel
class ExerciseBrowserViewModel @Inject constructor(
    gateway: RemoteReadGateway
) : ViewModel() {

    private val _state = MutableStateFlow(ExerciseBrowserState())
    val state = _state.asStateFlow()
    private val catalog = RemoteCatalogController(gateway, viewModelScope)
    private var category = ExerciseCategory.ALL
    private val authListener = gateway.auth.onIdentityChanged { _state.value = ExerciseBrowserState(query = _state.value.query, isLoading = true) }

    init {
        viewModelScope.launch {
            catalog.feed.state.collect { page ->
                gateway.auth.synchronizedState {
                val identity = page.identity ?: return@synchronizedState
                if (!gateway.auth.isCurrent(identity)) return@synchronizedState
                val results = page.items.map { it.toExercise() }.filter(category::includes)
                _state.update { it.copy(results = results, exercisesByCategory = results.groupBy { e -> e.muscleCategory },
                    isLoading = page.refreshing && !page.hasLoaded, hasError = page.refreshError != null,
                    refreshing = page.refreshing, stale = page.stale, appending = page.appending,
                    appendError = page.appendError != null, endReached = page.endReached) }
                }
            }
        }
    }

    fun initialize(category: ExerciseCategory) { this.category = category; catalog.select(category, _state.value.query) }

    fun setQuery(query: String) {
        val bounded = query.take(100)
        val changed = normalizeSearch(_state.value.query) != normalizeSearch(bounded)
        _state.value = if (changed) ExerciseBrowserState(query = bounded, isLoading = true) else _state.value.copy(query = bounded)
        catalog.select(category, bounded, debounce = true)
    }

    fun refresh() = catalog.refresh()
    fun retry() = catalog.retry()
    fun access(id: String) = catalog.access(id)
    fun loadMore() { catalog.feed.state.value.items.lastOrNull()?.let { catalog.access(it.id) } }
    override fun onCleared() { catalog.close(); authListener.close() }
}

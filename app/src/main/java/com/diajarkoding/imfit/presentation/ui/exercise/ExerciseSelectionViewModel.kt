package com.diajarkoding.imfit.presentation.ui.exercise

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.domain.model.Exercise
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import javax.inject.Inject
import com.diajarkoding.imfit.data.remote.online.*

data class ExerciseSelectionState(
    val allExercises: List<Exercise> = emptyList(),
    val filteredExercises: List<Exercise> = emptyList(),
    val selectedExercises: List<Exercise> = emptyList(),
    val selectedCategory: ExerciseCategory = ExerciseCategory.ALL,
    val searchQuery: String = "",
    val routineName: String = "",
    val isLoading: Boolean = true,
    @StringRes val errorRes: Int? = null,
    val refreshing: Boolean = false,
    val stale: Boolean = false,
    val appending: Boolean = false,
    val appendError: Boolean = false,
    val endReached: Boolean = false
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
    private val gateway: RemoteReadGateway
) : ViewModel() {
    private val _state = MutableStateFlow(ExerciseSelectionState())
    val state = _state.asStateFlow()
    private var contextId: String? = null
    private var contextOwner: String? = null
    private var exclusions: Set<String> = emptySet()
    private var requestedExclusions: Set<String>? = null
    private var draftName: String? = null
    private var loadJob: Job? = null
    private var contextReady = false
    private var contextGeneration = 0L
    private val catalog = RemoteCatalogController(gateway, viewModelScope)
    private val authListener = gateway.auth.onIdentityChanged {
        contextGeneration++
        loadJob?.cancel()
        _state.value = ExerciseSelectionState(isLoading = false, errorRes = R.string.routine_catalog_failed)
        contextReady = false
        exclusions = emptySet()
    }
    init {
        viewModelScope.launch { gateway.auth.identity.collect { identity ->
            if (identity != null && identity.ownerId == contextOwner && !contextReady) contextId?.let { id ->
                if (loadJob?.isActive != true) reloadContext(id)
            }
        } }
        viewModelScope.launch { catalog.feed.state.collect(::publishPage) }
    }

    private fun publishPage(page: RemoteListState<ExerciseSummary>) = gateway.auth.synchronizedState {
        if (contextId == null || !contextReady) return@synchronizedState
        val identity = page.identity ?: return@synchronizedState
        if (!gateway.auth.isCurrent(identity)) return@synchronizedState
        val rows = page.items.map { it.toExercise() }.filterNot { it.id in exclusions }.filter(_state.value.selectedCategory::includes)
        _state.update { it.copy(allExercises = rows, filteredExercises = rows,
            isLoading = page.refreshing && !page.hasLoaded,
            errorRes = if (page.refreshError != null) R.string.routine_catalog_failed else null,
            refreshing = page.refreshing, stale = page.stale, appending = page.appending,
            appendError = page.appendError != null, endReached = page.endReached) }
    }

    fun initialize(templateId: String, excludedIds: Set<String>? = null, routineName: String? = null) {
        val owner = gateway.auth.identity.value?.ownerId
        if (contextId == templateId && contextOwner == owner && requestedExclusions == excludedIds && draftName == routineName) return
        contextId = templateId
        contextOwner = owner
        val ticket = ++contextGeneration
        contextReady = false
        requestedExclusions = excludedIds
        draftName = routineName
        exclusions = emptySet()
        _state.update { it.copy(selectedExercises = emptyList(), filteredExercises = emptyList(), allExercises = emptyList(), isLoading = true,
            errorRes = null, refreshing = false, stale = false, appending = false, appendError = false, endReached = false) }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                val identity = gateway.auth.capture().identity
                if (identity.ownerId != contextOwner) throw StaleAuthException()
                val template = if (excludedIds == null && templateId.isNotBlank() && !templateId.startsWith("temp_")) gateway.read(OnlineRpc.templateDetail(templateId)) else null
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (!gateway.auth.isCurrent(identity) || ticket != contextGeneration) return@launch
                exclusions = excludedIds ?: template?.exercises?.map { it.exerciseId }?.toSet().orEmpty()
                contextReady = true
                _state.update { it.copy(routineName = routineName ?: template?.name.orEmpty()) }
                catalog.select(_state.value.selectedCategory, _state.value.searchQuery)
                publishPage(catalog.feed.state.value)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (ticket == contextGeneration) _state.update { it.copy(isLoading = false, errorRes = R.string.routine_catalog_failed) }
            }
        }
    }

    fun retry() {
        if (gateway.auth.identity.value?.ownerId != contextOwner) return
        if (!contextReady && contextId != null) {
            reloadContext(contextId!!)
        } else catalog.retry()
    }

    private fun reloadContext(id: String) {
        contextId = null
        initialize(id, requestedExclusions, draftName)
    }

    fun selectCategory(category: ExerciseCategory) {
        if (_state.value.selectedCategory == category) return
        _state.update { it.copy(selectedCategory = category, filteredExercises = emptyList(), allExercises = emptyList(),
            isLoading = if (contextReady) true else it.isLoading, errorRes = if (contextReady) null else it.errorRes) }
        if (contextReady) catalog.select(category, _state.value.searchQuery)
    }

    fun onSearchQueryChange(query: String) {
        val bounded = query.take(100)
        val changed = normalizeSearch(bounded) != normalizeSearch(_state.value.searchQuery)
        _state.update { it.copy(searchQuery = bounded, filteredExercises = if (changed) emptyList() else it.filteredExercises,
            isLoading = contextReady && changed || it.isLoading, errorRes = if (contextReady && changed) null else it.errorRes) }
        if (contextReady) catalog.select(_state.value.selectedCategory, bounded, debounce = true)
    }

    fun resetSelection() { _state.update { it.copy(selectedExercises = emptyList()) } }
    fun clearFilters() {
        if (_state.value.searchQuery.isEmpty() && _state.value.selectedCategory == ExerciseCategory.ALL) return
        _state.update { it.copy(searchQuery = "", selectedCategory = ExerciseCategory.ALL, filteredExercises = emptyList(), allExercises = emptyList(),
            isLoading = if (contextReady) true else it.isLoading, errorRes = if (contextReady) null else it.errorRes) }
        if (contextReady) catalog.select(ExerciseCategory.ALL, "")
    }

    private fun applyFilters() {
        _state.update { it.filtered() }
    }

    fun toggleExercise(exercise: Exercise) {
        if (!contextReady || _state.value.isLoading || _state.value.stale || exercise.id in exclusions) return
        _state.update { it.toggled(exercise) }
    }
    fun refresh() { if (!contextReady) retry() else catalog.refresh() }
    fun access(id: String) = catalog.access(id)
    fun loadMore() { catalog.feed.state.value.items.lastOrNull()?.let { catalog.access(it.id) } }
    override fun onCleared() { catalog.close(); authListener.close() }
}

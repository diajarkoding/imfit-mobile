package com.diajarkoding.imfit.presentation.ui.exercise

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.domain.model.Exercise
import com.diajarkoding.imfit.domain.model.ExercisePrescriptionCapability
import com.diajarkoding.imfit.domain.model.prescriptionCapability
import com.diajarkoding.imfit.domain.repository.AuthRepository
import com.diajarkoding.imfit.domain.repository.ExerciseCatalogRepository
import com.diajarkoding.imfit.domain.repository.ExerciseHydrationResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    val isAppending: Boolean = false,
    val isPullRefreshing: Boolean = false,
    val hasMore: Boolean = false,
    val nextCursor: String? = null,
    @StringRes val errorRes: Int? = null,
    @StringRes val appendErrorRes: Int? = null
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
class ExerciseSelectionViewModel(
    private val exerciseCatalogRepository: ExerciseCatalogRepository,
    private val authRepository: AuthRepository,
    private val savedStateHandle: SavedStateHandle,
    private val overrideScope: CoroutineScope? = null
) : ViewModel() {
    @Inject
    constructor(
        exerciseCatalogRepository: ExerciseCatalogRepository,
        authRepository: AuthRepository,
        savedStateHandle: SavedStateHandle
    ) : this(exerciseCatalogRepository, authRepository, savedStateHandle, null)

    private val scope: CoroutineScope get() = overrideScope ?: viewModelScope
    private val _state = MutableStateFlow(ExerciseSelectionState())
    val state = _state.asStateFlow()
    private var contextId: String? = null
    private var ownerId: String? = null
    private var exclusions: Set<String> = emptySet()
    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var generation = 0L
    private var seenCursors: Set<String> = emptySet()
    private var restoredSelectionIds: List<String> = emptyList()

    fun initialize(
        templateId: String, excludedIds: Set<String>? = null, routineName: String? = null,
        selectionContextId: String = templateId
    ) {
        val requestedExclusions = excludedIds.orEmpty()
        if (contextId == selectionContextId && exclusions == requestedExclusions) return
        invalidate()
        contextId = selectionContextId
        exclusions = requestedExclusions
        val token = generation
        _state.value = ExerciseSelectionState(routineName = routineName.orEmpty())
        loadJob = scope.launch {
            val owner = authRepository.getLocalUserId()
            if (generation != token) return@launch
            if (owner == null) {
                clearForOwnerChange()
                return@launch
            }
            ownerId = owner
            if (savedStateHandle.get<String>(OWNER) == owner && savedStateHandle.get<String>(CONTEXT) == selectionContextId) {
                val category = savedStateHandle.get<String>(CATEGORY)?.let { name ->
                    ExerciseCategory.entries.firstOrNull { it.name == name }
                } ?: ExerciseCategory.ALL
                _state.update { it.copy(searchQuery = savedStateHandle[QUERY] ?: "", selectedCategory = category) }
                restoredSelectionIds = savedStateHandle.get<ArrayList<String>>(SELECTION).orEmpty().filterNot { it in exclusions }
            } else {
                restoredSelectionIds = emptyList()
                persist()
            }
            loadFirstPage(isRefresh = false)
        }
    }

    fun retry() = loadFirstPage(isRefresh = false)

    fun pullToRefresh() {
        if (_state.value.isPullRefreshing) return
        loadFirstPage(isRefresh = true)
    }

    private fun invalidate() {
        generation++
        loadJob?.cancel()
        searchJob?.cancel()
    }

    private suspend fun owns(token: Long, owner: String): Boolean {
        if (generation != token || ownerId != owner) return false
        val currentOwner = authRepository.peekLocalUserId()
        if (generation != token || ownerId != owner) return false
        if (currentOwner == owner) return true
        clearForOwnerChange()
        return false
    }

    private fun clearForOwnerChange() {
        invalidate()
        ownerId = null
        restoredSelectionIds = emptyList()
        seenCursors = emptySet()
        listOf(OWNER, CONTEXT, QUERY, CATEGORY, SELECTION).forEach { savedStateHandle.remove<Any>(it) }
        _state.value = ExerciseSelectionState(isLoading = false, errorRes = R.string.routine_catalog_failed)
    }

    private fun loadFirstPage(isRefresh: Boolean) {
        val owner = ownerId ?: return
        invalidate()
        val token = generation
        val query = _state.value.searchQuery
        val category = _state.value.selectedCategory
        _state.update { it.copy(isLoading = !isRefresh, isPullRefreshing = isRefresh, isAppending = false,
            errorRes = null, appendErrorRes = null) }
        loadJob = scope.launch {
            try {
                if (!owns(token, owner)) return@launch
                if (restoredSelectionIds.isNotEmpty()) {
                    val ids = restoredSelectionIds
                    val hydrated = when (val result = exerciseCatalogRepository.getExercisesByIds(ids)) {
                        is ExerciseHydrationResult.Success -> result.exercises
                        is ExerciseHydrationResult.Missing -> error("Selected exercises are unavailable")
                        is ExerciseHydrationResult.Error -> throw result.cause
                    }.associateBy { it.id }
                    val ordered = ids.map { hydrated[it] ?: error("Selected exercise is missing") }
                    check(ordered.all { it.isActive && it.prescriptionCapability == ExercisePrescriptionCapability.REPS_LOAD })
                    if (!owns(token, owner)) return@launch
                    _state.update { it.copy(selectedExercises = ordered) }
                    restoredSelectionIds = emptyList()
                }
                val batch = fetchBatch(query, category, null, emptySet(), token, owner)
                if (!owns(token, owner)) return@launch
                seenCursors = batch.seen
                _state.update { it.copy(allExercises = batch.exercises, filteredExercises = batch.exercises,
                    isLoading = false, isPullRefreshing = false, hasMore = batch.hasMore, nextCursor = batch.cursor) }
                persist()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                if (owns(token, owner)) _state.update { it.copy(isLoading = false, isPullRefreshing = false,
                    errorRes = R.string.routine_catalog_failed) }
            }
        }
    }

    private data class Batch(val exercises: List<Exercise>, val cursor: String?, val hasMore: Boolean, val seen: Set<String>)

    private suspend fun fetchBatch(
        query: String, category: ExerciseCategory, startCursor: String?, previousCursors: Set<String>, token: Long, owner: String
    ): Batch {
        var cursor = startCursor
        val visited = previousCursors.toMutableSet()
        val rows = mutableListOf<Exercise>()
        var hasMore = false
        repeat(MAX_EXCLUDED_ADVANCES) {
            cursor?.let { visited.add(it) }
            val page = exerciseCatalogRepository.fetchCatalogPage(
                search = query.trim(), categoryId = category.databaseId,
                cursor = cursor, limit = PAGE_SIZE
            ).getOrThrow()
            if (!owns(token, owner)) throw CancellationException()
            if (page.hasMore) check(!page.nextCursor.isNullOrBlank() && page.nextCursor !in visited) { "Invalid catalog cursor" }
            rows.addAll(page.exercises.filter { category.includes(it) }.filterNot { it.id in exclusions })
            cursor = if (page.hasMore) page.nextCursor else null
            hasMore = page.hasMore
            if (rows.isNotEmpty() || !hasMore) return Batch(rows.distinctBy { it.id }, cursor, hasMore, visited)
            if (startCursor != null) {
                // Excluded rows need no presentation commit. Retain their progress so a
                // later failed fetch retries that continuation, not the skipped pages.
                seenCursors = visited.toSet()
                _state.update { it.copy(nextCursor = cursor) }
            }
        }
        return Batch(rows, cursor, hasMore, visited)
    }

    fun loadNextPage() {
        val current = _state.value
        val owner = ownerId ?: return
        if (current.isAppending || current.isLoading || current.isPullRefreshing || !current.hasMore || current.nextCursor == null) return
        val token = generation
        _state.update { it.copy(isAppending = true, appendErrorRes = null) }
        loadJob = scope.launch {
            try {
                if (!owns(token, owner)) return@launch
                val batch = fetchBatch(current.searchQuery, current.selectedCategory, current.nextCursor, seenCursors, token, owner)
                if (!owns(token, owner)) return@launch
                seenCursors = batch.seen
                _state.update {
                    val combined = (it.allExercises + batch.exercises).distinctBy { exercise -> exercise.id }
                    it.copy(allExercises = combined, filteredExercises = combined, isAppending = false,
                        hasMore = batch.hasMore, nextCursor = batch.cursor)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                if (owns(token, owner)) _state.update { it.copy(isAppending = false, appendErrorRes = R.string.routine_catalog_failed) }
            }
        }
    }

    fun selectCategory(category: ExerciseCategory) {
        if (_state.value.selectedCategory == category) return
        _state.update { it.copy(selectedCategory = category) }
        resetQueryPage()
        loadFirstPage(isRefresh = false)
    }

    fun onSearchQueryChange(query: String) {
        if (_state.value.searchQuery == query) return
        invalidate()
        _state.update { it.copy(searchQuery = query) }
        resetQueryPage()
        searchJob = scope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            loadFirstPage(isRefresh = false)
        }
    }

    private fun resetQueryPage() {
        seenCursors = emptySet()
        _state.update { it.copy(allExercises = emptyList(), filteredExercises = emptyList(), isLoading = true,
            isAppending = false, isPullRefreshing = false, hasMore = false, nextCursor = null, errorRes = null, appendErrorRes = null) }
        persist()
    }

    fun resetSelection() {
        scope.launch {
            val owner = ownerId ?: return@launch
            if (!owns(generation, owner)) return@launch
            restoredSelectionIds = emptyList()
            _state.update { it.copy(selectedExercises = emptyList()) }
            persist()
        }
    }

    fun clearFilters() {
        _state.update { it.copy(searchQuery = "", selectedCategory = ExerciseCategory.ALL) }
        resetQueryPage()
        loadFirstPage(isRefresh = false)
    }

    fun toggleExercise(exercise: Exercise) {
        scope.launch {
            val owner = ownerId ?: return@launch
            if (!owns(generation, owner) || _state.value.isLoading || exercise.id in exclusions || !exercise.isActive ||
                exercise.prescriptionCapability != ExercisePrescriptionCapability.REPS_LOAD) return@launch
            _state.update { it.toggled(exercise) }
            persist()
        }
    }

    private fun persist() {
        val owner = ownerId ?: return
        savedStateHandle[OWNER] = owner
        savedStateHandle[CONTEXT] = contextId
        savedStateHandle[QUERY] = _state.value.searchQuery
        savedStateHandle[CATEGORY] = _state.value.selectedCategory.name
        savedStateHandle[SELECTION] = ArrayList(restoredSelectionIds.ifEmpty { _state.value.selectedExercises.map { it.id } })
    }

    private companion object {
        const val PAGE_SIZE = 10
        const val MAX_EXCLUDED_ADVANCES = 3
        const val SEARCH_DEBOUNCE_MS = 300L
        const val OWNER = "picker.owner"
        const val CONTEXT = "picker.context"
        const val QUERY = "picker.query"
        const val CATEGORY = "picker.category"
        const val SELECTION = "picker.selection"
    }
}

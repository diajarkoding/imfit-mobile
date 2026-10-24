package com.diajarkoding.imfit.data.repository

import com.diajarkoding.imfit.data.local.FakeExerciseDataSource
import com.diajarkoding.imfit.domain.model.Exercise
import com.diajarkoding.imfit.domain.model.MuscleCategory
import com.diajarkoding.imfit.domain.model.ExerciseCatalogFilter
import com.diajarkoding.imfit.domain.repository.ExerciseRepository
import androidx.paging.*
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DemoExerciseRepository @Inject constructor() : ExerciseRepository {
    private val sources = Collections.synchronizedSet(mutableSetOf<PagingSource<Int, Exercise>>())

    override fun pageExercises(filter: ExerciseCatalogFilter) = Pager(
        exerciseCatalogPagingConfig()
    ) {
        DemoCatalogPagingSource(FakeExerciseDataSource.exercises.filter(filter::includes).distinctBy { it.id }.sortedBy { it.id })
            .also { source -> sources += source; source.registerInvalidatedCallback { sources -= source } }
    }.flow

    override suspend fun refreshExercises(force: Boolean): Boolean {
        // Demo-only local fixture refresh. Never presented as a successful Supabase request.
        synchronized(sources) { sources.toList() }.forEach { it.invalidate() }
        return true
    }
    override suspend fun getAllExercises(): List<Exercise> = FakeExerciseDataSource.exercises

    override suspend fun getExercisesByCategory(category: MuscleCategory): List<Exercise> =
        FakeExerciseDataSource.getExercisesByCategory(category)

    override suspend fun getExerciseById(id: String): Exercise? =
        FakeExerciseDataSource.getExerciseById(id)

    override suspend fun searchExercises(query: String): List<Exercise> =
        if (query.isBlank()) getAllExercises() else FakeExerciseDataSource.searchExercises(query)
}

internal class DemoCatalogPagingSource(private val records: List<Exercise>) : PagingSource<Int, Exercise>() {
    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, Exercise> {
        val offset = (params.key ?: 0).coerceAtMost(records.size)
        val end = (offset + params.loadSize).coerceAtMost(records.size)
        return LoadResult.Page(records.subList(offset, end),
            prevKey = if (offset == 0) null else (offset - EXERCISE_CATALOG_PAGE_SIZE).coerceAtLeast(0),
            nextKey = end.takeIf { it < records.size })
    }

    override fun getRefreshKey(state: PagingState<Int, Exercise>): Int? = state.anchorPosition?.let { anchor ->
        state.closestPageToPosition(anchor)?.let { page ->
            page.prevKey?.plus(state.config.pageSize) ?: page.nextKey?.minus(page.data.size)
        }
    }
}

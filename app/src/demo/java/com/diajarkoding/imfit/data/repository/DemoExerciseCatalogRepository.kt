package com.diajarkoding.imfit.data.repository

import com.diajarkoding.imfit.data.local.FakeExerciseDataSource
import com.diajarkoding.imfit.domain.model.Exercise
import com.diajarkoding.imfit.domain.model.MuscleCategory
import com.diajarkoding.imfit.domain.repository.ExerciseCatalogPage
import com.diajarkoding.imfit.domain.repository.ExerciseCatalogRepository
import com.diajarkoding.imfit.domain.repository.ExerciseHydrationResult
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DemoExerciseCatalogRepository @Inject constructor() : ExerciseCatalogRepository {

    override suspend fun fetchCatalogPage(
        search: String,
        categoryId: Int?,
        cursor: String?,
        limit: Int
    ): Result<ExerciseCatalogPage> {
        val all = FakeExerciseDataSource.exercises
        val filtered = all.filter { ex ->
            val matchesCategory = categoryId == null || ex.muscleCategory.ordinal == (categoryId - 1)
            val matchesSearch = search.isBlank() || ex.name.contains(search.trim(), ignoreCase = true)
            matchesCategory && matchesSearch
        }.sortedBy { it.name.lowercase() }

        val offset = cursor?.toIntOrNull() ?: 0
        val paged = filtered.drop(offset).take(limit)
        val nextOffset = offset + paged.size
        val hasMore = nextOffset < filtered.size
        val nextCursor = if (hasMore) nextOffset.toString() else null

        return Result.success(
            ExerciseCatalogPage(
                exercises = paged,
                nextCursor = nextCursor,
                hasMore = hasMore
            )
        )
    }

    override suspend fun getExercisesByIds(ids: List<String>): ExerciseHydrationResult {
        if (ids.isEmpty()) return ExerciseHydrationResult.Success(emptyList())
        val allById = FakeExerciseDataSource.exercises.associateBy { it.id }
        val distinctIds = ids.distinct()
        val missingIds = distinctIds.filterNot { allById.containsKey(it) }

        // MUST preserve caller input order
        val ordered = ids.mapNotNull { allById[it] }

        return if (missingIds.isNotEmpty()) {
            ExerciseHydrationResult.Missing(ordered, missingIds)
        } else {
            ExerciseHydrationResult.Success(ordered)
        }
    }
}

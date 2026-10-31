package com.diajarkoding.imfit.domain.repository

import com.diajarkoding.imfit.domain.model.Exercise

data class ExerciseCatalogPage(
    val exercises: List<Exercise>,
    val nextCursor: String?,
    val hasMore: Boolean
)

sealed interface ExerciseHydrationResult {
    data class Success(val exercises: List<Exercise>) : ExerciseHydrationResult
    data class Missing(val foundExercises: List<Exercise>, val missingIds: List<String>) : ExerciseHydrationResult
    data class Error(val cause: Throwable) : ExerciseHydrationResult
}

interface ExerciseCatalogRepository {
    suspend fun fetchCatalogPage(
        search: String = "",
        categoryId: Int? = null,
        cursor: String? = null,
        limit: Int = 10
    ): Result<ExerciseCatalogPage>

    suspend fun getExercisesByIds(ids: List<String>): ExerciseHydrationResult
}

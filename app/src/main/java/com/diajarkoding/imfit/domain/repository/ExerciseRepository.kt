package com.diajarkoding.imfit.domain.repository

import com.diajarkoding.imfit.domain.model.Exercise
import com.diajarkoding.imfit.domain.model.MuscleCategory
import com.diajarkoding.imfit.domain.model.ExerciseCatalogFilter
import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow

interface ExerciseRepository {
    fun pageExercises(filter: ExerciseCatalogFilter): Flow<PagingData<Exercise>>
    fun observeExercises(): kotlinx.coroutines.flow.Flow<List<Exercise>> = kotlinx.coroutines.flow.flow { emit(getAllExercises()) }
    suspend fun getAllExercises(): List<Exercise>
    suspend fun getExercisesByCategory(category: MuscleCategory): List<Exercise>
    suspend fun getExerciseById(id: String): Exercise?
    suspend fun searchExercises(query: String): List<Exercise>
    suspend fun refreshExercises(force: Boolean = false): Boolean = true
}

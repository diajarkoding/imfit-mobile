package com.diajarkoding.imfit.domain.repository

import com.diajarkoding.imfit.domain.model.Exercise
import com.diajarkoding.imfit.domain.model.MuscleCategory

interface ExerciseRepository {
    fun observeExercises(): kotlinx.coroutines.flow.Flow<List<Exercise>> = kotlinx.coroutines.flow.flow { emit(getAllExercises()) }
    suspend fun getAllExercises(): List<Exercise>
    suspend fun getExercisesByCategory(category: MuscleCategory): List<Exercise>
    suspend fun getExerciseById(id: String): Exercise?
    suspend fun searchExercises(query: String): List<Exercise>
    suspend fun refreshExercises(force: Boolean = false): Boolean = true
}

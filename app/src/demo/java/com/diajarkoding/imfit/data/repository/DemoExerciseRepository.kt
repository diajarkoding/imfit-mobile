package com.diajarkoding.imfit.data.repository

import com.diajarkoding.imfit.data.local.FakeExerciseDataSource
import com.diajarkoding.imfit.domain.model.Exercise
import com.diajarkoding.imfit.domain.model.MuscleCategory
import com.diajarkoding.imfit.domain.repository.ExerciseRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DemoExerciseRepository @Inject constructor() : ExerciseRepository {
    override suspend fun getAllExercises(): List<Exercise> = FakeExerciseDataSource.exercises

    override suspend fun getExercisesByCategory(category: MuscleCategory): List<Exercise> =
        FakeExerciseDataSource.getExercisesByCategory(category)

    override suspend fun getExerciseById(id: String): Exercise? =
        FakeExerciseDataSource.getExerciseById(id)

    override suspend fun searchExercises(query: String): List<Exercise> =
        if (query.isBlank()) getAllExercises() else FakeExerciseDataSource.searchExercises(query)
}

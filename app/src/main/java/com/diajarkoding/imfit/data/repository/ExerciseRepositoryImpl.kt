package com.diajarkoding.imfit.data.repository

import com.diajarkoding.imfit.data.local.FakeExerciseDataSource
import com.diajarkoding.imfit.domain.model.Exercise
import com.diajarkoding.imfit.domain.model.MuscleCategory
import com.diajarkoding.imfit.domain.repository.ExerciseRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExerciseRepositoryImpl @Inject constructor() : ExerciseRepository {

    override fun getAllExercises(): List<Exercise> {
        return FakeExerciseDataSource.exercises
    }

    override fun getExercisesByCategory(category: MuscleCategory): List<Exercise> {
        return FakeExerciseDataSource.getExercisesByCategory(category)
    }

    override fun getExerciseById(id: String): Exercise? {
        return FakeExerciseDataSource.getExerciseById(id)
    }

    override fun searchExercises(query: String): List<Exercise> {
        return FakeExerciseDataSource.searchExercises(query)
    }
}

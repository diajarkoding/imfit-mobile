package com.diajarkoding.imfit.domain.repository

import com.diajarkoding.imfit.domain.model.Exercise
import com.diajarkoding.imfit.domain.model.MuscleCategory

interface ExerciseRepository {
    fun getAllExercises(): List<Exercise>
    fun getExercisesByCategory(category: MuscleCategory): List<Exercise>
    fun getExerciseById(id: String): Exercise?
    fun searchExercises(query: String): List<Exercise>
}

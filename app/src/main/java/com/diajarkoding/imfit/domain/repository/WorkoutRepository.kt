package com.diajarkoding.imfit.domain.repository

import com.diajarkoding.imfit.domain.model.ExerciseLog
import com.diajarkoding.imfit.domain.model.TemplateExercise
import com.diajarkoding.imfit.domain.model.WorkoutLog
import com.diajarkoding.imfit.domain.model.WorkoutSession
import com.diajarkoding.imfit.domain.model.WorkoutTemplate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

interface WorkoutRepository {
    suspend fun getTemplateById(templateId: String): WorkoutTemplate?
    suspend fun createTemplate(userId: String, name: String, exercises: List<TemplateExercise>): WorkoutTemplate
    suspend fun updateTemplate(templateId: String, name: String, exercises: List<TemplateExercise>): WorkoutTemplate?
    suspend fun updateTemplateExercises(templateId: String, exercises: List<TemplateExercise>): WorkoutTemplate?
    suspend fun updateTemplateExercise(templateId: String, exerciseId: String, sets: Int, reps: Int, restSeconds: Int): WorkoutTemplate?
    suspend fun deleteTemplate(templateId: String): Boolean

    suspend fun getActiveSessionForTimer(): WorkoutSession?
    suspend fun isActiveSessionForTimer(userId: String, workoutId: String): Boolean
    // Active session operations
    suspend fun startWorkout(template: WorkoutTemplate): WorkoutSession
    suspend fun getActiveSession(): WorkoutSession?
    suspend fun getPrefillForExercise(exerciseId: String, userId: String,
        defaults: com.diajarkoding.imfit.domain.model.ExerciseInputDefaults): Map<Int, Pair<Float, Int>>
    /** The terminal receipt owns identity; summary fetching is a separate read. */
    suspend fun finishWorkout(): String?
    suspend fun cancelWorkout()
    suspend fun updateSessionRestOverride(seconds: Int)
    suspend fun getSessionRestOverride(): Int?
    suspend fun getWorkoutLogById(logId: String): WorkoutLog?
    suspend fun getLastWorkoutLog(userId: String): WorkoutLog?

}

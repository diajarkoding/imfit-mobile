package com.diajarkoding.imfit.domain.repository

import com.diajarkoding.imfit.domain.model.ExerciseLog
import com.diajarkoding.imfit.domain.model.TemplateExercise
import com.diajarkoding.imfit.domain.model.WorkoutLog
import com.diajarkoding.imfit.domain.model.WorkoutSession
import com.diajarkoding.imfit.domain.model.WorkoutTemplate

interface WorkoutRepository {
    // Template operations
    suspend fun getTemplates(userId: String): List<WorkoutTemplate>
    suspend fun getTemplateById(templateId: String): WorkoutTemplate?
    suspend fun createTemplate(userId: String, name: String, exercises: List<TemplateExercise>): WorkoutTemplate
    suspend fun updateTemplate(templateId: String, name: String, exercises: List<TemplateExercise>): WorkoutTemplate?
    suspend fun updateTemplateExercises(templateId: String, exercises: List<TemplateExercise>): WorkoutTemplate?
    suspend fun updateTemplateExercise(templateId: String, exerciseId: String, sets: Int, reps: Int, restSeconds: Int): WorkoutTemplate?
    suspend fun deleteTemplate(templateId: String): Boolean

    // Active session operations
    suspend fun startWorkout(template: WorkoutTemplate): WorkoutSession
    suspend fun getActiveSession(): WorkoutSession?
    suspend fun updateActiveSession(session: WorkoutSession)
    suspend fun finishWorkout(): WorkoutLog?
    suspend fun cancelWorkout()

    // Workout log operations
    suspend fun getWorkoutLogs(userId: String): List<WorkoutLog>
    suspend fun getWorkoutLogById(logId: String): WorkoutLog?
    suspend fun getLastWorkoutLog(userId: String): WorkoutLog?
    suspend fun getLastExerciseLog(exerciseId: String): ExerciseLog?
}

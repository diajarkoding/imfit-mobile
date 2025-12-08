package com.diajarkoding.imfit.domain.repository

import com.diajarkoding.imfit.domain.model.Exercise
import com.diajarkoding.imfit.domain.model.TemplateExercise
import com.diajarkoding.imfit.domain.model.WorkoutLog
import com.diajarkoding.imfit.domain.model.WorkoutSession
import com.diajarkoding.imfit.domain.model.WorkoutTemplate

interface WorkoutRepository {
    // Template operations
    fun getTemplates(userId: String): List<WorkoutTemplate>
    fun getTemplateById(templateId: String): WorkoutTemplate?
    fun createTemplate(userId: String, name: String, exercises: List<TemplateExercise>): WorkoutTemplate
    fun updateTemplate(templateId: String, name: String, exercises: List<TemplateExercise>): WorkoutTemplate?
    fun updateTemplateExercises(templateId: String, exercises: List<TemplateExercise>): WorkoutTemplate?
    fun updateTemplateExercise(templateId: String, exerciseId: String, sets: Int, reps: Int, restSeconds: Int): WorkoutTemplate?
    fun deleteTemplate(templateId: String): Boolean

    // Active session operations
    fun startWorkout(template: WorkoutTemplate): WorkoutSession
    fun getActiveSession(): WorkoutSession?
    fun updateActiveSession(session: WorkoutSession)
    fun finishWorkout(): WorkoutLog?
    fun cancelWorkout()

    // Workout log operations
    fun getWorkoutLogs(userId: String): List<WorkoutLog>
    fun getWorkoutLogById(logId: String): WorkoutLog?
    fun getLastWorkoutLog(userId: String): WorkoutLog?
}

package com.diajarkoding.imfit.domain.repository

import com.diajarkoding.imfit.domain.model.ExerciseLog
import com.diajarkoding.imfit.domain.model.TemplateExercise
import com.diajarkoding.imfit.domain.model.WorkoutLog
import com.diajarkoding.imfit.domain.model.WorkoutSession
import com.diajarkoding.imfit.domain.model.WorkoutTemplate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

interface WorkoutRepository {
    fun observeTemplates(userId: String): Flow<List<WorkoutTemplate>> = flow { emit(getTemplates(userId)) }
    fun observeTemplate(templateId: String): Flow<WorkoutTemplate?> = flow { emit(getTemplateById(templateId)) }
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
    /** Null means absent; recovery/read failures must propagate to background callers. */
    suspend fun getActiveSessionForTimer(): WorkoutSession? = getActiveSession()
    /** Caller must verify the current auth owner. Read failures must propagate, never mean absent. */
    suspend fun isActiveSessionForTimer(userId: String, workoutId: String): Boolean =
        getActiveSessionForTimer()?.id == workoutId
    suspend fun updateActiveSession(session: WorkoutSession)
    suspend fun mutateActiveSession(sessionId: String, transform: (WorkoutSession) -> WorkoutSession): WorkoutSession? {
        val current = getActiveSession()?.takeIf { it.id == sessionId } ?: return null
        return transform(current).also { updateActiveSession(it) }
    }
    fun requestCancelSession(sessionId: String) { error("Asynchronous session cancellation is not implemented") }
    suspend fun finishWorkout(): WorkoutLog?
    suspend fun cancelWorkout()
    suspend fun updateSessionRestOverride(seconds: Int)
    suspend fun getSessionRestOverride(): Int?

    // Workout log operations
    fun observeWorkoutLogs(userId: String): Flow<List<WorkoutLog>> = flow { emit(getWorkoutLogs(userId)) }
    fun observeLastWorkoutLog(userId: String): Flow<WorkoutLog?> = flow { emit(getLastWorkoutLog(userId)) }
    suspend fun getWorkoutLogs(userId: String): List<WorkoutLog>
    suspend fun getWorkoutLogById(logId: String): WorkoutLog?
    fun observeWorkoutLog(logId: String): Flow<WorkoutLog?> = flow { emit(getWorkoutLogById(logId)) }
    suspend fun getLastWorkoutLog(userId: String): WorkoutLog?
    suspend fun getLastExerciseLog(exerciseId: String): ExerciseLog?
    
    // Last Known Performance operations
    suspend fun getLastWeightsForExercise(exerciseId: String, userId: String): Map<Int, Float>
    /** Unknown provenance or changed targets means defaults, not guessed history. */
    suspend fun getPrefillForExercise(exerciseId: String, userId: String,
        defaults: com.diajarkoding.imfit.domain.model.ExerciseInputDefaults): Map<Int, Pair<Float, Int>> = emptyMap()
    suspend fun getLastPerformanceForExercise(
        exerciseId: String,
        userId: String
    ): Map<Int, Pair<Float, Int>> = getLastWeightsForExercise(exerciseId, userId).mapValues { it.value to 0 }
}

package com.diajarkoding.imfit.data.repository

import com.diajarkoding.imfit.domain.model.ExerciseLog
import com.diajarkoding.imfit.domain.model.TemplateExercise
import com.diajarkoding.imfit.domain.model.WorkoutLog
import com.diajarkoding.imfit.domain.model.WorkoutSession
import com.diajarkoding.imfit.domain.model.WorkoutTemplate
import com.diajarkoding.imfit.domain.repository.WorkoutRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Facade implementation of WorkoutRepository.
 * Delegates to specialized repositories for better separation of concerns:
 * - WorkoutTemplateRepository: Template CRUD operations
 * - WorkoutSessionManager: Active workout session management
 * - WorkoutLogRepository: Workout history and logs
 * 
 * This refactoring addresses the CRITICAL-1 issue from code review:
 * "Large Repository Class" - original was ~900 LOC with multiple responsibilities.
 */
@Singleton
class WorkoutRepositoryImpl @Inject constructor(
    private val templateRepository: WorkoutTemplateRepository,
    private val sessionManager: WorkoutSessionManager,
    private val logRepository: WorkoutLogRepository
) : WorkoutRepository {

    // ==================== TEMPLATES ====================

    override suspend fun getTemplates(userId: String): List<WorkoutTemplate> =
        templateRepository.getTemplates(userId)

    override suspend fun getTemplateById(templateId: String): WorkoutTemplate? =
        templateRepository.getTemplateById(templateId)

    override suspend fun createTemplate(
        userId: String, 
        name: String, 
        exercises: List<TemplateExercise>
    ): WorkoutTemplate = templateRepository.createTemplate(userId, name, exercises)

    override suspend fun updateTemplate(
        templateId: String, 
        name: String, 
        exercises: List<TemplateExercise>
    ): WorkoutTemplate? = templateRepository.updateTemplate(templateId, name, exercises)

    override suspend fun updateTemplateExercises(
        templateId: String, 
        exercises: List<TemplateExercise>
    ): WorkoutTemplate? {
        val template = templateRepository.getTemplateById(templateId) ?: return null
        return templateRepository.updateTemplate(templateId, template.name, exercises)
    }

    override suspend fun updateTemplateExercise(
        templateId: String, 
        exerciseId: String, 
        sets: Int, 
        reps: Int, 
        restSeconds: Int
    ): WorkoutTemplate? = templateRepository.updateTemplateExercise(
        templateId, exerciseId, sets, reps, restSeconds
    )

    override suspend fun deleteTemplate(templateId: String): Boolean =
        templateRepository.deleteTemplate(templateId)

    // ==================== ACTIVE SESSION ====================

    override suspend fun startWorkout(template: WorkoutTemplate): WorkoutSession =
        sessionManager.startWorkout(template)

    override suspend fun getActiveSession(): WorkoutSession? =
        sessionManager.getActiveSession()

    override suspend fun updateActiveSession(session: WorkoutSession) =
        sessionManager.updateActiveSession(session)

    override suspend fun finishWorkout(): WorkoutLog? {
        val session = sessionManager.getCurrentSession() ?: return null
        val workoutLog = logRepository.finishWorkout(session)
        sessionManager.clearActiveSession(session.id)
        return workoutLog
    }

    override suspend fun cancelWorkout() =
        sessionManager.cancelWorkout()

    override suspend fun updateSessionRestOverride(seconds: Int) =
        sessionManager.updateSessionRestOverride(seconds)

    override suspend fun getSessionRestOverride(): Int? =
        sessionManager.getSessionRestOverride()

    // ==================== WORKOUT LOGS ====================

    override suspend fun getWorkoutLogs(userId: String): List<WorkoutLog> =
        logRepository.getWorkoutLogs(userId)

    override suspend fun getWorkoutLogById(logId: String): WorkoutLog? =
        logRepository.getWorkoutLogById(logId)

    override suspend fun getLastWorkoutLog(userId: String): WorkoutLog? =
        logRepository.getLastWorkoutLog(userId)

    override suspend fun getLastExerciseLog(exerciseId: String): ExerciseLog? =
        logRepository.getLastExerciseLog(exerciseId)

    override suspend fun getLastWeightsForExercise(exerciseId: String, userId: String): Map<Int, Float> =
        logRepository.getLastWeightsForExercise(exerciseId, userId)

    // ==================== PREFERENCES ====================

    override suspend fun setDefaultRestTimer(seconds: Int) =
        sessionManager.setDefaultRestTimer(seconds)

    override suspend fun getDefaultRestTimer(): Int? =
        sessionManager.getDefaultRestTimer()

    override suspend fun clearDefaultRestTimer() =
        sessionManager.clearDefaultRestTimer()
}

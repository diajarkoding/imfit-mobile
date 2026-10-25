package com.diajarkoding.imfit.data.repository

import com.diajarkoding.imfit.domain.model.*
import com.diajarkoding.imfit.domain.repository.WorkoutRepository
import javax.inject.Inject
import javax.inject.Singleton

/** Public production facade. Local storage alone is never bound as the app repository. */
@Singleton
class WorkoutRepositoryImpl @Inject constructor(
    private val templates: WorkoutTemplateRepository,
    private val logs: WorkoutLogRepository,
    private val sessions: WorkoutSessionManager,
) : WorkoutRepository {
    override fun observeTemplates(userId: String) = templates.observeTemplates(userId)
    override fun observeTemplate(templateId: String) = templates.observeTemplate(templateId)
    override suspend fun getTemplates(userId: String) = templates.getTemplates(userId)
    override suspend fun getTemplateById(templateId: String) = templates.getTemplateById(templateId)
    override suspend fun createTemplate(userId: String, name: String, exercises: List<TemplateExercise>) = templates.createTemplate(userId, name, exercises)
    override suspend fun updateTemplate(templateId: String, name: String, exercises: List<TemplateExercise>) = templates.updateTemplate(templateId, name, exercises)
    override suspend fun updateTemplateExercises(templateId: String, exercises: List<TemplateExercise>) = templates.updateTemplateExercises(templateId, exercises)
    override suspend fun updateTemplateExercise(templateId: String, exerciseId: String, sets: Int, reps: Int, restSeconds: Int) = templates.updateTemplateExercise(templateId, exerciseId, sets, reps, restSeconds)
    override suspend fun deleteTemplate(templateId: String) = templates.deleteTemplate(templateId)
    override suspend fun startWorkout(template: WorkoutTemplate) = sessions.startWorkout(template)
    override suspend fun getActiveSession() = sessions.getActiveSession()
    override suspend fun getActiveSessionForTimer() = sessions.getActiveSessionForTimer()
    override suspend fun isActiveSessionForTimer(userId: String, workoutId: String) = sessions.isActiveSessionForTimer(userId, workoutId)
    override suspend fun updateActiveSession(session: WorkoutSession) = sessions.updateActiveSession(session)
    override suspend fun mutateActiveSession(sessionId: String, transform: (WorkoutSession) -> WorkoutSession) = sessions.mutateActiveSession(sessionId, transform)
    override fun requestCancelSession(sessionId: String) = sessions.requestCancelSession(sessionId)
    override suspend fun finishWorkout() = sessions.finishWorkout()
    override suspend fun cancelWorkout() = sessions.cancelWorkout()
    override suspend fun updateSessionRestOverride(seconds: Int) = sessions.updateSessionRestOverride(seconds)
    override suspend fun getSessionRestOverride() = sessions.getSessionRestOverride()
    override fun observeWorkoutLogs(userId: String) = logs.observeWorkoutLogs(userId)
    override fun observeLastWorkoutLog(userId: String) = logs.observeLastWorkoutLog(userId)
    override fun observeWorkoutLog(logId: String) = logs.observeWorkoutLog(logId)
    override suspend fun getWorkoutLogs(userId: String) = logs.getWorkoutLogs(userId)
    override suspend fun getWorkoutLogById(logId: String) = logs.getWorkoutLogById(logId)
    override suspend fun getLastWorkoutLog(userId: String) = logs.getLastWorkoutLog(userId)
    override suspend fun getLastExerciseLog(exerciseId: String) = logs.getLastExerciseLog(exerciseId)
    override suspend fun getLastWeightsForExercise(exerciseId: String, userId: String) = logs.getLastWeightsForExercise(exerciseId, userId)
    override suspend fun getLastPerformanceForExercise(exerciseId: String, userId: String) = logs.getLastPerformanceForExercise(exerciseId, userId)
    override suspend fun getPrefillForExercise(exerciseId: String, userId: String, defaults: ExerciseInputDefaults) = logs.getPrefillForExercise(exerciseId, userId, defaults)
}

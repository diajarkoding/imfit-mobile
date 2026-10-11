package com.diajarkoding.imfit.data.repository

import com.diajarkoding.imfit.data.local.FakeWorkoutDataSource
import com.diajarkoding.imfit.data.local.FakeUserDataSource
import com.diajarkoding.imfit.domain.model.ExerciseLog
import com.diajarkoding.imfit.domain.model.TemplateExercise
import com.diajarkoding.imfit.domain.model.WorkoutLog
import com.diajarkoding.imfit.domain.model.WorkoutSession
import com.diajarkoding.imfit.domain.model.WorkoutTemplate
import com.diajarkoding.imfit.domain.repository.WorkoutRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DemoWorkoutRepository @Inject constructor() : WorkoutRepository {
    private var sessionRestOverride: Int? = null

    override suspend fun getTemplates(userId: String): List<WorkoutTemplate> =
        FakeWorkoutDataSource.getTemplates(userId)

    override suspend fun getTemplateById(templateId: String): WorkoutTemplate? =
        FakeWorkoutDataSource.getTemplateById(templateId)

    override suspend fun createTemplate(
        userId: String,
        name: String,
        exercises: List<TemplateExercise>
    ): WorkoutTemplate = FakeWorkoutDataSource.createTemplate(userId, name, exercises)

    override suspend fun updateTemplate(
        templateId: String,
        name: String,
        exercises: List<TemplateExercise>
    ): WorkoutTemplate? = FakeWorkoutDataSource.updateTemplate(templateId, name, exercises)

    override suspend fun updateTemplateExercises(
        templateId: String,
        exercises: List<TemplateExercise>
    ): WorkoutTemplate? {
        val template = FakeWorkoutDataSource.getTemplateById(templateId) ?: return null
        return FakeWorkoutDataSource.updateTemplate(templateId, template.name, exercises)
    }

    override suspend fun updateTemplateExercise(
        templateId: String,
        exerciseId: String,
        sets: Int,
        reps: Int,
        restSeconds: Int
    ): WorkoutTemplate? = FakeWorkoutDataSource.updateTemplateExercise(
        templateId = templateId,
        exerciseId = exerciseId,
        sets = sets,
        reps = reps,
        restSeconds = restSeconds
    )

    override suspend fun deleteTemplate(templateId: String): Boolean =
        FakeWorkoutDataSource.deleteTemplate(templateId)

    override suspend fun startWorkout(template: WorkoutTemplate): WorkoutSession {
        sessionRestOverride = null
        return FakeWorkoutDataSource.startWorkout(template)
    }

    override suspend fun getActiveSession(): WorkoutSession? =
        FakeWorkoutDataSource.getActiveSession()

    override suspend fun updateActiveSession(session: WorkoutSession) {
        FakeWorkoutDataSource.updateActiveSession(session)
    }

    override suspend fun finishWorkout(): WorkoutLog? {
        val workoutLog = FakeWorkoutDataSource.finishWorkout()
        sessionRestOverride = null
        return workoutLog
    }

    override suspend fun cancelWorkout() {
        FakeWorkoutDataSource.cancelWorkout()
        sessionRestOverride = null
    }

    override suspend fun updateSessionRestOverride(seconds: Int) {
        sessionRestOverride = seconds
    }

    override suspend fun getSessionRestOverride(): Int? = sessionRestOverride

    override suspend fun getWorkoutLogs(userId: String): List<WorkoutLog> =
        FakeWorkoutDataSource.getWorkoutLogs(userId)

    override suspend fun getWorkoutLogById(logId: String): WorkoutLog? =
        FakeWorkoutDataSource.getWorkoutLogById(logId)

    override suspend fun getLastWorkoutLog(userId: String): WorkoutLog? =
        FakeWorkoutDataSource.getLastWorkoutLog(userId)

    override suspend fun getLastExerciseLog(exerciseId: String): ExerciseLog? =
        FakeUserDataSource.getCurrentUser()
            ?.let { FakeWorkoutDataSource.getWorkoutLogs(it.id) }
            .orEmpty()
            .asSequence()
            .flatMap { it.exerciseLogs.asSequence() }
            .firstOrNull { it.exercise.id == exerciseId }

    override suspend fun getLastWeightsForExercise(
        exerciseId: String,
        userId: String
    ): Map<Int, Float> = FakeWorkoutDataSource.getWorkoutLogs(userId)
        .asSequence()
        .flatMap { it.exerciseLogs.asSequence() }
        .firstOrNull { it.exercise.id == exerciseId }
        ?.sets
        ?.associate { it.setNumber to it.weight }
        .orEmpty()
}

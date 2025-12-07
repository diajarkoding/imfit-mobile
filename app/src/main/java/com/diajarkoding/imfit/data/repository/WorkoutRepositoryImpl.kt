package com.diajarkoding.imfit.data.repository

import com.diajarkoding.imfit.data.local.FakeWorkoutDataSource
import com.diajarkoding.imfit.domain.model.Exercise
import com.diajarkoding.imfit.domain.model.WorkoutLog
import com.diajarkoding.imfit.domain.model.WorkoutSession
import com.diajarkoding.imfit.domain.model.WorkoutTemplate
import com.diajarkoding.imfit.domain.repository.WorkoutRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkoutRepositoryImpl @Inject constructor() : WorkoutRepository {

    override fun getTemplates(userId: String): List<WorkoutTemplate> {
        return FakeWorkoutDataSource.getTemplates(userId)
    }

    override fun getTemplateById(templateId: String): WorkoutTemplate? {
        return FakeWorkoutDataSource.getTemplateById(templateId)
    }

    override fun createTemplate(userId: String, name: String, exercises: List<Exercise>): WorkoutTemplate {
        return FakeWorkoutDataSource.createTemplate(userId, name, exercises)
    }

    override fun updateTemplate(templateId: String, name: String, exercises: List<Exercise>): WorkoutTemplate? {
        return FakeWorkoutDataSource.updateTemplate(templateId, name, exercises)
    }

    override fun deleteTemplate(templateId: String): Boolean {
        return FakeWorkoutDataSource.deleteTemplate(templateId)
    }

    override fun startWorkout(template: WorkoutTemplate): WorkoutSession {
        return FakeWorkoutDataSource.startWorkout(template)
    }

    override fun getActiveSession(): WorkoutSession? {
        return FakeWorkoutDataSource.getActiveSession()
    }

    override fun updateActiveSession(session: WorkoutSession) {
        FakeWorkoutDataSource.updateActiveSession(session)
    }

    override fun finishWorkout(): WorkoutLog? {
        return FakeWorkoutDataSource.finishWorkout()
    }

    override fun cancelWorkout() {
        FakeWorkoutDataSource.cancelWorkout()
    }

    override fun getWorkoutLogs(userId: String): List<WorkoutLog> {
        return FakeWorkoutDataSource.getWorkoutLogs(userId)
    }

    override fun getWorkoutLogById(logId: String): WorkoutLog? {
        return FakeWorkoutDataSource.getWorkoutLogById(logId)
    }

    override fun getLastWorkoutLog(userId: String): WorkoutLog? {
        return FakeWorkoutDataSource.getLastWorkoutLog(userId)
    }
}

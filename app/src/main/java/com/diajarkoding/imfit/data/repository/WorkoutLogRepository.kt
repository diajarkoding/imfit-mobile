package com.diajarkoding.imfit.data.repository

import com.diajarkoding.imfit.data.sync.RemoteWorkoutCoordinator
import com.diajarkoding.imfit.domain.model.ExerciseInputDefaults
import com.diajarkoding.imfit.domain.model.WorkoutLog
import com.diajarkoding.imfit.domain.repository.AuthRepository
import javax.inject.Inject
import javax.inject.Singleton

/** Remote reads feed the reactive Room projection; failed completed uploads need manual intent. */
@Singleton
class WorkoutLogRepository @Inject constructor(
    private val local: WorkoutLocalStore,
    private val remote: RemoteWorkoutCoordinator,
    private val auth: AuthRepository,
) {
    private val access = AccountDataAccess(auth)
    fun observeWorkoutLogs(userId: String) = local.observeWorkoutLogs(userId)
    fun observeLastWorkoutLog(userId: String) = local.observeLastWorkoutLog(userId)
    fun observeWorkoutLog(logId: String) = local.observeWorkoutLog(logId)

    suspend fun getWorkoutLogs(userId: String): List<WorkoutLog> {
        require(userId.isNotBlank())
        return access.read(userId, emptyList()) { remote.refreshHistory(it); local.getWorkoutLogs(it) }
    }
    suspend fun getWorkoutLogById(logId: String): WorkoutLog? {
        require(logId.isNotBlank())
        return access.read(empty = null as WorkoutLog?) { remote.refreshHistory(it); local.getWorkoutLogById(logId) }
    }
    suspend fun getLastWorkoutLog(userId: String): WorkoutLog? {
        require(userId.isNotBlank())
        return access.read(userId, null as WorkoutLog?) { remote.refreshHistory(it); local.getLastWorkoutLog(it) }
    }
    suspend fun getLastExerciseLog(exerciseId: String): com.diajarkoding.imfit.domain.model.ExerciseLog? {
        require(exerciseId.isNotBlank())
        return local.getLastExerciseLog(exerciseId)
    }
    suspend fun getLastWeightsForExercise(exerciseId: String, userId: String) = local.getLastWeightsForExercise(exerciseId, userId)
    suspend fun getLastPerformanceForExercise(exerciseId: String, userId: String) = local.getLastPerformanceForExercise(exerciseId, userId)
    suspend fun getPrefillForExercise(exerciseId: String, userId: String, defaults: ExerciseInputDefaults) = local.getPrefillForExercise(exerciseId, userId, defaults)
    suspend fun attemptFinishedWorkout(owner: String, id: String) = remote.attemptFinishedWorkout(owner, id)
}

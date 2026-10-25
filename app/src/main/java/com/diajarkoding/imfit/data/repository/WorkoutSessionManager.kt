package com.diajarkoding.imfit.data.repository

import com.diajarkoding.imfit.domain.model.WorkoutSession
import com.diajarkoding.imfit.domain.model.WorkoutTemplate
import javax.inject.Inject
import javax.inject.Singleton

/** Latest development session editing, metrics, timer and prefill contracts, one state owner. */
@Singleton
class WorkoutSessionManager @Inject constructor(
    private val local: WorkoutLocalStore,
    private val logs: WorkoutLogRepository,
) {
    suspend fun startWorkout(template: WorkoutTemplate) = local.startWorkout(template)
    suspend fun getActiveSession() = local.getActiveSession()
    suspend fun getActiveSessionForTimer() = local.getActiveSessionForTimer()
    suspend fun isActiveSessionForTimer(userId: String, workoutId: String) = local.isActiveSessionForTimer(userId, workoutId)
    suspend fun updateActiveSession(session: WorkoutSession) = local.updateActiveSession(session)
    suspend fun mutateActiveSession(sessionId: String, transform: (WorkoutSession) -> WorkoutSession) = local.mutateActiveSession(sessionId, transform)
    fun requestCancelSession(sessionId: String) = local.requestCancelSession(sessionId)
    suspend fun finishWorkout() = local.finishWorkout()?.also { logs.attemptFinishedWorkout(it.userId, it.id) }
    suspend fun cancelWorkout() = local.cancelWorkout()
    suspend fun updateSessionRestOverride(seconds: Int) = local.updateSessionRestOverride(seconds)
    suspend fun getSessionRestOverride() = local.getSessionRestOverride()
}

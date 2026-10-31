package com.diajarkoding.imfit.presentation.ui.workout

import com.diajarkoding.imfit.domain.model.WorkoutSession
import com.diajarkoding.imfit.domain.model.supportsRepetitions
import com.diajarkoding.imfit.domain.repository.AuthRepository
import com.diajarkoding.imfit.domain.repository.RoutineError
import com.diajarkoding.imfit.domain.repository.RoutineRepository
import com.diajarkoding.imfit.domain.repository.WorkoutRepository

internal data class RoutineSessionStart(
    val session: WorkoutSession,
    val restored: Boolean,
    val lastKnownRevision: Long? = null
)

/** Session snapshots are execution state, never a source for routine CRUD. */
internal class RoutineSessionStarter(
    private val workouts: WorkoutRepository,
    private val routines: RoutineRepository,
    private val auth: AuthRepository
) {
    suspend fun start(routineId: String, expectedSessionId: String?): RoutineSessionStart? {
        val ownerId = auth.getLocalUserId() ?: throw RoutineError.Unauthorized()
        val existing = workouts.getActiveSessionForTimer()
        requireOwner(ownerId)
        if (expectedSessionId != null && existing?.id != expectedSessionId) return null
        if (existing != null) return RoutineSessionStart(existing, restored = true)

        val response = routines.getRoutineById(routineId)
        requireOwner(ownerId)
        val failure = response.exceptionOrNull()
        val offline = failure is RoutineError.Network
        val routine = when {
            response.isSuccess -> response.getOrThrow() ?: throw RoutineError.NotFound()
            offline -> routines.getCachedRoutine(ownerId, routineId) ?: throw failure!!
            else -> throw failure!!
        }
        if (routine.userId != ownerId) throw RoutineError.Unauthorized()
        if (routine.id != routineId || routine.exercises.isEmpty()) {
            throw RoutineError.ValidationError("Routine must contain at least one exercise.")
        }
        if (routine.revision < 0 || routine.exercises.any {
                !it.exercise.isActive || !it.exercise.supportsRepetitions ||
                    it.sets !in 1..20 || it.reps !in 1..100 || it.restSeconds !in 0..600
            }) throw RoutineError.ValidationError("Routine contains an unsupported or invalid prescription.")
        requireOwner(ownerId)
        // A session may have appeared while the remote read was in flight. Never replace it.
        val resumed = workouts.getActiveSessionForTimer()
        requireOwner(ownerId)
        if (resumed != null) return RoutineSessionStart(resumed, restored = true)
        val session = workouts.startWorkout(routine)
        requireOwner(ownerId)
        return RoutineSessionStart(session, restored = false, lastKnownRevision = routine.revision.takeIf { offline })
    }

    private suspend fun requireOwner(ownerId: String) {
        if (auth.getLocalUserId() != ownerId) throw RoutineError.Unauthorized()
    }
}

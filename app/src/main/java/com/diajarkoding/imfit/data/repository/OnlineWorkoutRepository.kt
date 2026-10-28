package com.diajarkoding.imfit.data.repository

import com.diajarkoding.imfit.data.remote.online.*
import com.diajarkoding.imfit.domain.model.ExerciseInputDefaults
import com.diajarkoding.imfit.domain.model.TemplateExercise
import com.diajarkoding.imfit.domain.model.WorkoutLog
import com.diajarkoding.imfit.domain.model.WorkoutSession
import com.diajarkoding.imfit.domain.model.WorkoutTemplate
import com.diajarkoding.imfit.domain.repository.WorkoutRepository
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton

/** Runtime facade with no Room, local store or legacy upload coordinator in its dependency graph.
 * Bulk legacy read APIs deliberately fail: screens must use bounded remote query contracts.
 */
@Singleton
class OnlineWorkoutRepository @Inject constructor(
    private val reads: RemoteReadGateway,
    private val sessions: OnlineSessionStore,
    private val templates: TemplateEditBridge,
) : WorkoutRepository {
    override suspend fun getTemplates(userId: String): List<WorkoutTemplate> = error("Use remote template paging")
    override suspend fun getTemplateById(templateId: String) = reads.template(templateId).second
    override suspend fun createTemplate(userId: String, name: String, exercises: List<TemplateExercise>): WorkoutTemplate {
        require(reads.auth.capture().identity.ownerId == userId)
        val receipt = templates.save(null, name, exercises)
        return reads.template(receipt.templateId).second
    }
    override suspend fun updateTemplate(templateId: String, name: String, exercises: List<TemplateExercise>): WorkoutTemplate {
        val (source, _) = reads.template(templateId)
        templates.save(source, name, exercises)
        return reads.template(templateId).second
    }
    override suspend fun updateTemplateExercises(templateId: String, exercises: List<TemplateExercise>): WorkoutTemplate {
        val (source, _) = reads.template(templateId)
        templates.save(source, source.name, exercises)
        return reads.template(templateId).second
    }
    override suspend fun updateTemplateExercise(templateId: String, exerciseId: String, sets: Int, reps: Int, restSeconds: Int): WorkoutTemplate {
        val (source, current) = reads.template(templateId)
        templates.save(source, source.name, current.exercises.map { if (it.id == exerciseId) it.copy(sets = sets, reps = reps, restSeconds = restSeconds) else it })
        return reads.template(templateId).second
    }
    override suspend fun deleteTemplate(templateId: String): Boolean {
        val (source, current) = reads.template(templateId)
        templates.save(source, source.name, current.exercises, deleted = true)
        return true
    }

    override suspend fun startWorkout(template: WorkoutTemplate): WorkoutSession {
        val identity = reads.auth.capture().identity
        require(template.userId == identity.ownerId)
        val source = reads.readFor(identity, OnlineRpc.templateDetail(template.id))
        return sessions.start(source.id, source.revision).toDomain()
    }
    override suspend fun getActiveSession() = sessions.reload()?.takeIf { it.status in setOf(SessionStatus.ACTIVE, SessionStatus.PAUSED) }?.toDomain()
    override suspend fun getActiveSessionForTimer() = sessions.peek()?.toDomain()
    override suspend fun isActiveSessionForTimer(userId: String, workoutId: String): Boolean {
        if (reads.auth.capture().identity.ownerId != userId) return false
        return sessions.peek()?.id == workoutId
    }
    override suspend fun mutateActiveSession(sessionId: String, transform: (WorkoutSession) -> WorkoutSession) = sessions.mutate(sessionId, transform).toDomain()
    override suspend fun updateActiveSession(session: WorkoutSession): Unit = error("Whole session writes are forbidden; use versioned commands")
    override fun requestCancelSession(sessionId: String): Unit = error("Await cancel acknowledgement before navigation")
    override suspend fun finishWorkout(): WorkoutLog? {
        val current = sessions.state.value.snapshot ?: return null
        val receipt = sessions.finish(current.id)
        return getWorkoutLogById(requireNotNull(receipt.workoutId))
    }
    override suspend fun cancelWorkout() {
        // Already-terminal work is a no-op via the retained receipt; a cold store must confirm
        // any remotely active session instead of pretending success without visibility.
        val state = sessions.state.value
        val snapshot = state.snapshot
        when {
            snapshot != null && snapshot.status !in setOf(SessionStatus.ACTIVE, SessionStatus.PAUSED) -> return
            snapshot != null -> sessions.cancel(snapshot.id)
            else -> sessions.reload()?.takeIf { it.status in setOf(SessionStatus.ACTIVE, SessionStatus.PAUSED) }?.let { sessions.cancel(it.id) }
        }
    }
    override suspend fun updateSessionRestOverride(seconds: Int) {
        require(seconds in 0..600)
        val snapshot = sessions.peek() ?: throw OnlineFailure(RemoteError.NotFound)
        snapshot.exercises.forEach { child -> if (child.restSeconds != seconds) sessions.command(snapshot.id, SessionCommand.SetRestSeconds(child.id, seconds)) }
    }
    override suspend fun getSessionRestOverride(): Int? = sessions.peek()?.exercises?.map { it.restSeconds }?.distinct()?.singleOrNull()

    override suspend fun getWorkoutLogs(userId: String): List<WorkoutLog> = error("Use bounded remote history")
    override suspend fun getWorkoutLogById(logId: String): WorkoutLog {
        val identity = reads.auth.capture().identity
        val detail = reads.readFor(identity, OnlineRpc.workoutDetail(logId))
        val start = OffsetDateTime.parse(detail.startTime).toInstant().toEpochMilli()
        return WorkoutLog(detail.id, identity.ownerId, detail.templateName, start, start,
            OffsetDateTime.parse(detail.endTime).toInstant().toEpochMilli(), detail.totalVolume.toFloat(), detail.exerciseLogs.map { child ->
                com.diajarkoding.imfit.domain.model.ExerciseLog(com.diajarkoding.imfit.domain.model.Exercise(child.exerciseId, child.exerciseName,
                    com.diajarkoding.imfit.domain.model.MuscleCategory.entries.firstOrNull { it.displayName == child.muscleCategory } ?: com.diajarkoding.imfit.domain.model.MuscleCategory.CHEST, ""),
                    child.sets.map { com.diajarkoding.imfit.domain.model.WorkoutSet(it.setNumber, it.weight.toFloat(), it.reps, it.isCompleted) },
                    inputDefaults = child.inputDefaults?.let { ExerciseInputDefaults(it.sets, it.reps) })
            })
    }
    override suspend fun getLastWorkoutLog(userId: String): WorkoutLog? {
        val identity = reads.auth.capture().identity
        require(identity.ownerId == userId)
        return reads.readFor(identity, OnlineRpc.homeSummary()).lastWorkout?.let { getWorkoutLogById(it.id) }
    }
    override suspend fun getLastExerciseLog(exerciseId: String): com.diajarkoding.imfit.domain.model.ExerciseLog? = error("Use provenance-aware prefill")
    override suspend fun getLastWeightsForExercise(exerciseId: String, userId: String): Map<Int, Float> = error("Input defaults are required for prefill")
    override suspend fun getPrefillForExercise(exerciseId: String, userId: String, defaults: ExerciseInputDefaults): Map<Int, Pair<Float, Int>> {
        val identity = reads.auth.capture().identity
        require(identity.ownerId == userId)
        return reads.readFor(identity, OnlineRpc.prefill(exerciseId, InputDefaults(defaults.sets, defaults.reps))).sets.associate {
            it.setNumber to (it.weight.toFloat() to it.reps)
        }
    }
}

package com.diajarkoding.imfit.data.sync

import androidx.room.withTransaction
import com.diajarkoding.imfit.data.local.database.IMFITDatabase
import com.diajarkoding.imfit.data.local.entity.ExerciseLogEntity
import com.diajarkoding.imfit.data.local.entity.TemplateExerciseEntity
import com.diajarkoding.imfit.data.local.entity.WorkoutLogEntity
import com.diajarkoding.imfit.data.local.entity.WorkoutSetEntity
import com.diajarkoding.imfit.data.local.entity.WorkoutTemplateEntity
import com.diajarkoding.imfit.data.local.sync.PendingOperation

internal data class LocalWorkoutSnapshot(
    val parent: WorkoutLogEntity,
    val exercises: List<ExerciseLogEntity>,
    val sets: List<WorkoutSetEntity>,
    val revision: Long,
)

internal data class LocalTemplateSnapshot(
    val parent: WorkoutTemplateEntity,
    val exercises: List<TemplateExerciseEntity>,
    val revision: Long,
)

/**
 * Capture a consistent, owner-scoped local graph in a short transaction; send outside the lock.
 * Capture never changes flags. Acknowledgement compares durable mutation identity, not content/time.
 * Not a session fence: production callers must also hold SessionCommitGate around local access.
 */
internal class AggregateSnapshotStore(private val database: IMFITDatabase) {
    suspend fun workout(ownerId: String, id: String): LocalWorkoutSnapshot? = database.withTransaction {
        val parent = database.workoutLogDao().getWorkoutLogById(id)
            ?.takeIf { it.userId == ownerId && it.pendingOperation != null } ?: return@withTransaction null
        requireKnownOperation(parent.pendingOperation)
        val exercises = database.exerciseLogDao().getExerciseLogsByWorkoutLogId(id)
            .sortedWith(compareBy<ExerciseLogEntity> { it.orderIndex }.thenBy { it.id })
        val sets = database.workoutSetDao().getSetsByWorkoutLogId(id)
            .sortedWith(compareBy<WorkoutSetEntity> { it.exerciseLogId }.thenBy { it.setNumber }.thenBy { it.id })
        val exerciseIds = exercises.associateBy { it.id }
        check(exerciseIds.size == exercises.size && exercises.all { it.workoutLogId == id })
        check(sets.map { it.id }.toSet().size == sets.size)
        check(sets.all { set -> set.workoutLogId == id && exerciseIds[set.exerciseLogId]?.exerciseId == set.exerciseId })
        LocalWorkoutSnapshot(parent, exercises, sets, revision(ownerId, "workout", id))
    }

    suspend fun template(ownerId: String, id: String): LocalTemplateSnapshot? = database.withTransaction {
        val parent = database.workoutTemplateDao().getTemplateByIdIncludingDeleted(id)
            ?.takeIf { it.userId == ownerId && it.pendingOperation != null } ?: return@withTransaction null
        requireKnownOperation(parent.pendingOperation)
        val exercises = database.templateExerciseDao().getExercisesForTemplateList(id)
            .sortedWith(compareBy<TemplateExerciseEntity> { it.orderIndex }.thenBy { it.exerciseId })
        check(exercises.all { it.templateId == id } && exercises.map { it.exerciseId }.toSet().size == exercises.size)
        LocalTemplateSnapshot(parent, exercises, revision(ownerId, "template", id))
    }

    /** Caller must hold the session fence; this transaction supplies the independent revision CAS. */
    suspend fun acknowledge(snapshot: LocalWorkoutSnapshot): Boolean = database.withTransaction {
        val expected = snapshot.parent
        val current = database.workoutLogDao().getWorkoutLogById(expected.id)
        if (current?.userId != expected.userId || current.pendingOperation != expected.pendingOperation ||
            revision(expected.userId, "workout", expected.id) != snapshot.revision) return@withTransaction false
        database.workoutLogDao().markAsSynced(expected.id)
        database.exerciseLogDao().markWorkoutExerciseLogsAsSynced(expected.id)
        database.workoutSetDao().markWorkoutSetsAsSynced(expected.id)
        true
    }

    /** Template transport safety (D6) is not supplied by a safe local acknowledgement. */
    suspend fun acknowledge(snapshot: LocalTemplateSnapshot): Boolean = database.withTransaction {
        val expected = snapshot.parent
        val current = database.workoutTemplateDao().getTemplateByIdIncludingDeleted(expected.id)
        if (current?.userId != expected.userId || current.pendingOperation != expected.pendingOperation ||
            revision(expected.userId, "template", expected.id) != snapshot.revision) return@withTransaction false
        database.workoutTemplateDao().markAsSynced(expected.id, "SYNCED")
        database.templateExerciseDao().markTemplateExercisesAsSynced(expected.id)
        true
    }

    private suspend fun revision(owner: String, type: String, id: String): Long =
        checkNotNull(database.aggregateRevisionDao().revision(owner, type, id)) { "Missing aggregate mutation identity" }
            .also { check(it > 0) { "Invalid aggregate mutation identity" } }

    private fun requireKnownOperation(operation: String?) {
        check(PendingOperation.entries.any { it.name == operation }) { "Unrecognized pending operation; capture rejected" }
    }
}

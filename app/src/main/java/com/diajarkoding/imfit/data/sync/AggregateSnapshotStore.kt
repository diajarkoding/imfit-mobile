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
)

internal data class LocalTemplateSnapshot(
    val parent: WorkoutTemplateEntity,
    val exercises: List<TemplateExerciseEntity>,
)

/**
 * Capture a consistent, owner-scoped local graph in a short transaction; send outside the lock.
 * No flags are modified. Intentionally has no acknowledge(snapshot) API: full-content equality
 * cannot distinguish same-ms/operation ABA. A durable revision contract needs separate approval.
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
        LocalWorkoutSnapshot(parent, exercises, sets)
    }

    suspend fun template(ownerId: String, id: String): LocalTemplateSnapshot? = database.withTransaction {
        val parent = database.workoutTemplateDao().getTemplateByIdIncludingDeleted(id)
            ?.takeIf { it.userId == ownerId && it.pendingOperation != null } ?: return@withTransaction null
        requireKnownOperation(parent.pendingOperation)
        val exercises = database.templateExerciseDao().getExercisesForTemplateList(id)
            .sortedWith(compareBy<TemplateExerciseEntity> { it.orderIndex }.thenBy { it.exerciseId })
        check(exercises.all { it.templateId == id } && exercises.map { it.exerciseId }.toSet().size == exercises.size)
        LocalTemplateSnapshot(parent, exercises)
    }

    private fun requireKnownOperation(operation: String?) {
        check(PendingOperation.entries.any { it.name == operation }) { "Unrecognized pending operation; capture rejected" }
    }
}

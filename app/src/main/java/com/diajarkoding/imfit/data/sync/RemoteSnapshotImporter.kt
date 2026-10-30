package com.diajarkoding.imfit.data.sync

import androidx.room.withTransaction
import com.diajarkoding.imfit.data.local.database.IMFITDatabase
import com.diajarkoding.imfit.data.local.entity.*
import com.diajarkoding.imfit.data.local.sync.SyncStatus

/** Apply only complete, owner-scoped snapshots. Observers never see a parent without its children. */
internal class RemoteSnapshotImporter(private val database: IMFITDatabase) {
    suspend fun template(
        parent: WorkoutTemplateEntity,
        exercises: List<TemplateExerciseEntity>,
        isCurrentOwner: () -> Boolean = { true }
    ) {
        require(exercises.all { it.templateId == parent.id })
        require(exercises.map { it.exerciseId }.distinct().size == exercises.size)
        database.withTransaction {
            if (!isCurrentOwner()) return@withTransaction
            if (!canApplyRemoteTemplate(parent)) return@withTransaction
            database.workoutTemplateDao().insertTemplate(parent)
            database.templateExerciseDao().deleteExercisesByTemplate(parent.id)
            if (exercises.isNotEmpty()) {
                database.templateExerciseDao().insertTemplateExercises(exercises)
            }
        }
    }

    /**
     * Reconciles a complete active-template snapshot for [userId].
     * Only marks absent local templates as deleted when [allowMissingDeletion] is true
     * (no concurrent local mutation occurred while the remote request was in flight)
     * AND the local template + all its children are clean (SYNCED with no pendingOperation).
     */
    suspend fun reconcileActiveTemplates(
        userId: String,
        snapshots: List<Pair<WorkoutTemplateEntity, List<TemplateExerciseEntity>>>,
        allowMissingDeletion: () -> Boolean = { true },
        isCurrentOwner: () -> Boolean = { true }
    ) {
        snapshots.forEach { (parent, exercises) ->
            require(parent.userId == userId)
            require(exercises.all { it.templateId == parent.id })
            require(exercises.map { it.exerciseId }.distinct().size == exercises.size)
        }
        database.withTransaction {
            if (!isCurrentOwner()) return@withTransaction
            val remoteIds = snapshots.map { it.first.id }.toSet()
            for ((parent, exercises) in snapshots) {
                if (!canApplyRemoteTemplate(parent)) continue
                database.workoutTemplateDao().insertTemplate(parent)
                database.templateExerciseDao().deleteExercisesByTemplate(parent.id)
                if (exercises.isNotEmpty()) {
                    database.templateExerciseDao().insertTemplateExercises(exercises)
                }
            }
            if (allowMissingDeletion() && isCurrentOwner()) {
                val existingLocal = database.workoutTemplateDao().getAllTemplatesByUserIncludingDeleted(userId)
                for (local in existingLocal) {
                    if (local.isDeleted || local.id in remoteIds) continue
                    if (isLocalTemplateClean(local)) {
                        database.workoutTemplateDao().deleteTemplate(local.id)
                        database.templateExerciseDao().deleteExercisesByTemplate(local.id)
                    }
                }
            }
        }
    }

    private suspend fun canApplyRemoteTemplate(parent: WorkoutTemplateEntity): Boolean {
        val local = database.workoutTemplateDao().getTemplateByIdIncludingDeleted(parent.id) ?: return true
        if (local.userId != parent.userId) return false
        if (!isLocalTemplateClean(local)) return false
        if (local.updatedAt > parent.updatedAt) return false
        return true
    }

    private suspend fun isLocalTemplateClean(local: WorkoutTemplateEntity): Boolean {
        if (local.syncStatus != SyncStatus.SYNCED.name || local.pendingOperation != null) return false
        val localChildren = database.templateExerciseDao().getExercisesForTemplateList(local.id)
        return localChildren.all {
            it.syncStatus == SyncStatus.SYNCED.name && it.pendingOperation == null
        }
    }

    suspend fun workout(
        parent: WorkoutLogEntity,
        exercises: List<ExerciseLogEntity>,
        sets: List<WorkoutSetEntity>,
        isCurrentOwner: () -> Boolean = { true }
    ) {
        require(exercises.all { it.workoutLogId == parent.id })
        require(exercises.map { it.id }.distinct().size == exercises.size)
        require(sets.map { it.id }.distinct().size == sets.size)
        require(sets.all { set -> set.workoutLogId == parent.id && exercises.any {
            it.id == set.exerciseLogId && it.exerciseId == set.exerciseId
        } })
        database.withTransaction {
            if (!isCurrentOwner()) return@withTransaction
            val local = database.workoutLogDao().getWorkoutLogById(parent.id)
            if (local != null && (local.userId != parent.userId || local.syncStatus != SyncStatus.SYNCED.name ||
                    local.pendingOperation != null || local.updatedAt > parent.updatedAt)) return@withTransaction
            val existingExercises = database.exerciseLogDao().getExerciseLogsByWorkoutLogId(parent.id)
            val existingSets = database.workoutSetDao().getSetsByWorkoutLogId(parent.id)
            val hasUnsyncedChildren = existingExercises.any {
                it.syncStatus != SyncStatus.SYNCED.name || it.pendingOperation != null
            } || existingSets.any {
                it.syncStatus != SyncStatus.SYNCED.name || it.pendingOperation != null
            }
            if (hasUnsyncedChildren) return@withTransaction
            database.workoutLogDao().insertWorkoutLog(parent)
            // Keep local target provenance only when remote performance is unchanged.
            val withProvenance = exercises.map { exercise ->
                val old = existingExercises.find { it.id == exercise.id }
                fun performance(rows: List<WorkoutSetEntity>) = rows.filter { it.exerciseLogId == exercise.id }
                    .sortedBy { it.setNumber }.map { listOf(it.setNumber, it.weight, it.reps, it.isCompleted) }
                exercise.copy(inputDefaults = old?.inputDefaults?.takeIf { performance(existingSets) == performance(sets) })
            }
            database.workoutSetDao().deleteSetsByWorkoutLog(parent.id)
            database.exerciseLogDao().deleteExerciseLogsByWorkout(parent.id)
            database.exerciseLogDao().insertExerciseLogs(withProvenance)
            database.workoutSetDao().insertWorkoutSets(sets)
        }
    }
}

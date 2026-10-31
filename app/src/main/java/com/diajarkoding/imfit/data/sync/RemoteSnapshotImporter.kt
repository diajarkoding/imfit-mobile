package com.diajarkoding.imfit.data.sync

import androidx.room.withTransaction
import com.diajarkoding.imfit.data.local.database.IMFITDatabase
import com.diajarkoding.imfit.data.local.entity.*
import com.diajarkoding.imfit.data.local.sync.SyncStatus

/** Apply only complete, owner-scoped snapshots. Observers never see a parent without its children. */
internal class RemoteSnapshotImporter(private val database: IMFITDatabase) {
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

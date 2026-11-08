package com.diajarkoding.imfit.data.sync

import androidx.room.withTransaction
import com.diajarkoding.imfit.data.local.database.IMFITDatabase
import com.diajarkoding.imfit.data.local.entity.*
import com.diajarkoding.imfit.data.local.sync.SyncStatus
import com.diajarkoding.imfit.domain.model.ExerciseInputDefaults

/** Apply only complete, owner-scoped snapshots. Observers never see a parent without its children. */
internal class RemoteSnapshotImporter(private val database: IMFITDatabase) {
    suspend fun workout(
        parent: WorkoutLogEntity,
        exercises: List<ExerciseLogEntity>,
        sets: List<WorkoutSetEntity>,
        isCurrentOwner: () -> Boolean = { true }
    ) {
        require(parent.measurementVersion == null || parent.measurementVersion == 3) { "Unsupported workout measurement version" }
        require(exercises.all { it.measurementVersion == parent.measurementVersion }) { "Mixed exercise measurement versions" }
        require(sets.all { it.measurementVersion == parent.measurementVersion }) { "Mixed set measurement versions" }
        if (parent.measurementVersion == 3) {
            exercises.forEach {
                val snapshot = ExerciseInputDefaults.fromStoredString(requireNotNull(it.inputDefaults))
                require(snapshot.schemaVersion == 3 && snapshot.trackingType == it.trackingType &&
                    snapshot.modality?.name == it.modality) { "Invalid version 3 exercise snapshot" }
            }
        }
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
                exercise.copy(inputDefaults = exercise.inputDefaults ?: old?.inputDefaults?.takeIf {
                    sameWorkoutExercisePerformance(old, exercise, existingSets, sets)
                })
            }
            database.workoutSetDao().deleteSetsByWorkoutLog(parent.id)
            database.exerciseLogDao().deleteExerciseLogsByWorkout(parent.id)
            database.exerciseLogDao().insertExerciseLogs(withProvenance)
            database.workoutSetDao().insertWorkoutSets(sets)
        }
    }
}

/** Ignore only transport bookkeeping and absent provenance, never recorded performance or identity. */
internal fun sameWorkoutExercisePerformance(
    old: ExerciseLogEntity,
    remote: ExerciseLogEntity,
    existingSets: List<WorkoutSetEntity>,
    remoteSets: List<WorkoutSetEntity>
): Boolean {
    if (old.id != remote.id || old.workoutLogId != remote.workoutLogId || old.exerciseId != remote.exerciseId ||
        old.exerciseName != remote.exerciseName || old.muscleCategory != remote.muscleCategory ||
        old.orderIndex != remote.orderIndex || old.totalVolume != remote.totalVolume ||
        old.totalSets != remote.totalSets || old.totalReps != remote.totalReps ||
        old.trackingType != remote.trackingType || old.modality != remote.modality ||
        old.totalDurationSeconds != remote.totalDurationSeconds || old.totalDistanceMeters != remote.totalDistanceMeters ||
        old.additionalVolume != remote.additionalVolume || old.metricQuality != remote.metricQuality ||
        old.measurementVersion != remote.measurementVersion || old.recordedLoadVolume != remote.recordedLoadVolume) return false
    if (remote.inputDefaults != null &&
        old.inputDefaults?.let(ExerciseInputDefaults::fromStoredString) != ExerciseInputDefaults.fromStoredString(remote.inputDefaults)) return false
    val order = compareBy<WorkoutSetEntity> { it.setNumber }.thenBy { it.id }
    val before = existingSets.filter { it.exerciseLogId == old.id }.sortedWith(order)
    val after = remoteSets.filter { it.exerciseLogId == remote.id }.sortedWith(order)
    if (before.size != after.size) return false
    return before.indices.all { index ->
        val a = before[index]
        val b = after[index]
        a.id == b.id && a.exerciseLogId == b.exerciseLogId && a.workoutLogId == b.workoutLogId &&
            a.exerciseId == b.exerciseId && a.setNumber == b.setNumber && a.weight == b.weight &&
            a.reps == b.reps && a.isCompleted == b.isCompleted && a.durationSeconds == b.durationSeconds &&
            a.distanceMeters == b.distanceMeters && a.additionalWeight == b.additionalWeight &&
            a.assistanceWeight == b.assistanceWeight && a.targetRowId == b.targetRowId &&
            a.caloriesKcal == b.caloriesKcal && a.speedKph == b.speedKph && a.lapReps == b.lapReps &&
            a.inclinePercent == b.inclinePercent && a.measurementVersion == b.measurementVersion &&
            a.restElapsedMs == b.restElapsedMs && a.restEndReason == b.restEndReason
    }
}

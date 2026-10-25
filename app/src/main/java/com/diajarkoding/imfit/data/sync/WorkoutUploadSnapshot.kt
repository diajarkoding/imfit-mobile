package com.diajarkoding.imfit.data.sync

import com.diajarkoding.imfit.data.local.entity.ExerciseLogEntity
import com.diajarkoding.imfit.data.local.entity.WorkoutLogEntity
import com.diajarkoding.imfit.data.local.entity.WorkoutSetEntity

/** Exact immutable content used by one manual attempt; no timestamp-only child acknowledgement. */
data class WorkoutUploadSnapshot(
    val parent: WorkoutLogEntity,
    val exercises: List<ExerciseLogEntity>,
    val sets: List<WorkoutSetEntity>,
) {
    fun matches(current: WorkoutUploadSnapshot): Boolean = parent == current.parent &&
        exercises.sortedBy { it.id } == current.exercises.sortedBy { it.id } &&
        sets.sortedBy { it.id } == current.sets.sortedBy { it.id }
}

package com.diajarkoding.imfit.data.remote.online

import com.diajarkoding.imfit.domain.model.Exercise
import com.diajarkoding.imfit.domain.model.ExerciseInputDefaults
import com.diajarkoding.imfit.domain.model.MuscleCategory
import com.diajarkoding.imfit.domain.model.WorkoutSession
import java.time.OffsetDateTime

/** Presentation projection only. Remote child/set identities remain on the confirmed snapshot. */
fun SessionSnapshot.toDomain(): WorkoutSession {
    require(exercises.map { it.id }.distinct().size == exercises.size)
    require(exercises.map { it.exerciseId }.distinct().size == exercises.size)
    return WorkoutSession(id, templateId.orEmpty(), templateName, OffsetDateTime.parse(startedAt).toInstant().toEpochMilli(),
        exercises.sortedBy { it.orderIndex }.map { child ->
            require(child.sets.map { it.id }.distinct().size == child.sets.size)
            require(child.sets.map { it.setNumber }.distinct().size == child.sets.size)
            com.diajarkoding.imfit.domain.model.ExerciseLog(
                Exercise(child.exerciseId, child.name, MuscleCategory.entries.firstOrNull { it.displayName == child.muscleCategory } ?: MuscleCategory.CHEST, ""),
                child.sets.sortedBy { it.setNumber }.map { set -> com.diajarkoding.imfit.domain.model.WorkoutSet(set.setNumber, set.weight.toFloat(), set.reps, set.isCompleted) },
                child.restSeconds, ExerciseInputDefaults(child.inputDefaults.sets, child.inputDefaults.reps))
        }, exercises.sortedBy { it.orderIndex }.indexOfFirst { it.id == currentExerciseId }.coerceAtLeast(0),
        status == SessionStatus.PAUSED, totalPausedMs, pausedAt?.let { OffsetDateTime.parse(it).toInstant().toEpochMilli() })
}

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

/** Temporary compatibility adapter. Computes typed commands from the latest confirmed projection;
 * it never serializes caller-provided aggregates, pause timestamps, whole JSON or computed totals.
 * A call must represent exactly one user intent. Batched additions use dedicated typed commands.
 */
internal fun SessionSnapshot.commandFor(next: WorkoutSession, newId: () -> String): SessionCommand? {
    val current = toDomain()
    require(next.id == current.id && next.templateId == current.templateId && next.templateName == current.templateName && next.startTime == current.startTime)
    val commands = mutableListOf<SessionCommand>()
    if (next.isPaused != current.isPaused) {
        require(next.exerciseLogs == current.exerciseLogs && next.currentExerciseIndex == current.currentExerciseIndex)
        return if (next.isPaused) SessionCommand.Pause else SessionCommand.Resume
    }
    require(next.totalPausedTimeMs == current.totalPausedTimeMs && next.lastPauseTime == current.lastPauseTime)
    require(next.exerciseLogs.map { it.exercise.id }.distinct().size == next.exerciseLogs.size)
    val beforeIds = current.exerciseLogs.map { it.exercise.id }
    val afterIds = next.exerciseLogs.map { it.exercise.id }
    require(afterIds.filter { it in beforeIds } == beforeIds.filter { it in afterIds }) { "Reordering is not a session command" }
    current.exerciseLogs.filter { it.exercise.id !in afterIds }.forEach { removed ->
        commands += SessionCommand.RemoveExercise(exercises.single { it.exerciseId == removed.exercise.id }.id)
    }
    next.exerciseLogs.filter { it.exercise.id !in beforeIds }.forEach { added ->
        commands += SessionCommand.AddExercise(newId(), added.exercise.id)
    }
    next.exerciseLogs.filter { it.exercise.id in beforeIds }.forEach { after ->
        val before = current.exerciseLogs.single { it.exercise.id == after.exercise.id }
        val remote = exercises.single { it.exerciseId == after.exercise.id }
        require(after.exercise == before.exercise && after.inputDefaults == before.inputDefaults)
        if (after.restSeconds != before.restSeconds) commands += SessionCommand.SetRestSeconds(remote.id, after.restSeconds)
        require(after.sets.map { it.setNumber }.distinct().size == after.sets.size)
        before.sets.filter { old -> after.sets.none { it.setNumber == old.setNumber } }.forEach { removed ->
            commands += SessionCommand.RemoveSet(remote.id, remote.sets.single { it.setNumber == removed.setNumber }.id)
        }
        after.sets.filter { candidate -> before.sets.none { it.setNumber == candidate.setNumber } }.forEach { _ ->
            commands += SessionCommand.AddSet(remote.id, newId())
        }
        after.sets.filter { candidate -> before.sets.any { it.setNumber == candidate.setNumber } }.forEach { changed ->
            val old = before.sets.single { it.setNumber == changed.setNumber }
            val remoteSet = remote.sets.single { it.setNumber == changed.setNumber }
            if (changed.weight != old.weight || changed.reps != old.reps) {
                require(!old.isCompleted && !changed.isCompleted)
                commands += SessionCommand.EditSet(remote.id, remoteSet.id,
                    java.math.BigDecimal(changed.weight.toString()).stripTrailingZeros().toPlainString(), changed.reps)
            }
            if (changed.isCompleted != old.isCompleted) commands += if (changed.isCompleted) SessionCommand.CompleteSet(remote.id, remoteSet.id)
                else SessionCommand.UncompleteSet(remote.id, remoteSet.id)
        }
    }
    if (next.currentExerciseIndex != current.currentExerciseIndex) {
        require(beforeIds == afterIds)
        val exercise = next.exerciseLogs.getOrNull(next.currentExerciseIndex) ?: error("Invalid selection")
        commands += SessionCommand.SelectExercise(exercises.single { it.exerciseId == exercise.exercise.id }.id)
    }
    require(commands.size <= 1) { "Use one versioned command per intent" }
    return commands.singleOrNull()
}

package com.diajarkoding.imfit.domain.model

/** Pure, identity-based session edits. Re-evaluated against the latest persisted session. */
fun WorkoutSession.appendExercises(exercises: List<ExerciseLog>): WorkoutSession {
    val existing = exerciseLogs.map { it.exercise.id }.toSet()
    return copy(exerciseLogs = exerciseLogs + exercises.distinctBy { it.exercise.id }.filterNot { it.exercise.id in existing })
}

fun WorkoutSession.removeIncompleteExercise(exerciseId: String): WorkoutSession {
    val index = exerciseLogs.indexOfFirst { it.exercise.id == exerciseId }
    if (index < 0 || exerciseLogs[index].sets.any { it.isCompleted }) return this
    val remaining = exerciseLogs.filterIndexed { i, _ -> i != index }
    val nextIndex = when {
        remaining.isEmpty() -> 0
        index < currentExerciseIndex -> currentExerciseIndex - 1
        else -> currentExerciseIndex.coerceAtMost(remaining.lastIndex)
    }
    return copy(exerciseLogs = remaining, currentExerciseIndex = nextIndex)
}

fun validateActiveSessionEdit(current: WorkoutSession, next: WorkoutSession) {
    require(next.id == current.id && next.templateId == current.templateId && next.startTime == current.startTime)
    require(next.exerciseLogs.map { it.exercise.id }.distinct().size == next.exerciseLogs.size)
    require(current.exerciseLogs.filter { log -> log.sets.any { it.isCompleted } }
        .all { completed -> next.exerciseLogs.any { it.exercise.id == completed.exercise.id } }) {
        "An exercise with completed sets cannot be removed"
    }
}

private const val MAX_V3_SETS = 20

/**
 * T41: add an active set to a v3 exercise. A fresh result UUID and a fresh extra target (rest 60) are created;
 * values follow the exercise's first routine target (not the last result). The routine target list is untouched.
 */
fun ExerciseLog.addV3Set(
    newSetId: String = java.util.UUID.randomUUID().toString(),
    newRowId: String = java.util.UUID.randomUUID().toString()
): ExerciseLog {
    require(measurementVersion == 3) { "Not a v3 exercise" }
    require(sets.size < MAX_V3_SETS) { "At most $MAX_V3_SETS sets per exercise" }
    val defaults = requireNotNull(inputDefaults) { "Missing v3 snapshot" }
    val template = requireNotNull(defaults.setTargets?.firstOrNull()) { "Missing initial target" }
    val allRowIds = defaults.setTargets.orEmpty().map { it.rowId } + defaults.extraSetTargets.map { it.rowId }
    require(newRowId !in allRowIds && sets.none { it.id == newSetId }) { "Identity already used" }
    val target = template.copy(rowId = newRowId, setNumber = defaults.extraSetTargets.size + 1, restSeconds = 60)
    val strength = trackingType == TrackingType.WEIGHT_REPS
    val set = WorkoutSet(
        setNumber = sets.size + 1,
        weightKg = if (strength) target.weightKg else null,
        reps = if (strength) target.reps else null,
        durationSeconds = if (strength) null else target.durationSeconds,
        isCompleted = false, id = newSetId, targetRowId = newRowId, measurementVersion = 3
    )
    return copy(sets = sets + set, inputDefaults = defaults.copy(extraSetTargets = defaults.extraSetTargets + target))
}

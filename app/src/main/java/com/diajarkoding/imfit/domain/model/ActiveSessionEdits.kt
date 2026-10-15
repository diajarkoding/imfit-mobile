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

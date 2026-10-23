package com.diajarkoding.imfit.domain.model

import kotlinx.serialization.Serializable

/** Targets captured before prefill, never inferred from completed performance. */
@Serializable
data class ExerciseInputDefaults(val sets: Int, val reps: Int) {
    val key: String get() = "$sets:$reps"
}

fun TemplateExercise.inputDefaults() = ExerciseInputDefaults(sets, reps)

fun ExerciseLog.addDefaultSet(): ExerciseLog = copy(sets = sets + WorkoutSet(
    setNumber = (sets.maxOfOrNull { it.setNumber } ?: 0) + 1,
    reps = inputDefaults?.reps ?: TemplateExercise(exercise).reps))

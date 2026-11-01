package com.diajarkoding.imfit.domain.model

import kotlinx.serialization.Serializable

/** Targets captured before prefill, never inferred from completed performance. */
@Serializable
data class ExerciseInputDefaults(
    val sets: Int,
    val reps: Int,
    val trackingType: String? = null,
    val targetDurationSeconds: Int? = null,
    val targetDistanceMeters: Int? = null
) {
    val key: String get() = if (trackingType != null) {
        "$sets:$reps:$trackingType:${targetDurationSeconds ?: 0}:${targetDistanceMeters ?: 0}"
    } else {
        "$sets:$reps"
    }
}

fun TemplateExercise.inputDefaults() = ExerciseInputDefaults(
    sets = sets,
    reps = reps ?: 0,
    trackingType = trackingType.name,
    targetDurationSeconds = targetDurationSeconds,
    targetDistanceMeters = targetDistanceMeters
)

fun ExerciseLog.addDefaultSet(): ExerciseLog = copy(sets = sets + WorkoutSet(
    setNumber = (sets.maxOfOrNull { it.setNumber } ?: 0) + 1,
    reps = inputDefaults?.reps ?: (TemplateExercise(exercise).reps ?: 0),
    durationSeconds = inputDefaults?.targetDurationSeconds,
    distanceMeters = inputDefaults?.targetDistanceMeters
))

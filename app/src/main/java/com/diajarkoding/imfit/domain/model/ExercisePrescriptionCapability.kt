package com.diajarkoding.imfit.domain.model

/** Units supported by the current routine sets/reps/rest schema. */
enum class ExercisePrescriptionCapability {
    REPS_LOAD,
    UNSUPPORTED_DURATION_DISTANCE
}

// Catalog seed IDs, not translated names or muscle groups. Repetition-based cardio
// (burpees, box jumps, jumping jacks) deliberately remains supported.
private val durationDistanceExerciseIds = setOf(
    "ex_core_1", "ex_core_9",
    "ex_cardio_1", "ex_cardio_2", "ex_cardio_3", "ex_cardio_4",
    "ex_cardio_6", "ex_cardio_7", "ex_cardio_9"
)

val Exercise.prescriptionCapability: ExercisePrescriptionCapability
    get() = if (id in durationDistanceExerciseIds) {
        ExercisePrescriptionCapability.UNSUPPORTED_DURATION_DISTANCE
    } else {
        ExercisePrescriptionCapability.REPS_LOAD
    }

val Exercise.supportsRepetitions: Boolean
    get() = prescriptionCapability == ExercisePrescriptionCapability.REPS_LOAD

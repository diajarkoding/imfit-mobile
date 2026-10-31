package com.diajarkoding.imfit.domain.model

data class WorkoutSet(
    val setNumber: Int,
    val weight: Float = 0f,
    val reps: Int = 0,
    val isCompleted: Boolean = false,
    val durationSeconds: Int? = null,
    val distanceMeters: Int? = null,
    val additionalWeight: Float? = null,
    val assistanceWeight: Float? = null
) {
    val measurement: SetMeasurement
        get() = SetMeasurement(
            weight = weight,
            reps = reps,
            durationSeconds = durationSeconds,
            distanceMeters = distanceMeters,
            additionalWeight = additionalWeight,
            assistanceWeight = assistanceWeight
        )

    val volume: Float
        get() = if (isCompleted) weight * reps else 0f
}

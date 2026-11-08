package com.diajarkoding.imfit.domain.model

data class WorkoutSet(
    val setNumber: Int,
    val weightKg: Double? = 0.0,
    val reps: Int? = 0,
    val isCompleted: Boolean = false,
    val durationSeconds: Int? = null,
    val distanceMeters: Double? = null,
    val additionalWeight: Float? = null,
    val assistanceWeight: Float? = null,
    val executionStopwatch: ExecutionStopwatch? = null,
    val id: String = java.util.UUID.randomUUID().toString(),
    val targetRowId: String? = null,
    val measurementVersion: Int? = null,
    val caloriesKcal: Double? = null,
    val speedKph: Double? = null,
    val lapReps: Int? = null,
    val inclinePercent: Double? = null,
    /** Actual rest that followed this completed set (v3). Never the target rest. */
    val restOutcome: RestOutcome? = null
) {
    val measurement: SetMeasurement
        get() = SetMeasurement(
            weightKg = weightKg,
            reps = reps,
            durationSeconds = durationSeconds,
            distanceMeters = distanceMeters,
            additionalWeight = additionalWeight,
            assistanceWeight = assistanceWeight,
            caloriesKcal = caloriesKcal,
            speedKph = speedKph,
            lapReps = lapReps,
            inclinePercent = inclinePercent,
            measurementVersion = measurementVersion
        )

    val volume: Float
        get() = if (isCompleted) ((weightKg ?: 0.0) * (reps ?: 0)).toFloat() else 0f

    val paceSecondsPerKilometer: Double?
        get() {
            val seconds = durationSeconds?.takeIf { it > 0 } ?: return null
            val meters = distanceMeters?.takeIf { it > 0 } ?: return null
            return seconds.toDouble() * 1000.0 / meters
        }
}

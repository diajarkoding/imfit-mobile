package com.diajarkoding.imfit.domain.model

data class TemplateExercise(
    val exercise: Exercise,
    val sets: Int = 3,
    val reps: Int? = 8,
    val restSeconds: Int = 60,
    val trackingType: TrackingType = exercise.defaultTrackingType,
    val targetDurationSeconds: Int? = null,
    val targetDistanceMeters: Int? = null,
    // Only a restored pre-cutover immutable command may replay this obsolete wire field.
    val legacyRestSeconds: Int? = null,
    val setTargets: List<SetTarget>? = null,
    val measurementVersion: Int? = null
) {
    val setCount: Int get() = if (measurementVersion == 3) requireNotNull(setTargets).size else sets

    fun expandedLegacyTargets(scope: String): List<SetTarget> {
        require(measurementVersion == null && setTargets == null) { "Not a legacy routine target" }
        return LegacyTargetAdapter.expand(scope, sets, reps, targetDurationSeconds, targetDistanceMeters, restSeconds, trackingType)
    }

    val id: String get() = exercise.id
    val name: String get() = exercise.name
    val muscleCategory: MuscleCategory get() = exercise.muscleCategory
    val description: String get() = exercise.description
    val imageUrl: String? get() = exercise.imageUrl
    val target: ExerciseTarget
        get() = ExerciseTarget(
            sets = sets,
            targetReps = reps,
            targetDurationSeconds = targetDurationSeconds,
            targetDistanceMeters = targetDistanceMeters,
            restSeconds = restSeconds,
            setTargets = setTargets,
            measurementVersion = measurementVersion
        )
}

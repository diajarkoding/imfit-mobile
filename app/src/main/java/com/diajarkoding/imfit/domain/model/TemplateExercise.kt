package com.diajarkoding.imfit.domain.model

data class TemplateExercise(
    val exercise: Exercise,
    val sets: Int = 3,
    val reps: Int? = 8,
    val restSeconds: Int = 60,
    val trackingType: TrackingType = exercise.defaultTrackingType,
    val targetDurationSeconds: Int? = null,
    val targetDistanceMeters: Int? = null
) {
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
            restSeconds = restSeconds
        )
}

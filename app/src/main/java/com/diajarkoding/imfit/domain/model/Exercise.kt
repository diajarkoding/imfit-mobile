package com.diajarkoding.imfit.domain.model

data class Exercise(
    val id: String,
    val name: String,
    val muscleCategory: MuscleCategory,
    val description: String,
    val imageUrl: String? = null,
    val isActive: Boolean = true,
    val defaultTrackingType: TrackingType = TrackingType.WEIGHT_REPS,
    val modality: ExerciseModality = ExerciseModality.STRENGTH,
    val allowedTrackingTypes: List<TrackingType> = listOf(defaultTrackingType)
) {
    init {
        require(defaultTrackingType in allowedTrackingTypes) {
            "Exercise $id default tracking mode must be allowed"
        }
    }
}

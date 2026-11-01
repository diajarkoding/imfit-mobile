package com.diajarkoding.imfit.domain.model

data class Exercise(
    val id: String,
    val name: String,
    val muscleCategory: MuscleCategory,
    val description: String,
    val imageUrl: String? = null,
    val isActive: Boolean = true,
    val defaultTrackingType: TrackingType = deriveDefaultTrackingType(id),
    val modality: ExerciseModality = deriveModality(id),
    val allowedTrackingTypes: List<TrackingType> = deriveAllowedTrackingTypes(id, defaultTrackingType)
) {
    companion object {
        fun deriveDefaultTrackingType(id: String): TrackingType = when (id) {
            "ex_core_1", "ex_core_9" -> TrackingType.DURATION
            "ex_cardio_1", "ex_cardio_2", "ex_cardio_3", "ex_cardio_6", "ex_cardio_7",
            "ex_cardio_11", "ex_cardio_12", "ex_cardio_13", "ex_cardio_14" -> TrackingType.DISTANCE_DURATION
            "ex_back_17" -> TrackingType.ASSISTED_REPS
            "ex_cardio_4", "ex_cardio_5", "ex_cardio_8", "ex_cardio_9", "ex_cardio_10", "ex_cardio_20", "ex_core_8" -> TrackingType.REPS_ONLY
            "ex_chest_4", "ex_chest_9", "ex_chest_10", "ex_back_2", "ex_back_8", "ex_back_9",
            "ex_core_2", "ex_core_3", "ex_core_4", "ex_core_6", "ex_core_7", "ex_core_10",
            "ex_legs_6", "ex_legs_7", "ex_legs_8", "ex_legs_10", "ex_shoulders_10",
            "ex_triceps_5", "ex_triceps_6", "ex_triceps_8" -> TrackingType.BODYWEIGHT_REPS
            else -> TrackingType.WEIGHT_REPS
        }

        fun deriveModality(id: String): ExerciseModality = when (id) {
            "ex_core_1", "ex_core_9" -> ExerciseModality.ISOMETRIC
            "ex_cardio_1", "ex_cardio_2", "ex_cardio_3", "ex_cardio_4", "ex_cardio_6", "ex_cardio_7",
            "ex_cardio_9", "ex_cardio_10", "ex_cardio_11", "ex_cardio_12", "ex_cardio_13", "ex_cardio_14" -> ExerciseModality.CARDIO
            "ex_cardio_5", "ex_cardio_8", "ex_cardio_15", "ex_cardio_16", "ex_cardio_17", "ex_cardio_18",
            "ex_cardio_19", "ex_cardio_20", "ex_core_8" -> ExerciseModality.CONDITIONING
            "ex_chest_4", "ex_chest_9", "ex_chest_10", "ex_back_2", "ex_back_8", "ex_back_9",
            "ex_core_2", "ex_core_3", "ex_core_4", "ex_core_6", "ex_core_7", "ex_core_10",
            "ex_legs_6", "ex_legs_7", "ex_legs_8", "ex_legs_10", "ex_shoulders_10",
            "ex_triceps_5", "ex_triceps_6", "ex_triceps_8" -> ExerciseModality.CALISTHENICS
            else -> ExerciseModality.STRENGTH
        }

        fun deriveAllowedTrackingTypes(id: String, default: TrackingType): List<TrackingType> = when (default) {
            TrackingType.BODYWEIGHT_REPS -> listOf(TrackingType.BODYWEIGHT_REPS, TrackingType.REPS_ONLY)
            TrackingType.REPS_ONLY -> if (id in setOf("ex_cardio_4", "ex_cardio_5", "ex_cardio_9", "ex_cardio_10", "ex_cardio_20", "ex_core_8")) {
                listOf(TrackingType.REPS_ONLY, TrackingType.DURATION)
            } else {
                listOf(TrackingType.REPS_ONLY)
            }
            TrackingType.DISTANCE_DURATION -> listOf(TrackingType.DISTANCE_DURATION, TrackingType.DURATION)
            else -> listOf(default)
        }
    }
}

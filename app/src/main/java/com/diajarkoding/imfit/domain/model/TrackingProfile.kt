package com.diajarkoding.imfit.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class TrackingProfile(val wireValue: String) {
    @SerialName("duration_only") DURATION_ONLY("duration_only"),
    @SerialName("distance_activity") DISTANCE_ACTIVITY("distance_activity"),
    @SerialName("equipment_metrics") EQUIPMENT_METRICS("equipment_metrics");

    companion object {
        fun fromWire(value: String): TrackingProfile = entries.firstOrNull { it.wireValue == value }
            ?: throw IllegalArgumentException("Unknown tracking profile: $value")
    }
}

@Serializable
enum class CardioMetric(val wireValue: String) {
    @SerialName("calories") CALORIES("calories"),
    @SerialName("distance") DISTANCE("distance"),
    @SerialName("speed") SPEED("speed"),
    @SerialName("laps") LAPS("laps"),
    @SerialName("incline") INCLINE("incline");

    companion object {
        fun fromWire(value: String): CardioMetric = entries.firstOrNull { it.wireValue == value }
            ?: throw IllegalArgumentException("Unknown cardio metric: $value")
    }
}

internal fun validTrackingMetadata(
    modality: ExerciseModality,
    type: TrackingType,
    profile: TrackingProfile?,
    metrics: List<CardioMetric>
): Boolean {
    var seen = 0
    for (metric in metrics) {
        val bit = 1 shl metric.ordinal
        if (seen and bit != 0) return false
        seen = seen or bit
    }
    return when (modality) {
        ExerciseModality.STRENGTH -> type == TrackingType.WEIGHT_REPS && profile == null && metrics.isEmpty()
        ExerciseModality.ISOMETRIC -> type == TrackingType.DURATION && profile == null && metrics.isEmpty()
        ExerciseModality.CONDITIONING -> (type == TrackingType.WEIGHT_REPS || type == TrackingType.DURATION) && profile == null && metrics.isEmpty()
        ExerciseModality.CALISTHENICS -> false // Historical category, never a v3 snapshot.
        ExerciseModality.CARDIO -> type == TrackingType.DURATION && when (profile) {
            TrackingProfile.DURATION_ONLY -> metrics.isEmpty()
            TrackingProfile.DISTANCE_ACTIVITY -> metrics.all { it == CardioMetric.DISTANCE || it == CardioMetric.SPEED || it == CardioMetric.LAPS }
            TrackingProfile.EQUIPMENT_METRICS -> true
            null -> false
        }
    }
}

internal fun Double.isTwoDecimalIn(min: Double, max: Double): Boolean =
    isFinite() && this >= min && this <= max && this == kotlin.math.round(this * 100.0) / 100.0

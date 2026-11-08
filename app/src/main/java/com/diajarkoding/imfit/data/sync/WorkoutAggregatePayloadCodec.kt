package com.diajarkoding.imfit.data.sync

import com.diajarkoding.imfit.data.local.entity.ExerciseLogEntity
import com.diajarkoding.imfit.data.local.entity.WorkoutLogEntity
import com.diajarkoding.imfit.data.local.entity.WorkoutSetEntity
import com.diajarkoding.imfit.domain.model.ExerciseInputDefaults
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

internal data class WorkoutAggregatePayload(
    val workout: JsonObject,
    val exercises: JsonArray,
    val sets: JsonArray
)

/** The legacy branch is an immutable receipt contract, including its numeric JSON tokens. */
internal object WorkoutAggregatePayloadCodec {
    fun encode(
        log: WorkoutLogEntity,
        exercises: List<ExerciseLogEntity>,
        sets: List<WorkoutSetEntity>
    ): WorkoutAggregatePayload {
        require(log.measurementVersion == null || log.measurementVersion == 3) { "Unsupported workout measurement version" }
        require(exercises.all { it.measurementVersion == log.measurementVersion }) { "Mixed exercise measurement versions" }
        require(sets.all { it.measurementVersion == log.measurementVersion }) { "Mixed set measurement versions" }
        // Rest outcome / session durations exist only on v3 results; a legacy payload must never carry them.
        require(log.measurementVersion == 3 || (log.pausedElapsedMs == null && log.activeElapsedMs == null &&
            sets.all { it.restElapsedMs == null && it.restEndReason == null })) { "Version 3 rest/session fields in legacy aggregate" }
        val isV3 = log.measurementVersion == 3
        val start = OffsetDateTime.ofInstant(Instant.ofEpochMilli(log.startTime), ZoneOffset.UTC)
        val workout = buildJsonObject {
            put("id", log.id)
            put("user_id", log.userId)
            log.templateId?.let { put("template_id", it) }
            put("template_name", log.templateName)
            put("date", start.toLocalDate().toString())
            put("start_time", start.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
            put("end_time", timestamp(log.endTime))
            put("total_volume", log.totalVolume.toDouble())
            put("total_sets", log.totalSets)
            put("total_reps", log.totalReps)
            log.deletedAt?.let { put("deleted_at", timestamp(it)) }
            put("total_duration_seconds", log.totalDurationSeconds)
            if (isV3) {
                put("measurement_version", 3)
                put("recorded_load_volume", log.recordedLoadVolume?.let(::JsonPrimitive) ?: JsonNull)
                put("total_distance_meters", log.totalDistanceMeters?.let(::JsonPrimitive) ?: JsonNull)
                // Both or neither (server contract T37). Omitted entirely when not recorded, so older payloads are unchanged.
                require((log.pausedElapsedMs == null) == (log.activeElapsedMs == null)) { "Incomplete session durations" }
                if (log.pausedElapsedMs != null && log.activeElapsedMs != null) {
                    put("paused_elapsed_ms", log.pausedElapsedMs)
                    put("active_elapsed_ms", log.activeElapsedMs)
                }
            } else {
                put("total_distance_meters", legacyDistance(requireNotNull(log.totalDistanceMeters)))
            }
            put("additional_volume", log.additionalVolume.toDouble())
        }
        return WorkoutAggregatePayload(
            workout = workout,
            exercises = buildJsonArray { exercises.forEach { add(exercise(it, isV3)) } },
            sets = buildJsonArray { sets.forEach { add(set(it, isV3)) } }
        )
    }

    private fun exercise(log: ExerciseLogEntity, isV3: Boolean): JsonObject = buildJsonObject {
        put("id", log.id)
        put("workout_log_id", log.workoutLogId)
        put("exercise_id", log.exerciseId)
        put("exercise_name", log.exerciseName)
        put("muscle_category", log.muscleCategory)
        put("order_index", log.orderIndex)
        put("total_volume", log.totalVolume.toDouble())
        put("total_sets", log.totalSets)
        put("total_reps", log.totalReps)
        if (isV3) {
            val defaults = ExerciseInputDefaults.fromStoredString(requireNotNull(log.inputDefaults))
            require(defaults.schemaVersion == 3 && defaults.trackingType == log.trackingType &&
                defaults.modality?.name == log.modality) { "Exercise metadata differs from its frozen snapshot" }
            put("input_defaults", defaults.toSnapshotJson())
            put("tracking_type", log.trackingType)
            put("modality", log.modality)
            put("measurement_version", 3)
            put("recorded_load_volume", log.recordedLoadVolume?.let(::JsonPrimitive) ?: JsonNull)
            put("total_distance_meters", log.totalDistanceMeters?.let(::JsonPrimitive) ?: JsonNull)
        } else {
            // Do not reinterpret or re-encode old pending keys as versioned objects.
            log.inputDefaults?.let { put("input_defaults", it) }
            if (log.metricQuality != "LEGACY_UNVERIFIED") {
                put("tracking_type", log.trackingType)
                put("modality", log.modality)
            }
            put("total_distance_meters", legacyDistance(requireNotNull(log.totalDistanceMeters)))
        }
        put("total_duration_seconds", log.totalDurationSeconds)
        put("additional_volume", log.additionalVolume.toDouble())
    }

    private fun set(set: WorkoutSetEntity, isV3: Boolean): JsonObject = buildJsonObject {
        put("id", set.id)
        put("exercise_log_id", set.exerciseLogId)
        put("workout_log_id", set.workoutLogId)
        put("exercise_id", set.exerciseId)
        put("set_number", set.setNumber)
        put("is_completed", set.isCompleted)
        if (isV3) {
            require(!set.targetRowId.isNullOrBlank()) { "Version 3 set has no target row ID" }
            require(set.additionalWeight == null && set.assistanceWeight == null) { "Legacy loads in version 3 set" }
            put("measurement_version", 3)
            put("target_row_id", set.targetRowId)
            put("weight", set.weight?.let(::JsonPrimitive) ?: JsonNull)
            put("reps", set.reps?.let(::JsonPrimitive) ?: JsonNull)
            put("duration_seconds", set.durationSeconds?.let(::JsonPrimitive) ?: JsonNull)
            put("distance_meters", set.distanceMeters?.let(::JsonPrimitive) ?: JsonNull)
            put("calories_kcal", set.caloriesKcal?.let(::JsonPrimitive) ?: JsonNull)
            put("speed_kph", set.speedKph?.let(::JsonPrimitive) ?: JsonNull)
            put("lap_reps", set.lapReps?.let(::JsonPrimitive) ?: JsonNull)
            put("incline_percent", set.inclinePercent?.let(::JsonPrimitive) ?: JsonNull)
            require((set.restElapsedMs == null) == (set.restEndReason == null)) { "Incomplete rest outcome" }
            if (set.restElapsedMs != null && set.restEndReason != null) {
                require(set.isCompleted && set.restElapsedMs >= 0) { "Rest outcome requires a completed set" }
                put("rest_elapsed_ms", set.restElapsedMs)
                put("rest_end_reason", set.restEndReason)
            }
        } else {
            require(set.targetRowId == null && set.caloriesKcal == null && set.speedKph == null &&
                set.lapReps == null && set.inclinePercent == null) { "Version 3 measurements in legacy set" }
            // The old entity stored Float. Reproduce its widening, not a new decimal token.
            put("weight", requireNotNull(set.weight).toFloat().toDouble())
            put("reps", requireNotNull(set.reps))
            set.durationSeconds?.let { put("duration_seconds", it) }
            set.distanceMeters?.let { put("distance_meters", legacyDistance(it)) }
            set.additionalWeight?.let { put("additional_weight", it.toDouble()) }
            set.assistanceWeight?.let { put("assistance_weight", it.toDouble()) }
        }
    }

    private fun legacyDistance(value: Double): Int {
        require(value.isFinite() && value >= Int.MIN_VALUE && value <= Int.MAX_VALUE && value % 1.0 == 0.0) {
            "Legacy distance must retain its integer representation"
        }
        return value.toInt()
    }

    private fun timestamp(value: Long): String = OffsetDateTime.ofInstant(Instant.ofEpochMilli(value), ZoneOffset.UTC)
        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
}

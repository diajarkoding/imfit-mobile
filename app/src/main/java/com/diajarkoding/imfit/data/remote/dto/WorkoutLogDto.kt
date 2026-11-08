package com.diajarkoding.imfit.data.remote.dto

import com.diajarkoding.imfit.domain.model.ExerciseLog
import com.diajarkoding.imfit.domain.model.ExerciseInputDefaults
import com.diajarkoding.imfit.domain.model.ExerciseModality
import com.diajarkoding.imfit.domain.model.RestEndReason
import com.diajarkoding.imfit.domain.model.RestOutcome
import com.diajarkoding.imfit.domain.model.TrackingType
import com.diajarkoding.imfit.domain.model.WorkoutLog
import com.diajarkoding.imfit.domain.model.WorkoutSet
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import java.util.UUID

@Serializable
data class WorkoutLogDto(
    val id: String,
    @SerialName("user_id")
    val userId: String,
    @SerialName("template_id")
    val templateId: String? = null,
    @SerialName("template_name")
    val templateName: String,
    val date: String,
    @SerialName("start_time")
    val startTime: String,
    @SerialName("end_time")
    val endTime: String,
    @SerialName("total_volume")
    val totalVolume: Double = 0.0,
    @SerialName("total_sets")
    val totalSets: Int = 0,
    @SerialName("total_reps")
    val totalReps: Int = 0,
    val notes: String? = null,
    val rating: Int? = null,
    @SerialName("created_at")
    val createdAt: String? = null,
    @SerialName("updated_at")
    val updatedAt: String? = null,
    @SerialName("deleted_at")
    val deletedAt: String? = null,
    @SerialName("exercise_logs")
    val exerciseLogs: List<ExerciseLogDto>? = null,
    @SerialName("total_duration_seconds")
    val totalDurationSeconds: Int = 0,
    @SerialName("total_distance_meters")
    val totalDistanceMeters: Double? = 0.0,
    @SerialName("additional_volume")
    val additionalVolume: Double = 0.0,
    @SerialName("data_quality")
    val dataQuality: String = "LEGACY_UNVERIFIED",
    @SerialName("measurement_version")
    val measurementVersion: Int? = null,
    @SerialName("recorded_load_volume")
    val recordedLoadVolume: Double? = null,
    @SerialName("paused_elapsed_ms")
    val pausedElapsedMs: Long? = null,
    @SerialName("active_elapsed_ms")
    val activeElapsedMs: Long? = null
)

@Serializable
data class ExerciseLogDto(
    val id: String? = null,
    @SerialName("workout_log_id")
    val workoutLogId: String? = null,
    @SerialName("exercise_id")
    val exerciseId: String,
    @SerialName("exercise_name")
    val exerciseName: String,
    @SerialName("muscle_category")
    val muscleCategory: String,
    @SerialName("order_index")
    val orderIndex: Int = 0,
    @SerialName("total_volume")
    val totalVolume: Double = 0.0,
    @SerialName("total_sets")
    val totalSets: Int = 0,
    @SerialName("total_reps")
    val totalReps: Int = 0,
    @SerialName("workout_sets")
    val workoutSets: List<WorkoutSetDto>? = null,
    val exercises: ExerciseDto? = null,
    @SerialName("input_defaults")
    val inputDefaults: JsonElement? = null,
    @SerialName("tracking_type")
    val trackingType: String? = null,
    @SerialName("modality")
    val modality: String? = null,
    @SerialName("total_duration_seconds")
    val totalDurationSeconds: Int = 0,
    @SerialName("total_distance_meters")
    val totalDistanceMeters: Double? = 0.0,
    @SerialName("additional_volume")
    val additionalVolume: Double = 0.0,
    @SerialName("metric_quality")
    val metricQuality: String = "LEGACY_UNVERIFIED",
    @SerialName("measurement_version")
    val measurementVersion: Int? = null,
    @SerialName("recorded_load_volume")
    val recordedLoadVolume: Double? = null
)

@Serializable
data class WorkoutSetDto(
    val id: String? = null,
    @SerialName("exercise_log_id")
    val exerciseLogId: String? = null,
    @SerialName("workout_log_id")
    val workoutLogId: String? = null,
    @SerialName("exercise_id")
    val exerciseId: String? = null,
    @SerialName("set_number")
    val setNumber: Int,
    val weight: Double? = null,
    val reps: Int? = null,
    @SerialName("is_completed")
    val isCompleted: Boolean = false,
    @SerialName("is_warmup")
    val isWarmup: Boolean = false,
    val notes: String? = null,
    @SerialName("duration_seconds")
    val durationSeconds: Int? = null,
    @SerialName("distance_meters")
    val distanceMeters: Double? = null,
    @SerialName("additional_weight")
    val additionalWeight: Double? = null,
    @SerialName("assistance_weight")
    val assistanceWeight: Double? = null,
    @SerialName("target_row_id")
    val targetRowId: String? = null,
    @SerialName("calories_kcal")
    val caloriesKcal: Double? = null,
    @SerialName("speed_kph")
    val speedKph: Double? = null,
    @SerialName("lap_reps")
    val lapReps: Int? = null,
    @SerialName("incline_percent")
    val inclinePercent: Double? = null,
    @SerialName("measurement_version")
    val measurementVersion: Int? = null,
    @SerialName("rest_elapsed_ms")
    val restElapsedMs: Long? = null,
    @SerialName("rest_end_reason")
    val restEndReason: String? = null
)

fun WorkoutLogDto.toDomain(): WorkoutLog {
    require(measurementVersion == null || measurementVersion == 3) { "Unsupported workout measurement version" }
    val sortedExercises = exerciseLogs
        ?.sortedWith(compareBy<ExerciseLogDto> { it.orderIndex }.thenBy { it.id.orEmpty() }.thenBy { it.exerciseId })
        ?.map {
            require(it.measurementVersion == measurementVersion) { "Mixed workout measurement versions" }
            it.toDomain(id)
        }
        ?: emptyList()
    return WorkoutLog(
        id = id,
        userId = userId,
        templateName = templateName,
        date = parseTimestamp(startTime),
        startTime = parseTimestamp(startTime),
        endTime = parseTimestamp(endTime),
        totalVolume = totalVolume.toFloat(),
        exerciseLogs = sortedExercises,
        totalSets = if (totalSets > 0) totalSets else sortedExercises.sumOf { it.completedSets },
        totalReps = if (totalReps > 0) totalReps else sortedExercises.sumOf { it.totalReps },
        totalDurationSeconds = totalDurationSeconds,
        totalDistanceMeters = totalDistanceMeters,
        additionalVolume = additionalVolume.toFloat(),
        dataQuality = dataQuality,
        measurementVersion = measurementVersion,
        recordedLoadVolume = recordedLoadVolume,
        pausedElapsedMs = pausedElapsedMs,
        activeElapsedMs = activeElapsedMs
    )
}

fun ExerciseLogDto.toDomain(parentWorkoutId: String? = workoutLogId): ExerciseLog {
    require(measurementVersion == null || measurementVersion == 3) { "Unsupported exercise measurement version" }
    val defaults = inputDefaults?.let(ExerciseInputDefaults::fromSnapshotJson)
    val isV3 = measurementVersion == 3
    require((defaults?.schemaVersion == 3) == isV3) { "Exercise snapshot measurement version mismatch" }
    val parsedTracking = if (isV3) TrackingType.valueOf(requireNotNull(defaults?.trackingType))
        else trackingType?.let(TrackingType::valueOf) ?: TrackingType.WEIGHT_REPS
    val parsedModality = if (isV3) requireNotNull(defaults?.modality)
        else modality?.let(ExerciseModality::valueOf) ?: ExerciseModality.STRENGTH
    if (isV3) {
        require(trackingType == parsedTracking.name && modality == parsedModality.name) {
            "Exercise metadata differs from its frozen snapshot"
        }
    }
    val exercise = com.diajarkoding.imfit.domain.model.Exercise(
        id = exerciseId,
        name = exerciseName,
        muscleCategory = runCatching {
            com.diajarkoding.imfit.domain.model.MuscleCategory.valueOf(muscleCategory.uppercase())
        }.getOrDefault(com.diajarkoding.imfit.domain.model.MuscleCategory.CHEST),
        description = "",
        defaultTrackingType = parsedTracking,
        modality = parsedModality,
        allowedTrackingTypes = listOf(parsedTracking),
        trackingProfile = defaults?.trackingProfile,
        supportedMetrics = defaults?.supportedMetrics.orEmpty()
    )
    if (isV3) exercise.requireV3Metadata()
    val scope = "${parentWorkoutId.orEmpty()}:${id ?: exerciseId}"
    val sortedSets = workoutSets
        ?.sortedWith(compareBy<WorkoutSetDto> { it.setNumber }.thenBy { it.id.orEmpty() })
        ?.map {
            require(it.measurementVersion == measurementVersion) { "Mixed exercise measurement versions" }
            it.toDomain(scope)
        }
        ?: emptyList()

    // Only frozen snapshot metadata determines historical meaning.
    return ExerciseLog(
        exercise = exercise,
        sets = sortedSets,
        inputDefaults = defaults,
        trackingType = parsedTracking,
        modality = parsedModality,
        metricQuality = metricQuality,
        measurementVersion = measurementVersion
    )
}

fun WorkoutSetDto.toDomain(idScope: String = "${workoutLogId.orEmpty()}:${exerciseLogId.orEmpty()}:${exerciseId.orEmpty()}"): WorkoutSet {
    require(measurementVersion == null || measurementVersion == 3) { "Unsupported set measurement version" }
    val stableId = if (measurementVersion == 3) requireNotNull(id?.takeIf(String::isNotBlank)) {
        "Version 3 set has no stable ID"
    } else id ?: UUID.nameUUIDFromBytes("remote-set:$idScope:$setNumber".toByteArray(Charsets.UTF_8)).toString()
    if (measurementVersion == 3) {
        require(UUID.fromString(stableId).toString().equals(stableId, ignoreCase = true)) { "Invalid v3 result UUID" }
        require(!targetRowId.isNullOrBlank()) { "Version 3 set has no target row ID" }
    }
    return WorkoutSet(
        id = stableId,
        setNumber = setNumber,
        weightKg = if (measurementVersion == 3) weight else (weight ?: 0.0).toFloat().toDouble(),
        reps = if (measurementVersion == 3) reps else reps ?: 0,
        isCompleted = isCompleted,
        durationSeconds = durationSeconds,
        distanceMeters = distanceMeters,
        additionalWeight = additionalWeight?.toFloat(),
        assistanceWeight = assistanceWeight?.toFloat(),
        targetRowId = targetRowId,
        measurementVersion = measurementVersion,
        caloriesKcal = caloriesKcal,
        speedKph = speedKph,
        lapReps = lapReps,
        inclinePercent = inclinePercent,
        restOutcome = restEndReason?.let { reason ->
            RestOutcome(requireNotNull(restElapsedMs) { "Rest reason without elapsed time" }, RestEndReason.valueOf(reason))
        }
    )
}

fun WorkoutSet.toDto(exerciseLogId: String? = null): WorkoutSetDto {
    return WorkoutSetDto(
        id = id,
        exerciseLogId = exerciseLogId,
        setNumber = setNumber,
        weight = if (measurementVersion == 3) weightKg else weightKg?.toFloat()?.toDouble(),
        reps = reps,
        isCompleted = isCompleted,
        durationSeconds = durationSeconds,
        distanceMeters = distanceMeters,
        additionalWeight = additionalWeight?.toDouble(),
        assistanceWeight = assistanceWeight?.toDouble(),
        targetRowId = targetRowId,
        measurementVersion = measurementVersion,
        caloriesKcal = caloriesKcal,
        speedKph = speedKph,
        lapReps = lapReps,
        inclinePercent = inclinePercent,
        restElapsedMs = restOutcome?.elapsedMs,
        restEndReason = restOutcome?.endReason?.name
    )
}

private fun parseTimestamp(timestamp: String?): Long {
    if (timestamp == null) return System.currentTimeMillis()
    return try {
        java.time.OffsetDateTime.parse(timestamp).toInstant().toEpochMilli()
    } catch (e: Exception) {
        try {
            java.time.LocalDate.parse(timestamp).atStartOfDay()
                .toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
        } catch (e2: Exception) {
            System.currentTimeMillis()
        }
    }
}

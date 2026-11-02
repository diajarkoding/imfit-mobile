package com.diajarkoding.imfit.data.remote.dto

import com.diajarkoding.imfit.domain.model.ExerciseLog
import com.diajarkoding.imfit.domain.model.ExerciseModality
import com.diajarkoding.imfit.domain.model.TrackingType
import com.diajarkoding.imfit.domain.model.WorkoutLog
import com.diajarkoding.imfit.domain.model.WorkoutSet
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

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
    val totalDistanceMeters: Int = 0,
    @SerialName("additional_volume")
    val additionalVolume: Double = 0.0,
    @SerialName("data_quality")
    val dataQuality: String = "LEGACY_UNVERIFIED"
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
    val totalDistanceMeters: Int = 0,
    @SerialName("additional_volume")
    val additionalVolume: Double = 0.0,
    @SerialName("metric_quality")
    val metricQuality: String = "LEGACY_UNVERIFIED"
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
    val weight: Double = 0.0,
    val reps: Int = 0,
    @SerialName("is_completed")
    val isCompleted: Boolean = false,
    @SerialName("is_warmup")
    val isWarmup: Boolean = false,
    val notes: String? = null,
    @SerialName("duration_seconds")
    val durationSeconds: Int? = null,
    @SerialName("distance_meters")
    val distanceMeters: Int? = null,
    @SerialName("additional_weight")
    val additionalWeight: Double? = null,
    @SerialName("assistance_weight")
    val assistanceWeight: Double? = null
)

fun WorkoutLogDto.toDomain(): WorkoutLog {
    val sortedExercises = exerciseLogs
        ?.sortedWith(compareBy<ExerciseLogDto> { it.orderIndex }.thenBy { it.id.orEmpty() }.thenBy { it.exerciseId })
        ?.map { it.toDomain() }
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
        dataQuality = dataQuality
    )
}

fun ExerciseLogDto.toDomain(): ExerciseLog {
    val parsedTracking = trackingType?.let(TrackingType::valueOf) ?: TrackingType.WEIGHT_REPS
    val parsedModality = modality?.let(ExerciseModality::valueOf) ?: ExerciseModality.STRENGTH
    val exercise = com.diajarkoding.imfit.domain.model.Exercise(
        id = exerciseId,
        name = exerciseName,
        muscleCategory = runCatching {
            com.diajarkoding.imfit.domain.model.MuscleCategory.valueOf(muscleCategory.uppercase())
        }.getOrDefault(com.diajarkoding.imfit.domain.model.MuscleCategory.CHEST),
        description = "",
        defaultTrackingType = parsedTracking,
        modality = parsedModality,
        allowedTrackingTypes = listOf(parsedTracking)
    )
    val sortedSets = workoutSets
        ?.sortedWith(compareBy<WorkoutSetDto> { it.setNumber }.thenBy { it.id.orEmpty() })
        ?.map { it.toDomain() }
        ?: emptyList()

    // History without typed metadata is the historical weight/reps contract.
    // Never reinterpret old measurements using a mutable exercise catalog.

    return ExerciseLog(
        exercise = exercise,
        sets = sortedSets,
        trackingType = parsedTracking,
        modality = parsedModality,
        metricQuality = metricQuality
    )
}

fun WorkoutSetDto.toDomain(): WorkoutSet {
    return WorkoutSet(
        setNumber = setNumber,
        weight = weight.toFloat(),
        reps = reps,
        isCompleted = isCompleted,
        durationSeconds = durationSeconds,
        distanceMeters = distanceMeters,
        additionalWeight = additionalWeight?.toFloat(),
        assistanceWeight = assistanceWeight?.toFloat()
    )
}

fun WorkoutSet.toDto(exerciseLogId: String? = null): WorkoutSetDto {
    return WorkoutSetDto(
        exerciseLogId = exerciseLogId,
        setNumber = setNumber,
        weight = weight.toDouble(),
        reps = reps,
        isCompleted = isCompleted,
        durationSeconds = durationSeconds,
        distanceMeters = distanceMeters,
        additionalWeight = additionalWeight?.toDouble(),
        assistanceWeight = assistanceWeight?.toDouble()
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

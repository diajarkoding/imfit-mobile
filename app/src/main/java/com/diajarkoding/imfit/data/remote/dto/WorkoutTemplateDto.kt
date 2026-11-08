package com.diajarkoding.imfit.data.remote.dto

import com.diajarkoding.imfit.domain.model.TemplateExercise
import com.diajarkoding.imfit.domain.model.TrackingType
import com.diajarkoding.imfit.domain.model.WorkoutTemplate
import com.diajarkoding.imfit.domain.model.SetTarget
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class WorkoutTemplateDto(
    val id: String,
    @SerialName("user_id")
    val userId: String,
    val name: String,
    val description: String? = null,
    @SerialName("estimated_duration")
    val estimatedDuration: Int? = null,
    @SerialName("is_deleted")
    val isDeleted: Boolean = false,
    @SerialName("created_at")
    val createdAt: String? = null,
    @SerialName("updated_at")
    val updatedAt: String? = null,
    @SerialName("template_exercises")
    val templateExercises: List<TemplateExerciseDto>? = null,
    @SerialName("measurement_version")
    val measurementVersion: Int? = null
)

@Serializable
data class TemplateExerciseDto(
    val id: String? = null,
    @SerialName("template_id")
    val templateId: String? = null,
    @SerialName("exercise_id")
    val exerciseId: String,
    @SerialName("order_index")
    val orderIndex: Int = 0,
    val sets: Int = 3,
    val reps: Int? = 10,
    @SerialName("rest_seconds")
    val restSeconds: Int = 60,
    val notes: String? = null,
    val exercises: ExerciseDto? = null,
    @SerialName("tracking_type")
    val trackingType: String? = null,
    @SerialName("target_duration_seconds")
    val targetDurationSeconds: Int? = null,
    @SerialName("target_distance_meters")
    val targetDistanceMeters: Int? = null,
    @SerialName("set_targets")
    val setTargets: List<SetTarget>? = null,
    @SerialName("measurement_version")
    val measurementVersion: Int? = null
)

fun WorkoutTemplateDto.toDomain(): WorkoutTemplate {
    require(measurementVersion == null || measurementVersion == 3) { "Unsupported routine measurement version" }
    require(templateExercises.orEmpty().all { it.measurementVersion == measurementVersion }) { "Mixed routine measurement versions" }
    val sortedExercises = templateExercises
        ?.sortedWith(compareBy<TemplateExerciseDto> { it.orderIndex }.thenBy { it.exerciseId })
        ?.mapNotNull { it.toDomain() }
        ?: emptyList()
    return WorkoutTemplate(
        id = id,
        userId = userId,
        name = name,
        exercises = sortedExercises,
        createdAt = parseRemoteTimestamp(createdAt),
        measurementVersion = measurementVersion
    )
}

fun TemplateExerciseDto.toDomain(): TemplateExercise? {
    require(measurementVersion == null || measurementVersion == 3) { "Unsupported target measurement version" }
    require((setTargets != null) == (measurementVersion == 3)) { "Routine target measurement version mismatch" }
    val exercise = exercises?.toDomain()
        ?: if (measurementVersion == 3) throw IllegalArgumentException("Version 3 routine exercise metadata is missing") else return null
    val parsedTrackingType = trackingType?.let(TrackingType::valueOf)
        ?: exercise.defaultTrackingType
    return TemplateExercise(
        exercise = exercise,
        sets = sets,
        reps = reps,
        restSeconds = restSeconds,
        trackingType = parsedTrackingType,
        targetDurationSeconds = targetDurationSeconds,
        targetDistanceMeters = targetDistanceMeters,
        setTargets = setTargets,
        measurementVersion = measurementVersion
    ).also {
        if (measurementVersion == 3) {
            exercise.requireV3Metadata()
            require(it.target.isValidFor(parsedTrackingType)) { "Invalid version 3 routine targets" }
        }
    }
}

fun TemplateExercise.toDto(templateId: String, orderIndex: Int): TemplateExerciseDto {
    return TemplateExerciseDto(
        templateId = templateId,
        exerciseId = exercise.id,
        orderIndex = orderIndex,
        sets = sets,
        reps = reps,
        restSeconds = restSeconds,
        trackingType = trackingType.name,
        targetDurationSeconds = targetDurationSeconds,
        targetDistanceMeters = targetDistanceMeters,
        setTargets = setTargets,
        measurementVersion = measurementVersion
    )
}

internal fun parseRemoteTimestamp(timestamp: String?, fallbackMs: Long = 0L): Long {
    if (timestamp.isNullOrBlank()) return fallbackMs
    return try {
        java.time.OffsetDateTime.parse(timestamp).toInstant().toEpochMilli()
    } catch (e: Exception) {
        fallbackMs
    }
}

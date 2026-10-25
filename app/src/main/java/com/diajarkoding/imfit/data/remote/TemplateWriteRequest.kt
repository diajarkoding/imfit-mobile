package com.diajarkoding.imfit.data.remote

import com.diajarkoding.imfit.domain.model.TemplateExercise
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TemplateWriteRequest(
    @SerialName("p_request_id") val requestId: String,
    @SerialName("p_template_id") val templateId: String,
    @SerialName("p_expected_revision") val expectedRevision: Long?,
    @SerialName("p_name") val name: String,
    @SerialName("p_exercises") val exercises: List<TemplateWriteExercise>,
    @SerialName("p_removed_exercise_ids") val removedExerciseIds: List<String>,
    @SerialName("p_is_deleted") val isDeleted: Boolean = false,
    @SerialName("p_owner_id") val ownerId: String,
) {
    fun sameIntent(name: String, exercises: List<TemplateWriteExercise>, deleted: Boolean) =
        this.name == name && this.exercises == exercises && isDeleted == deleted
}

@Serializable
data class TemplateWriteExercise(
    @SerialName("exercise_id") val exerciseId: String,
    @SerialName("order_index") val orderIndex: Int,
    val sets: Int,
    val reps: Int,
    @SerialName("rest_seconds") val restSeconds: Int,
)

internal fun templateWriteExercises(exercises: List<TemplateExercise>): List<TemplateWriteExercise> {
    require(exercises.map { it.id }.distinct().size == exercises.size) { "Duplicate exercises" }
    return exercises.mapIndexed { index, exercise ->
        require(exercise.sets in 1..20 && exercise.reps in 1..100 && exercise.restSeconds in 0..600)
        TemplateWriteExercise(exercise.id, index, exercise.sets, exercise.reps, exercise.restSeconds)
    }
}

@Serializable
data class TemplateWriteReceipt(
    @SerialName("request_id") val requestId: String,
    @SerialName("template_id") val templateId: String,
    val revision: Long,
    @SerialName("exercise_count") val exerciseCount: Int,
    @SerialName("server_updated_at") val serverUpdatedAt: String,
)

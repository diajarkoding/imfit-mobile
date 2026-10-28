package com.diajarkoding.imfit.data.remote.online

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer

object DecimalStringSerializer : JsonTransformingSerializer<String>(String.serializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement {
        if (element !is JsonPrimitive || !element.isString ||
            !Regex("-?[0-9]+(\\.[0-9]+)?").matches(element.content)) {
            throw SerializationException("Expected exact finite decimal string")
        }
        return element
    }
}

// Required fields deliberately have no defaults (including required nullable fields).
// Timestamps remain wire strings: do not truncate PostgreSQL microseconds to millis.
@Serializable
data class RemotePage<T>(val data: List<T>, @SerialName("page_info") val pageInfo: PageInfo)

@Serializable
data class PageInfo(
    @SerialName("next_cursor") val nextCursor: String?,
    @SerialName("has_more") val hasMore: Boolean,
    val limit: Int,
)

@Serializable
data class WorkoutSummary(
    val id: String,
    @SerialName("template_id") val templateId: String?,
    @SerialName("template_name") val templateName: String,
    @SerialName("start_time") val startTime: String,
    @SerialName("end_time") val endTime: String,
    @SerialName("duration_seconds") val durationSeconds: Long,
    @Serializable(with = DecimalStringSerializer::class) @SerialName("total_volume") val totalVolume: String,
    @SerialName("total_sets") val totalSets: Long,
    @SerialName("total_reps") val totalReps: Long,
)

@Serializable
data class ExerciseSummary(
    val id: String,
    @SerialName("muscle_category_id") val muscleCategoryId: Int,
    val name: String,
    @SerialName("image_url") val imageUrl: String?,
    val difficulty: String?,
    val equipment: String?,
)

@Serializable
data class TemplateSummary(
    val id: String, val name: String, val revision: Long,
    @SerialName("exercise_count") val exerciseCount: Int,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class TemplateDetail(
    val id: String, val name: String, val revision: Long,
    @SerialName("updated_at") val updatedAt: String,
    val exercises: List<TemplateChild>,
)

@Serializable
data class TemplateChild(
    val id: String,
    @SerialName("exercise_id") val exerciseId: String,
    @SerialName("order_index") val orderIndex: Int,
    val sets: Int, val reps: Int,
    @SerialName("rest_seconds") val restSeconds: Int,
)

@Serializable
data class WorkoutDetail(
    val id: String,
    @SerialName("template_id") val templateId: String?,
    @SerialName("template_name") val templateName: String,
    @SerialName("start_time") val startTime: String,
    @SerialName("end_time") val endTime: String,
    @SerialName("duration_seconds") val durationSeconds: Long,
    @Serializable(with = DecimalStringSerializer::class) @SerialName("total_volume") val totalVolume: String,
    @SerialName("total_sets") val totalSets: Long,
    @SerialName("total_reps") val totalReps: Long,
    @SerialName("exercise_logs") val exerciseLogs: List<ExerciseLog>,
)

@Serializable
data class ExerciseLog(
    val id: String,
    @SerialName("exercise_id") val exerciseId: String,
    @SerialName("exercise_name") val exerciseName: String,
    @SerialName("muscle_category") val muscleCategory: String,
    @SerialName("order_index") val orderIndex: Int,
    @SerialName("input_defaults") val inputDefaults: InputDefaults?,
    @Serializable(with = DecimalStringSerializer::class) @SerialName("total_volume") val totalVolume: String,
    @SerialName("total_sets") val totalSets: Long,
    @SerialName("total_reps") val totalReps: Long,
    val sets: List<WorkoutSet>,
)

@Serializable
data class WorkoutSet(
    val id: String,
    @SerialName("set_number") val setNumber: Int,
    @Serializable(with = DecimalStringSerializer::class) val weight: String, val reps: Int,
    @SerialName("is_completed") val isCompleted: Boolean,
)

@Serializable
data class InputDefaults(val sets: Int, val reps: Int)

@Serializable
data class ProgressSummary(
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String,
    val timezone: String,
    @SerialName("workout_count") val workoutCount: Long,
    @Serializable(with = DecimalStringSerializer::class) @SerialName("total_volume") val totalVolume: String,
    @SerialName("total_sets") val totalSets: Long,
    @SerialName("total_reps") val totalReps: Long,
    @SerialName("duration_seconds") val durationSeconds: Long,
)

@Serializable
data class DateMarkers(
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String,
    val timezone: String, val dates: List<DateMarker>,
)

@Serializable
data class DateMarker(val date: String, @SerialName("workout_count") val workoutCount: Long)

@Serializable
enum class PrefillSource { HISTORY, DEFAULTS }

@Serializable
data class ExercisePrefill(
    val source: PrefillSource,
    @SerialName("source_workout_id") val sourceWorkoutId: String?,
    @SerialName("input_defaults") val inputDefaults: InputDefaults,
    val sets: List<PrefillSet>,
)

@Serializable
data class PrefillSet(
    @SerialName("set_number") val setNumber: Int,
    @Serializable(with = DecimalStringSerializer::class) val weight: String,
    val reps: Int,
)

@Serializable
enum class SessionStatus { ACTIVE, PAUSED, COMPLETED, CANCELLED, EXPIRED }

@Serializable
data class SessionSnapshot(
    val id: String, val version: Long, val status: SessionStatus,
    @SerialName("effective_status") val effectiveStatus: SessionStatus,
    @SerialName("review_required") val reviewRequired: Boolean,
    @SerialName("template_id") val templateId: String?,
    @SerialName("template_name") val templateName: String,
    @SerialName("template_revision") val templateRevision: Long,
    @SerialName("started_at") val startedAt: String,
    @SerialName("paused_at") val pausedAt: String?,
    @SerialName("total_paused_ms") val totalPausedMs: Long,
    @SerialName("last_activity_at") val lastActivityAt: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("current_exercise_id") val currentExerciseId: String?,
    val exercises: List<SessionExercise>,
    @SerialName("completed_workout_id") val completedWorkoutId: String?,
)

@Serializable
data class SessionExercise(
    val id: String,
    @SerialName("exercise_id") val exerciseId: String,
    val name: String,
    @SerialName("order_index") val orderIndex: Int,
    @SerialName("rest_seconds") val restSeconds: Int,
    @SerialName("input_defaults") val inputDefaults: InputDefaults,
    val sets: List<WorkoutSet>,
)

@Serializable
data class SessionLookup(val found: Boolean, val session: SessionSnapshot?)

@Serializable
data class SessionMutationReceipt(
    @SerialName("request_id") val requestId: String,
    val operation: String,
    @SerialName("session_id") val sessionId: String,
    val version: Long, val status: SessionStatus,
    @SerialName("committed_at") val committedAt: String,
    val snapshot: SessionSnapshot,
    // Present only on a completed receipt. Validated by the finish call.
    @SerialName("workout_id") val workoutId: String? = null,
)

@Serializable
enum class CommitTimeSource { COMMIT, OBSERVED_BACKFILL }

@Serializable
data class OperationReceipt(
    @SerialName("request_id") val requestId: String,
    val operation: String,
    @SerialName("resource_type") val resourceType: String,
    @SerialName("resource_id") val resourceId: String,
    @SerialName("committed_at") val committedAt: String,
    @SerialName("committed_at_source") val committedAtSource: CommitTimeSource,
    // A tagged result: decoded with the original operation's serializer, never guessed.
    val result: JsonObject,
)

@Serializable
enum class OperationState { COMMITTED, NOT_FOUND }

@Serializable
data class OperationLookup(val state: OperationState, val receipt: OperationReceipt?)

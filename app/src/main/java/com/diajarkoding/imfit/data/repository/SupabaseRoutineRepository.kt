package com.diajarkoding.imfit.data.repository

import com.diajarkoding.imfit.domain.model.MuscleCategory
import com.diajarkoding.imfit.domain.model.TemplateExercise
import com.diajarkoding.imfit.domain.model.TrackingType
import com.diajarkoding.imfit.domain.model.SetTarget
import com.diajarkoding.imfit.domain.model.WorkoutTemplate
import com.diajarkoding.imfit.domain.repository.ExerciseCatalogRepository
import com.diajarkoding.imfit.domain.repository.ExerciseHydrationResult
import com.diajarkoding.imfit.domain.repository.RoutineError
import com.diajarkoding.imfit.domain.repository.RoutineRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SupabaseRoutineRepository @Inject constructor(
    private val supabaseClient: SupabaseClient,
    private val exerciseCatalogRepository: ExerciseCatalogRepository
) : RoutineRepository {
    private val snapshots = mutableMapOf<String, WorkoutTemplate>()
    private var snapshotOwner: String? = null
    private val mutationGeneration = AtomicLong()

    private fun owner(): String {
        val id = supabaseClient.auth.currentUserOrNull()?.id ?: throw RoutineError.Unauthorized()
        synchronized(snapshots) {
            if (snapshotOwner != id) { snapshots.clear(); snapshotOwner = id }
        }
        return id
    }

    private fun checkOwner(id: String) {
        if (owner() != id) throw RoutineError.Unauthorized()
    }

    override fun getCachedRoutine(userId: String, routineId: String): WorkoutTemplate? {
        if (supabaseClient.auth.currentUserOrNull()?.id != userId) return null
        return synchronized(snapshots) { snapshots[routineId]?.takeIf { it.userId == userId } }
    }

    private fun remember(template: WorkoutTemplate): WorkoutTemplate {
        checkOwner(template.userId)
        synchronized(snapshots) {
            val previous = snapshots[template.id]
            if (previous == null || (previous.revision <= template.revision && (template.exercises.isNotEmpty() || previous.exercises.isEmpty()))) {
                snapshots[template.id] = template
            }
        }
        return template
    }

    override suspend fun getRoutines(userId: String): Result<List<WorkoutTemplate>> = result {
        checkOwner(userId)
        val generation = mutationGeneration.get()
        var cursor: String? = null
        val items = mutableListOf<Summary>()
        do {
            val page = supabaseClient.postgrest.rpc("get_template_page", buildJsonObject {
                cursor?.let { put("p_cursor", it) }
                put("p_limit", 15)
            }).decodeAs<TemplatePage>()
            items.addAll(page.data)
            cursor = if (page.pageInfo.hasMore) page.pageInfo.nextCursor else null
        } while (cursor != null)

        val routines = items.map { summary ->
            val cached = getCachedRoutine(userId, summary.id)
            if (cached != null && cached.revision == summary.revision && (cached.exercises.isNotEmpty() || summary.exerciseCount == 0)) {
                cached
            } else {
                WorkoutTemplate(
                    id = summary.id,
                    userId = userId,
                    name = summary.name,
                    exercises = emptyList(),
                    createdAt = System.currentTimeMillis(),
                    revision = summary.revision,
                    explicitPrimaryCategory = summary.primaryCategoryId?.let { id ->
                        MuscleCategory.entries.getOrNull(id - 1)
                    },
                    exerciseCountOverride = summary.exerciseCount,
                    measurementVersion = summary.measurementVersion
                )
            }
        }
        routines.forEach { if (it.exercises.isNotEmpty()) remember(it) }
        routines
    }

    override suspend fun getRoutineById(routineId: String): Result<WorkoutTemplate?> = result {
        val userId = owner()
        val generation = mutationGeneration.get()
        val detail = supabaseClient.postgrest.rpc("get_template_detail", buildJsonObject {
            put("p_template_id", routineId)
        }).decodeAs<TemplateDetail>()
        val template = hydrate(detail, userId)
        if (generation != mutationGeneration.get()) throw RoutineError.Server("Routine changed while detail was loading; refresh")
        remember(template)
    }

    private suspend fun hydrate(detail: TemplateDetail, userId: String): WorkoutTemplate {
        require(detail.measurementVersion == null || detail.measurementVersion == 3) { "Unsupported routine measurement version" }
        require(detail.exercises.all { it.measurementVersion == detail.measurementVersion }) { "Mixed routine measurement versions" }
        val children = detail.exercises.sortedBy { it.orderIndex }
        val ids = children.map { it.exerciseId }
        val exercises = when (val hydration = exerciseCatalogRepository.getExercisesByIds(ids)) {
            is ExerciseHydrationResult.Success -> hydration.exercises.associateBy { it.id }
            is ExerciseHydrationResult.Missing -> throw RoutineError.MissingExercises(hydration.missingIds)
            is ExerciseHydrationResult.Error -> throw hydration.cause
        }
        checkOwner(userId)
        return WorkoutTemplate(detail.id, userId, detail.name, children.map { child ->
            val ex = exercises[child.exerciseId] ?: throw RoutineError.MissingExercises(listOf(child.exerciseId))
            val parsedTracking = child.trackingType?.let(TrackingType::valueOf)
                ?: ex.defaultTrackingType
            TemplateExercise(
                exercise = ex,
                sets = child.sets,
                reps = child.reps,
                restSeconds = child.restSeconds,
                trackingType = parsedTracking,
                targetDurationSeconds = child.targetDurationSeconds,
                targetDistanceMeters = child.targetDistanceMeters,
                setTargets = child.setTargets,
                measurementVersion = child.measurementVersion
            )
        }, revision = detail.revision, measurementVersion = detail.measurementVersion)
    }

    override suspend fun createRoutine(userId: String, name: String, requestId: String): Result<WorkoutTemplate> = result {
        checkOwner(userId)
        validate(name, emptyList())
        val id = UUID.nameUUIDFromBytes("routine:$userId:$requestId".toByteArray(Charsets.UTF_8)).toString()
        val receipt = mutate(userId, id, name.trim(), emptyList(), null, requestId, emptyList(), false)
        remember(WorkoutTemplate(receipt.templateId, userId, name.trim(), emptyList(), revision = receipt.revision))
    }

    override suspend fun saveRoutine(
        routineId: String, name: String, exercises: List<TemplateExercise>, expectedRevision: Long?,
        requestId: String, baselineExerciseIds: List<String>
    ): Result<WorkoutTemplate> = result {
        val userId = owner()
        validate(name, exercises)
        if (expectedRevision == null) throw RoutineError.ValidationError("Routine baseline is required")
        val version = explicitRoutineVersion(exercises)
        if (getCachedRoutine(userId, routineId)?.measurementVersion == 3 && version != 3) {
            throw RoutineError.ValidationError("Routine requires explicit version 3 set targets")
        }
        val receipt = mutate(userId, routineId, name.trim(), exercises, expectedRevision, requestId,
            baselineExerciseIds.filterNot { id -> exercises.any { it.id == id } }, false, version)
        remember(WorkoutTemplate(receipt.templateId, userId, name.trim(), exercises.toList(), revision = receipt.revision,
            measurementVersion = version))
    }

    override suspend fun deleteRoutine(
        routineId: String, expectedRevision: Long?, requestId: String,
        baselineExerciseIds: List<String>, name: String
    ): Result<Boolean> = result {
        val userId = owner()
        if (expectedRevision == null) throw RoutineError.ValidationError("Routine baseline is required")
        mutate(userId, routineId, name, emptyList(), expectedRevision, requestId, baselineExerciseIds, true,
            getCachedRoutine(userId, routineId)?.measurementVersion)
        synchronized(snapshots) { snapshots.remove(routineId) }
        true
    }

    private fun validate(name: String, exercises: List<TemplateExercise>) {
        if (name.trim().length !in 2..100 || exercises.size > 100 || exercises.map { it.id }.distinct().size != exercises.size) {
            throw RoutineError.ValidationError("Invalid routine name or prescription")
        }
        for (item in exercises) {
            if (!item.exercise.isActive || !item.target.isValidFor(item.trackingType)) {
                throw RoutineError.ValidationError("Invalid routine name or prescription")
            }
            if (item.measurementVersion == 3) {
                item.exercise.requireV3Metadata()
                if (item.trackingType != item.exercise.defaultTrackingType) {
                    throw RoutineError.ValidationError("Version 3 target format must match exercise metadata")
                }
            }
            if (item.trackingType !in item.exercise.allowedTrackingTypes) {
                throw RoutineError.ValidationError("Tracking type not allowed for exercise: ${item.name}")
            }
        }
    }

    private suspend fun mutate(
        userId: String, id: String, name: String, exercises: List<TemplateExercise>, revision: Long?,
        requestId: String, removedIds: List<String>, deleted: Boolean, measurementVersion: Int? = null
    ): Receipt {
        val response = supabaseClient.postgrest.rpc("save_template_revision", buildJsonObject {
            put("p_request_id", requestId)
            put("p_template_id", id)
            put("p_expected_revision", revision?.let(::JsonPrimitive) ?: JsonNull)
            put("p_name", name)
            put("p_exercises", routineExercisePayload(exercises))
            if (measurementVersion == 3) put("p_measurement_version", 3)
            put("p_removed_exercise_ids", buildJsonArray { removedIds.forEach { add(JsonPrimitive(it)) } })
            put("p_is_deleted", deleted)
            put("p_owner_id", userId)
        }).decodeAs<Receipt>()
        checkOwner(userId)
        if (response.requestId != requestId || response.templateId != id) throw RoutineError.Server("Mismatched routine receipt")
        mutationGeneration.incrementAndGet()
        return response
    }
    private suspend fun <T> result(block: suspend () -> T): Result<T> = try { Result.success(block()) }
    catch (e: CancellationException) { throw e }
    catch (e: Exception) { Result.failure(mapError(e)) }


    private fun mapError(e: Exception): Exception {
        if (e is RoutineError) return e
        val message = e.message ?: return RoutineError.Network("Network error", e)
        return if (message.contains("REVISION_CONFLICT", ignoreCase = true)) RoutineError.RevisionConflict()
        else if (message.contains("RESOURCE_NOT_FOUND", ignoreCase = true)) RoutineError.NotFound()
        else if (message.contains("VALIDATION", ignoreCase = true)) RoutineError.ValidationError(message)
        else if (message.contains("42501") || message.contains("Unauthorized", ignoreCase = true)) RoutineError.Unauthorized()
        else if (e is RestException) RoutineError.Server(message, e)
        else if (e is HttpRequestException || e is IOException) RoutineError.Network(message, e)
        else RoutineError.Server("Routine response could not be processed: $message", e)
    }

    @Serializable private data class TemplatePage(val data: List<Summary>, @SerialName("page_info") val pageInfo: PageInfo)
    @Serializable
    private data class Summary(
        val id: String,
        val name: String = "",
        val revision: Long = 0L,
        @SerialName("exercise_count") val exerciseCount: Int = 0,
        @SerialName("primary_category_id") val primaryCategoryId: Int? = null,
        @SerialName("measurement_version") val measurementVersion: Int? = null
    )
    @Serializable private data class PageInfo(@SerialName("has_more") val hasMore: Boolean, @SerialName("next_cursor") val nextCursor: String? = null)
    @Serializable private data class TemplateDetail(
        val id: String, val name: String, val revision: Long, val exercises: List<Child>,
        @SerialName("measurement_version") val measurementVersion: Int? = null
    )
    @Serializable private data class Child(
        @SerialName("exercise_id") val exerciseId: String,
        @SerialName("order_index") val orderIndex: Int,
        val sets: Int,
        val reps: Int? = null,
        @SerialName("rest_seconds") val restSeconds: Int,
        @SerialName("tracking_type") val trackingType: String? = null,
        @SerialName("target_duration_seconds") val targetDurationSeconds: Int? = null,
        @SerialName("target_distance_meters") val targetDistanceMeters: Int? = null,
        @SerialName("set_targets") val setTargets: List<SetTarget>? = null,
        @SerialName("measurement_version") val measurementVersion: Int? = null
    )
    @Serializable private data class Receipt(@SerialName("request_id") val requestId: String, @SerialName("template_id") val templateId: String, val revision: Long)
}

/** A v3 command is explicit and complete; catalog metadata never upgrades a scalar command. */
internal fun explicitRoutineVersion(exercises: List<TemplateExercise>): Int? {
    val hasV3 = exercises.any { it.measurementVersion == 3 || it.setTargets != null }
    if (hasV3) require(exercises.all { it.measurementVersion == 3 && it.setTargets != null }) {
        "Version 3 routine commands require explicit targets for every exercise"
    }
    require(exercises.all { it.measurementVersion == null || it.measurementVersion == 3 }) {
        "Unsupported routine measurement version"
    }
    return if (hasV3) 3 else null
}

internal fun routineExercisePayload(exercises: List<TemplateExercise>): JsonArray {
    val version = explicitRoutineVersion(exercises)
    return buildJsonArray {
        exercises.forEachIndexed { index, child -> add(buildJsonObject {
            put("exercise_id", child.id)
            put("order_index", index)
            put("tracking_type", child.trackingType.name)
            if (version == 3) {
                put("measurement_version", 3)
                put("set_targets", routineTargetsJson.encodeToJsonElement(requireNotNull(child.setTargets)))
            } else {
                put("sets", child.sets)
                put("reps", child.reps?.let(::JsonPrimitive) ?: JsonNull)
                child.legacyRestSeconds?.let { put("rest_seconds", it) }
                child.targetDurationSeconds?.let { put("target_duration_seconds", it) }
                child.targetDistanceMeters?.let { put("target_distance_meters", it) }
            }
        }) }
    }
}

private val routineTargetsJson = Json { encodeDefaults = true; explicitNulls = true }

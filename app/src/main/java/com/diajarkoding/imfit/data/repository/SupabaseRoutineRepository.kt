package com.diajarkoding.imfit.data.repository

import com.diajarkoding.imfit.domain.model.MuscleCategory
import com.diajarkoding.imfit.domain.model.TemplateExercise
import com.diajarkoding.imfit.domain.model.WorkoutTemplate
import com.diajarkoding.imfit.domain.model.supportsRepetitions
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
        val routines = mutableListOf<WorkoutTemplate>()
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val page = supabaseClient.postgrest.rpc("get_template_page", buildJsonObject {
                cursor?.let { put("p_cursor", it) }; put("p_limit", 50)
            }).decodeAs<TemplatePage>()
            for (summary in page.data) {
                val primaryMuscle = summary.primaryCategoryId?.let { catId: Int ->
                    MuscleCategory.entries.getOrNull(catId - 1)
                }
                val cached = synchronized(snapshots) { snapshots[summary.id] }
                if (cached != null && cached.revision == summary.revision && cached.exercises.isNotEmpty()) {
                    routines += cached.copy(
                        name = summary.name.ifBlank { cached.name },
                        explicitPrimaryCategory = primaryMuscle,
                        exerciseCountOverride = summary.exerciseCount
                    )
                } else {
                    val template = WorkoutTemplate(
                        id = summary.id,
                        userId = userId,
                        name = summary.name,
                        exercises = emptyList(),
                        revision = summary.revision,
                        explicitPrimaryCategory = primaryMuscle,
                        exerciseCountOverride = summary.exerciseCount
                    )
                    remember(template)
                    routines += template
                }
            }
            checkOwner(userId)
            cursor = if (page.pageInfo.hasMore) {
                page.pageInfo.nextCursor?.takeIf { it.isNotBlank() && cursors.add(it) }
                    ?: throw RoutineError.Server("Invalid routine continuation")
            } else null
        } while (cursor != null)
        if (generation != mutationGeneration.get()) throw RoutineError.Server("Routine changed while list was loading; refresh")
        routines.forEach(::remember)
        synchronized(snapshots) { snapshots.keys.retainAll(routines.map { it.id }.toSet()) }
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
        val children = detail.exercises.sortedBy { it.orderIndex }
        val ids = children.map { it.exerciseId }
        val exercises = when (val hydration = exerciseCatalogRepository.getExercisesByIds(ids)) {
            is ExerciseHydrationResult.Success -> hydration.exercises.associateBy { it.id }
            is ExerciseHydrationResult.Missing -> throw RoutineError.MissingExercises(hydration.missingIds)
            is ExerciseHydrationResult.Error -> throw hydration.cause
        }
        checkOwner(userId)
        return WorkoutTemplate(detail.id, userId, detail.name, children.map { child ->
            TemplateExercise(exercises[child.exerciseId] ?: throw RoutineError.MissingExercises(listOf(child.exerciseId)),
                child.sets, child.reps, child.restSeconds)
        }, revision = detail.revision)
    }

    override suspend fun createRoutine(userId: String, name: String, requestId: String): Result<WorkoutTemplate> = result {
        checkOwner(userId)
        validate(name, emptyList())
        // The resource identity is part of the replay payload, not a fresh UUID per attempt.
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
        val receipt = mutate(userId, routineId, name.trim(), exercises, expectedRevision, requestId,
            baselineExerciseIds.filterNot { id -> exercises.any { it.id == id } }, false)
        remember(WorkoutTemplate(receipt.templateId, userId, name.trim(), exercises.toList(), revision = receipt.revision))
    }

    override suspend fun deleteRoutine(
        routineId: String, expectedRevision: Long?, requestId: String,
        baselineExerciseIds: List<String>, name: String
    ): Result<Boolean> = result {
        val userId = owner()
        if (expectedRevision == null) throw RoutineError.ValidationError("Routine baseline is required")
        mutate(userId, routineId, name, emptyList(), expectedRevision, requestId, baselineExerciseIds, true)
        synchronized(snapshots) { snapshots.remove(routineId) }
        true
    }

    private fun validate(name: String, exercises: List<TemplateExercise>) {
        if (name.trim().length !in 2..100 || exercises.size > 100 || exercises.map { it.id }.distinct().size != exercises.size ||
            exercises.any { it.sets !in 1..20 || it.reps !in 1..100 || it.restSeconds !in 0..600 || !it.exercise.isActive || !it.exercise.supportsRepetitions }) {
            throw RoutineError.ValidationError("Invalid routine name or prescription")
        }
    }

    private suspend fun mutate(
        userId: String, id: String, name: String, exercises: List<TemplateExercise>, revision: Long?,
        requestId: String, removedIds: List<String>, deleted: Boolean
    ): Receipt {
        val response = supabaseClient.postgrest.rpc("save_template_revision", buildJsonObject {
            put("p_request_id", requestId); put("p_template_id", id)
            put("p_expected_revision", revision?.let(::JsonPrimitive) ?: JsonNull)
            put("p_name", name)
            put("p_exercises", buildJsonArray {
                exercises.forEachIndexed { index, child -> add(buildJsonObject {
                    put("exercise_id", child.id); put("order_index", index); put("sets", child.sets)
                    put("reps", child.reps); put("rest_seconds", child.restSeconds)
                }) }
            })
            put("p_removed_exercise_ids", buildJsonArray { removedIds.forEach { add(JsonPrimitive(it)) } })
            put("p_is_deleted", deleted); put("p_owner_id", userId)
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
        if (e is RestException) return when {
            e.error.contains("WORKOUT_ACTIVE") || e.error.contains("SESSION_REVIEW") -> RoutineError.ActiveSessionConflict()
            e.error.contains("REVISION_CONFLICT") -> RoutineError.RevisionConflict()
            e.statusCode == 404 -> RoutineError.NotFound()
            e.statusCode == 401 || e.statusCode == 403 -> RoutineError.Unauthorized()
            e.statusCode == 400 -> RoutineError.ValidationError(e.error)
            else -> RoutineError.Server(e.error, e)
        }
        return if (e is IOException || e is HttpRequestException) RoutineError.Network("Connection failed; retry the same operation", e)
        else RoutineError.Server("Routine response could not be processed", e)
    }

    @Serializable private data class TemplatePage(val data: List<Summary>, @SerialName("page_info") val pageInfo: PageInfo)
    @Serializable
    private data class Summary(
        val id: String,
        val name: String = "",
        val revision: Long = 0L,
        @SerialName("exercise_count") val exerciseCount: Int = 0,
        @SerialName("primary_category_id") val primaryCategoryId: Int? = null
    )
    @Serializable private data class PageInfo(@SerialName("has_more") val hasMore: Boolean, @SerialName("next_cursor") val nextCursor: String? = null)
    @Serializable private data class TemplateDetail(val id: String, val name: String, val revision: Long, val exercises: List<Child>)
    @Serializable private data class Child(@SerialName("exercise_id") val exerciseId: String, @SerialName("order_index") val orderIndex: Int,
        val sets: Int, val reps: Int, @SerialName("rest_seconds") val restSeconds: Int)
    @Serializable private data class Receipt(@SerialName("request_id") val requestId: String, @SerialName("template_id") val templateId: String, val revision: Long)
}

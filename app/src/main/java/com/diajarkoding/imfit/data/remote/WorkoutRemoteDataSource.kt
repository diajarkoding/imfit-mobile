package com.diajarkoding.imfit.data.remote

import com.diajarkoding.imfit.data.remote.dto.ExerciseDto
import com.diajarkoding.imfit.data.remote.dto.TemplateExerciseDto
import com.diajarkoding.imfit.data.remote.dto.WorkoutLogDto
import com.diajarkoding.imfit.data.remote.dto.WorkoutTemplateDto
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

interface WorkoutRemoteDataSource {
    suspend fun fetchActiveTemplates(userId: String): List<WorkoutTemplateDto>
    suspend fun fetchLatestCompletedWorkoutLog(userId: String): WorkoutLogDto?
}

class SupabaseWorkoutRemoteDataSource(
    private val supabaseClient: SupabaseClient
) : WorkoutRemoteDataSource {
    override suspend fun fetchActiveTemplates(userId: String): List<WorkoutTemplateDto> {
        return try {
            fetchActiveTemplatesViaRpc(userId)
        } catch (rpcError: CancellationException) {
            throw rpcError
        } catch (rpcError: Exception) {
            try {
                pages { from, to ->
                    supabaseClient.postgrest.from("workout_templates")
                        .select(Columns.raw("*, template_exercises(*, exercises(*, muscle_categories(*)))")) {
                            filter {
                                eq("user_id", userId)
                                eq("is_deleted", false)
                            }
                            order("created_at", Order.ASCENDING)
                            order("id", Order.ASCENDING)
                            range(from, to)
                        }
                        .decodeList<WorkoutTemplateDto>()
                }
            } catch (_: Exception) {
                throw rpcError
            }
        }
    }

    override suspend fun fetchLatestCompletedWorkoutLog(userId: String): WorkoutLogDto? {
        return try {
            fetchLatestWorkoutViaRpc(userId)
        } catch (rpcError: CancellationException) {
            throw rpcError
        } catch (rpcError: Exception) {
            try {
                supabaseClient.postgrest.from("workout_logs")
                    .select(Columns.raw("*, exercise_logs(*, workout_sets(*), exercises(*, muscle_categories(*)))")) {
                        filter {
                            eq("user_id", userId)
                            exact("deleted_at", null)
                            filterNot("end_time", FilterOperator.IS, null)
                        }
                        order("end_time", Order.DESCENDING)
                        order("created_at", Order.DESCENDING)
                        order("id", Order.DESCENDING)
                        limit(1)
                    }
                    .decodeList<WorkoutLogDto>()
                    .firstOrNull()
            } catch (_: Exception) {
                throw rpcError
            }
        }
    }

    private suspend fun fetchLatestWorkoutViaRpc(userId: String): WorkoutLogDto? {
        val response = supabaseClient.postgrest
            .rpc("get_home_summary")
            .decodeAs<HomeSummaryRpcResponse>()
        val summary = response.lastWorkout ?: return null
        return WorkoutLogDto(
            id = summary.id,
            userId = userId,
            templateId = summary.templateId,
            templateName = summary.templateName,
            date = summary.startTime,
            startTime = summary.startTime,
            endTime = summary.endTime,
            totalVolume = summary.totalVolume.toDoubleOrNull() ?: 0.0,
            totalSets = summary.totalSets,
            totalReps = summary.totalReps,
            exerciseLogs = emptyList()
        )
    }

    private suspend fun fetchActiveTemplatesViaRpc(userId: String): List<WorkoutTemplateDto> {
        val exercisesById = runCatching {
            supabaseClient.postgrest.from("exercises")
                .select(Columns.raw("*, muscle_categories(*)"))
                .decodeList<ExerciseDto>()
                .associateBy { it.id }
        }.getOrDefault(emptyMap())

        val summaries = mutableListOf<TemplatePageItemDto>()
        var cursor: String? = null
        while (true) {
            val params: JsonObject = buildJsonObject {
                cursor?.let { put("p_cursor", it) }
                put("p_limit", 50)
            }
            val page = supabaseClient.postgrest
                .rpc("get_template_page", params)
                .decodeAs<TemplatePageRpcResponse>()
            summaries += page.data
            val next = page.pageInfo.nextCursor
            if (!page.pageInfo.hasMore || next.isNullOrBlank()) break
            cursor = next
        }

        return summaries.map { item ->
            val detail = if (item.exerciseCount > 0) {
                runCatching {
                    supabaseClient.postgrest
                        .rpc(
                            "get_template_detail",
                            buildJsonObject { put("p_template_id", item.id) }
                        )
                        .decodeAs<TemplateDetailRpcResponse>()
                }.getOrNull()
            } else {
                null
            }
            val mappedChildren = detail?.exercises.orEmpty().map { child ->
                TemplateExerciseDto(
                    id = child.id,
                    templateId = item.id,
                    exerciseId = child.exerciseId,
                    orderIndex = child.orderIndex,
                    sets = child.sets,
                    reps = child.reps,
                    restSeconds = child.restSeconds,
                    exercises = exercisesById[child.exerciseId]
                )
            }
            WorkoutTemplateDto(
                id = item.id,
                userId = userId,
                name = detail?.name ?: item.name,
                isDeleted = false,
                createdAt = item.updatedAt,
                updatedAt = detail?.updatedAt ?: item.updatedAt,
                templateExercises = mappedChildren
            )
        }
    }

    private suspend fun <T> pages(fetch: suspend (Long, Long) -> List<T>): List<T> {
        val result = mutableListOf<T>()
        var offset = 0L
        while (true) {
            val page = fetch(offset, offset + 499)
            result.addAll(page)
            if (page.size < 500) return result
            offset += page.size
        }
    }
}

@Serializable
private data class HomeSummaryRpcResponse(
    @SerialName("last_workout")
    val lastWorkout: HomeWorkoutSummaryRpcDto? = null
)

@Serializable
private data class HomeWorkoutSummaryRpcDto(
    val id: String,
    @SerialName("template_id")
    val templateId: String? = null,
    @SerialName("template_name")
    val templateName: String,
    @SerialName("start_time")
    val startTime: String,
    @SerialName("end_time")
    val endTime: String,
    @SerialName("duration_seconds")
    val durationSeconds: Long = 0L,
    @SerialName("total_volume")
    val totalVolume: String = "0",
    @SerialName("total_sets")
    val totalSets: Int = 0,
    @SerialName("total_reps")
    val totalReps: Int = 0
)

@Serializable
private data class TemplatePageRpcResponse(
    val data: List<TemplatePageItemDto> = emptyList(),
    @SerialName("page_info")
    val pageInfo: RpcPageInfoDto = RpcPageInfoDto()
)

@Serializable
private data class RpcPageInfoDto(
    val limit: Int = 50,
    @SerialName("has_more")
    val hasMore: Boolean = false,
    @SerialName("next_cursor")
    val nextCursor: String? = null
)

@Serializable
private data class TemplatePageItemDto(
    val id: String,
    val name: String,
    val revision: Long = 0L,
    @SerialName("updated_at")
    val updatedAt: String? = null,
    @SerialName("exercise_count")
    val exerciseCount: Int = 0
)

@Serializable
private data class TemplateDetailRpcResponse(
    val id: String,
    val name: String,
    val revision: Long = 0L,
    @SerialName("updated_at")
    val updatedAt: String? = null,
    val exercises: List<TemplateDetailExerciseRpcDto> = emptyList()
)

@Serializable
private data class TemplateDetailExerciseRpcDto(
    val id: String? = null,
    @SerialName("exercise_id")
    val exerciseId: String,
    @SerialName("order_index")
    val orderIndex: Int = 0,
    val sets: Int = 3,
    val reps: Int = 10,
    @SerialName("rest_seconds")
    val restSeconds: Int = 60
)

package com.diajarkoding.imfit.data.repository

import com.diajarkoding.imfit.data.remote.dto.ExerciseDto
import com.diajarkoding.imfit.data.remote.dto.toDomain
import com.diajarkoding.imfit.domain.model.Exercise
import com.diajarkoding.imfit.domain.model.MuscleCategory
import com.diajarkoding.imfit.domain.repository.ExerciseCatalogPage
import com.diajarkoding.imfit.domain.repository.ExerciseCatalogRepository
import com.diajarkoding.imfit.domain.repository.ExerciseHydrationResult
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import com.diajarkoding.imfit.domain.repository.RoutineError
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SupabaseExerciseCatalogRepository @Inject constructor(
    private val supabaseClient: SupabaseClient
) : ExerciseCatalogRepository {

    override suspend fun fetchCatalogPage(
        search: String,
        categoryId: Int?,
        cursor: String?,
        limit: Int
    ): Result<ExerciseCatalogPage> {
        return try {
            val owner = supabaseClient.auth.currentUserOrNull()?.id ?: throw RoutineError.Unauthorized()
            val params = buildJsonObject {
                put("p_search", search.trim())
                if (categoryId != null) put("p_category_id", categoryId)
                if (!cursor.isNullOrBlank()) put("p_cursor", cursor)
                put("p_limit", limit)
            }
            val response = supabaseClient.postgrest
                .rpc("get_exercise_catalog_page", params)
                .decodeAs<CatalogPageRpcResponse>()
            if (supabaseClient.auth.currentUserOrNull()?.id != owner) throw RoutineError.Unauthorized()

            val exercises = response.data.map { dto ->
                Exercise(
                    id = dto.id,
                    name = dto.name,
                    muscleCategory = MuscleCategory.entries.getOrNull(dto.muscleCategoryId - 1) ?: MuscleCategory.CHEST,
                    description = "",
                    imageUrl = dto.imageUrl,
                    isActive = true
                )
            }
            Result.success(
                ExerciseCatalogPage(
                    exercises = exercises,
                    nextCursor = response.pageInfo.nextCursor,
                    hasMore = response.pageInfo.hasMore
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getExercisesByIds(ids: List<String>): ExerciseHydrationResult {
        if (ids.isEmpty()) return ExerciseHydrationResult.Success(emptyList())
        val distinctIds = ids.distinct()
        return try {
            val owner = supabaseClient.auth.currentUserOrNull()?.id ?: throw RoutineError.Unauthorized()
            val response = supabaseClient.postgrest
                .from("exercises")
                .select(Columns.raw("id, muscle_category_id, name, description, image_url, video_url, difficulty, equipment, is_active, muscle_categories(*)")) {
                    filter {
                        isIn("id", distinctIds)
                    }
                }
                .decodeList<ExerciseDto>()
            if (supabaseClient.auth.currentUserOrNull()?.id != owner) throw RoutineError.Unauthorized()

            val byId = response.associateBy { it.id }
            val missingIds = distinctIds.filterNot { byId.containsKey(it) }

            // MUST preserve caller input order (order_index)
            val orderedExercises = ids.mapNotNull { id -> byId[id]?.toDomain() }

            if (missingIds.isNotEmpty()) {
                ExerciseHydrationResult.Missing(orderedExercises, missingIds)
            } else {
                ExerciseHydrationResult.Success(orderedExercises)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ExerciseHydrationResult.Error(e)
        }
    }

    @Serializable
    private data class CatalogPageRpcResponse(
        val data: List<CatalogPageItemDto> = emptyList(),
        @SerialName("page_info")
        val pageInfo: CatalogPageInfoDto = CatalogPageInfoDto()
    )

    @Serializable
    private data class CatalogPageInfoDto(
        val limit: Int = 10,
        @SerialName("has_more")
        val hasMore: Boolean = false,
        @SerialName("next_cursor")
        val nextCursor: String? = null
    )

    @Serializable
    private data class CatalogPageItemDto(
        val id: String,
        @SerialName("muscle_category_id")
        val muscleCategoryId: Int = 1,
        val name: String,
        @SerialName("image_url")
        val imageUrl: String? = null,
        val difficulty: String? = "INTERMEDIATE",
        val equipment: String? = null
    )
}

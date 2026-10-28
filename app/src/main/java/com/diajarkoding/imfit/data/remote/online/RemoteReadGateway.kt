package com.diajarkoding.imfit.data.remote.online

import com.diajarkoding.imfit.domain.model.Exercise
import com.diajarkoding.imfit.domain.model.MuscleCategory
import com.diajarkoding.imfit.domain.model.TemplateExercise
import com.diajarkoding.imfit.domain.model.WorkoutTemplate
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The read surface shared by production HTTP, deterministic demo, and scripted tests. No DAO. */
interface RemoteReadGateway {
    val auth: OnlineAuth
    suspend fun <T> read(call: RpcCall<T>): T
    suspend fun profile(): RemoteProfile
    suspend fun exercises(ids: List<String>): List<Exercise>
    /** Existing template mutation only; session mutations are NOT exposed by the G3 gateway. */
    suspend fun saveTemplate(request: com.diajarkoding.imfit.data.remote.TemplateWriteRequest): com.diajarkoding.imfit.data.remote.TemplateWriteReceipt
    suspend fun catalog(query: QueryKey.Catalog, cursor: String?, limit: Int): RemotePage<ExerciseSummary> {
        val ids = query.exerciseIds
        if (ids == null) return read(OnlineRpc.catalog(query.search, query.categoryId, cursor, limit))
        // Existing verified subgroup taxonomy is bounded (<= 2 IDs), not a full catalog scan.
        require(ids.size <= limit && cursor == null)
        val data = exercises(ids).filter { it.name.lowercase(java.util.Locale.ROOT).contains(query.search) }
            .sortedWith(compareBy<Exercise> { it.name.lowercase(java.util.Locale.ROOT) }.thenBy { it.id })
            .map { ExerciseSummary(it.id, it.muscleCategory.ordinal + 1, it.name, it.imageUrl, null, null) }
        return RemotePage(data, PageInfo(null, false, limit))
    }
    fun catalogFeed(scope: CoroutineScope) = RemotePagingFeed(auth, scope, pageSize = 25, stableId = ExerciseSummary::id) { query, cursor, limit ->
        catalog(query as QueryKey.Catalog, cursor, limit)
    }
    fun historyFeed(scope: CoroutineScope) = RemotePagingFeed(auth, scope, pageSize = 20, stableId = WorkoutSummary::id) { query, cursor, limit ->
        read(OnlineRpc.history((query as QueryKey.History).window, cursor, limit))
    }
    fun templateFeed(scope: CoroutineScope) = RemotePagingFeed(auth, scope, pageSize = 15, stableId = TemplateSummary::id) { _, cursor, limit ->
        read(OnlineRpc.templates(cursor, limit))
    }
    suspend fun template(id: String): Pair<TemplateDetail, WorkoutTemplate> {
        val identity = auth.capture().identity
        val detail = read(OnlineRpc.templateDetail(id))
        val records = exercises(detail.exercises.map { it.exerciseId }).associateBy { it.id }
        if (!auth.isCurrent(identity)) throw StaleAuthException()
        val children = detail.exercises.map { child ->
            TemplateExercise(records[child.exerciseId] ?: throw OnlineFailure(RemoteError.Conflict("LEGACY_REVIEW_REQUIRED", null)),
                child.sets, child.reps, child.restSeconds)
        }
        return detail to WorkoutTemplate(detail.id, identity.ownerId, detail.name, children,
            java.time.OffsetDateTime.parse(detail.updatedAt).toInstant().toEpochMilli())
    }
}

@Serializable
data class RemoteProfile(
    val id: String, val name: String, val email: String,
    @SerialName("birth_date") val birthDate: String?,
    @SerialName("avatar_url") val avatarUrl: String?,
)

fun ExerciseSummary.toExercise(): Exercise = Exercise(id, name,
    MuscleCategory.entries.getOrNull(muscleCategoryId - 1) ?: throw OnlineFailure(RemoteError.Protocol), "", imageUrl)

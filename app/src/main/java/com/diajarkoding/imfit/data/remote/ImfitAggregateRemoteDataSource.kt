package com.diajarkoding.imfit.data.remote

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

@Serializable
data class WorkoutAggregateRpcParams(
    @SerialName("p_workout")
    val workout: JsonObject,
    @SerialName("p_exercises")
    val exercises: JsonArray,
    @SerialName("p_sets")
    val sets: JsonArray,
)

@Serializable
data class WorkoutAggregateRpcResult(
    @SerialName("workout_id")
    val workoutId: String,
    @SerialName("exercise_count")
    val exerciseCount: Int,
    @SerialName("set_count")
    val setCount: Int,
    @SerialName("server_updated_at")
    val serverUpdatedAt: String,
)

class ImfitAggregateRemoteDataSource(
    private val supabase: SupabaseClient,
) {
    suspend fun upsertWorkout(
        workout: JsonObject,
        exercises: JsonArray,
        sets: JsonArray,
    ): WorkoutAggregateRpcResult = supabase.postgrest
        .rpc(
            function = "persist_local_workout_aggregate",
            parameters = rpcJson.encodeToJsonElement(
                WorkoutAggregateRpcParams(workout, exercises, sets),
            ).jsonObject,
        )
        .decodeAs()

    /** Owner-scoped atomic history pages; authenticated table reads are disabled. */
    suspend fun getWorkoutPage(afterId: String? = null, limit: Int = 25): JsonObject =
        supabase.postgrest.rpc("get_workout_sync_page", buildJsonObject {
            afterId?.let { put("p_after_id", it) }
            put("p_limit", limit)
        }).decodeAs()

    private companion object {
        val rpcJson = Json { explicitNulls = false }
    }
}

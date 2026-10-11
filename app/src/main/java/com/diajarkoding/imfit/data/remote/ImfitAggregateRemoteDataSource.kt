package com.diajarkoding.imfit.data.remote

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
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

@Serializable
data class TemplateAggregateRpcParams(
    @SerialName("p_template")
    val template: JsonObject,
    @SerialName("p_exercises")
    val exercises: JsonArray,
)

@Serializable
data class TemplateAggregateRpcResult(
    @SerialName("template_id")
    val templateId: String,
    @SerialName("exercise_count")
    val exerciseCount: Int,
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
            function = "upsert_workout_aggregate",
            parameters = rpcJson.encodeToJsonElement(
                WorkoutAggregateRpcParams(workout, exercises, sets),
            ).jsonObject,
        )
        .decodeAs()

    suspend fun upsertTemplate(
        template: JsonObject,
        exercises: JsonArray,
    ): TemplateAggregateRpcResult = supabase.postgrest
        .rpc(
            function = "upsert_template_aggregate",
            parameters = rpcJson.encodeToJsonElement(
                TemplateAggregateRpcParams(template, exercises),
            ).jsonObject,
        )
        .decodeAs()

    private companion object {
        val rpcJson = Json { explicitNulls = false }
    }
}

package com.diajarkoding.imfit.data.remote

import com.diajarkoding.imfit.data.remote.dto.WorkoutLogDto
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.ZoneOffset

interface WorkoutRemoteDataSource {
    suspend fun fetchLatestCompletedWorkoutLog(userId: String): WorkoutLogDto?
}

class SupabaseWorkoutRemoteDataSource(
    private val supabaseClient: SupabaseClient
) : WorkoutRemoteDataSource {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun fetchLatestCompletedWorkoutLog(userId: String): WorkoutLogDto? {
        check(supabaseClient.auth.currentUserOrNull()?.id == userId) { "Authentication required" }
        val summary = supabaseClient.postgrest.rpc("get_home_summary").decodeAs<JsonObject>()
        val last = summary["last_workout"]?.takeUnless { it is JsonNull }?.jsonObject ?: return null
        val detail = supabaseClient.postgrest.rpc("get_workout_detail", buildJsonObject {
            put("p_workout_id", last.getValue("id").jsonPrimitive.content)
        }).decodeAs<JsonObject>()
        check(supabaseClient.auth.currentUserOrNull()?.id == userId) { "Authentication changed" }
        // A summary must never replace a complete Room aggregate and erase its children.
        val children = detail.getValue("exercise_logs").jsonArray.map { element ->
            val child = element.jsonObject
            val sets = child.getValue("sets").jsonArray.map { set ->
                val fields = set.jsonObject
                JsonObject(fields + ("weight" to JsonPrimitive(fields.getValue("weight").jsonPrimitive.double)))
            }
            JsonObject(child + mapOf(
                "total_volume" to JsonPrimitive(child.getValue("total_volume").jsonPrimitive.double),
                "workout_sets" to JsonArray(sets)
            ))
        }
        val start = detail.getValue("start_time").jsonPrimitive.content
        val complete = JsonObject(detail + mapOf(
            "user_id" to JsonPrimitive(userId),
            "date" to JsonPrimitive(Instant.parse(start).atZone(ZoneOffset.UTC).toLocalDate().toString()),
            "total_volume" to JsonPrimitive(detail.getValue("total_volume").jsonPrimitive.double),
            "exercise_logs" to JsonArray(children)
        ))
        return json.decodeFromJsonElement<WorkoutLogDto>(complete)
    }
}

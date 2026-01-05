package com.diajarkoding.imfit.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ActiveSessionDto(
    val id: String? = null,
    @SerialName("user_id")
    val userId: String,
    @SerialName("template_id")
    val templateId: String,
    @SerialName("template_name")
    val templateName: String,
    @SerialName("start_time")
    val startTime: String? = null,
    @SerialName("current_exercise_index")
    val currentExerciseIndex: Int = 0,
    @SerialName("session_data")
    val sessionData: JsonObject? = null,
    @SerialName("last_activity_at")
    val lastActivityAt: String? = null,
    @SerialName("expires_at")
    val expiresAt: String? = null
)

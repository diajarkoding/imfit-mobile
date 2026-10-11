package com.diajarkoding.imfit.core.timer

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Versioned disk contract. Invalid records never become an EXPIRED alert. */
object RestTimerStateCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun encode(state: RestTimerState): String = json.encodeToString(state)
    fun decode(encoded: String): RestTimerState = json.decodeFromString<RestTimerState>(encoded).also {
        require(it.schemaVersion == 1 && it.durationMs in 0..86_400_000 && it.deadlineElapsedMs >= 0 && it.generation >= 0)
        require(it.timerId.isNotBlank() && it.userId.isNotBlank() && it.workoutId.isNotBlank() && it.setId.isNotBlank())
    }
}

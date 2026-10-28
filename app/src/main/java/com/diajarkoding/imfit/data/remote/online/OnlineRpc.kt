package com.diajarkoding.imfit.data.remote.online

import com.diajarkoding.imfit.data.remote.TemplateWriteReceipt
import com.diajarkoding.imfit.data.remote.TemplateWriteRequest
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlinx.serialization.serializer

internal val onlineJson = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = true // Additive response fields; required fields still fail closed.
    coerceInputValues = false
}

/** Commands cannot carry totals, owner, time anchors, version or arbitrary JSON fields. */
@Serializable
sealed interface SessionCommand {
    @Serializable @SerialName("edit_set")
    data class EditSet(
        @SerialName("exercise_log_id") val exerciseLogId: String,
        @SerialName("set_id") val setId: String,
        val weight: String, val reps: Int,
    ) : SessionCommand {
        init { validateInputWeight(weight); require(reps in 0..1000) }
    }
    @Serializable @SerialName("complete_set")
    data class CompleteSet(@SerialName("exercise_log_id") val exerciseLogId: String, @SerialName("set_id") val setId: String) : SessionCommand
    @Serializable @SerialName("uncomplete_set")
    data class UncompleteSet(@SerialName("exercise_log_id") val exerciseLogId: String, @SerialName("set_id") val setId: String) : SessionCommand
    @Serializable @SerialName("add_set")
    data class AddSet(@SerialName("exercise_log_id") val exerciseLogId: String, @SerialName("set_id") val setId: String) : SessionCommand
    @Serializable @SerialName("remove_set")
    data class RemoveSet(@SerialName("exercise_log_id") val exerciseLogId: String, @SerialName("set_id") val setId: String) : SessionCommand
    @Serializable @SerialName("add_exercise")
    data class AddExercise(@SerialName("exercise_log_id") val exerciseLogId: String, @SerialName("exercise_id") val exerciseId: String) : SessionCommand
    @Serializable @SerialName("remove_exercise")
    data class RemoveExercise(@SerialName("exercise_log_id") val exerciseLogId: String) : SessionCommand
    @Serializable @SerialName("set_rest_seconds")
    data class SetRestSeconds(@SerialName("exercise_log_id") val exerciseLogId: String, @SerialName("rest_seconds") val restSeconds: Int) : SessionCommand {
        init { require(restSeconds in 0..600) }
    }
    @Serializable @SerialName("select_exercise")
    data class SelectExercise(@SerialName("exercise_log_id") val exerciseLogId: String) : SessionCommand
    @Serializable @SerialName("pause") data object Pause : SessionCommand
    @Serializable @SerialName("resume") data object Resume : SessionCommand
    @Serializable @SerialName("acknowledge_expiry") data object AcknowledgeExpiry : SessionCommand
}

internal fun validateInputWeight(value: String) {
    require(Regex("[0-9]+(\\.[0-9]{1,2})?").matches(value) && value.toBigDecimal() <= BigDecimal(1000)) {
        "Weight must be an unsigned decimal string, <=1000 kg, at most 2 decimal places"
    }
}

data class DateWindow(val startDate: String, val endDate: String, val timezone: String) {
    init {
        val days = ChronoUnit.DAYS.between(LocalDate.parse(startDate), LocalDate.parse(endDate))
        require(days in 1..366)
        require(timezone == "UTC" || timezone in ZoneId.getAvailableZoneIds())
    }
}

internal data class MutationIdentity(val requestId: String, val resourceType: String, val resourceId: String)

/** Built once before dispatch. The JSON body (including CAS and IDs) is reused unchanged on retry. */
class RpcCall<T> internal constructor(
    val name: String,
    val parameters: JsonObject,
    internal val serializer: KSerializer<T>,
    internal val mutation: MutationIdentity? = null,
    internal val validate: (T) -> Unit = {},
)

object OnlineRpc {
    private inline fun <reified T> read(name: String, body: JsonObject, noinline validate: (T) -> Unit = {}) =
        RpcCall(name, body, serializer<T>(), validate = validate)

    fun saveTemplate(request: TemplateWriteRequest): RpcCall<TemplateWriteReceipt> {
        val body = onlineJson.encodeToJsonElement(request).jsonObject
        require(body.toString().toByteArray(Charsets.UTF_8).size <= 262144)
        return RpcCall("save_template_revision", body, serializer(),
            MutationIdentity(request.requestId, "TEMPLATE", request.templateId)) {
            require(it.requestId == request.requestId && it.templateId == request.templateId && it.revision >= 0)
        }
    }

    fun history(window: DateWindow, cursor: String? = null, limit: Int = 20) =
        read<RemotePage<WorkoutSummary>>("get_workout_history_page", windowBody(window, cursor, limit))

    fun catalog(search: String = "", categoryId: Int? = null, cursor: String? = null, limit: Int = 25): RpcCall<RemotePage<ExerciseSummary>> {
        val normalized = normalizeSearch(search)
        require(normalized.length <= 100)
        return read("get_exercise_catalog_page", buildJsonObject {
            put("p_search", normalized); put("p_category_id", categoryId); page(cursor, limit)
        })
    }

    fun templates(cursor: String? = null, limit: Int = 15) =
        read<RemotePage<TemplateSummary>>("get_template_page", buildJsonObject { page(cursor, limit) })
    fun workoutDetail(id: String) = read<WorkoutDetail>("get_workout_detail", buildJsonObject { put("p_workout_id", id) })
    fun templateDetail(id: String) = read<TemplateDetail>("get_template_detail", buildJsonObject { put("p_template_id", id) })
    fun progress(window: DateWindow) = read<ProgressSummary>("get_progress_summary", windowBody(window))
    fun dateMarkers(window: DateWindow) = read<DateMarkers>("get_workout_date_markers", windowBody(window))
    fun prefill(exerciseId: String, defaults: InputDefaults) = read<ExercisePrefill>("get_exercise_prefill", buildJsonObject {
        put("p_exercise_id", exerciseId); put("p_input_defaults", onlineJson.encodeToJsonElement(defaults))
    })
    fun createSession(requestId: String, sessionId: String, templateId: String, revision: Long) =
        sessionMutation("create_active_session", requestId, sessionId, buildJsonObject {
            require(revision >= 0); put("p_template_id", templateId); put("p_expected_template_revision", revision)
        })
    fun activeSession(sessionId: String? = null) = read<SessionLookup>("get_active_session", buildJsonObject {
        put("p_session_id", sessionId)
    }) {
        require(it.found == (it.session != null))
        it.session?.let { session ->
            require(session.version >= 1 && session.schemaVersion == 1)
            require(sessionId == null || session.id == sessionId)
        }
    }
    fun updateSession(requestId: String, sessionId: String, version: Long, command: SessionCommand): RpcCall<SessionMutationReceipt> {
        var encoded = onlineJson.encodeToJsonElement<SessionCommand>(command).jsonObject
        if (command is SessionCommand.EditSet) {
            encoded = JsonObject(encoded + ("weight" to JsonPrimitive(command.weight.toBigDecimal().setScale(2).toPlainString())))
        }
        return sessionMutation("update_active_session", requestId, sessionId, buildJsonObject {
            require(version >= 1); put("p_expected_version", version); put("p_command", encoded)
        })
    }
    fun cancelSession(requestId: String, sessionId: String, version: Long) =
        terminal("cancel_active_session", requestId, sessionId, version)
    fun finishSession(requestId: String, sessionId: String, version: Long) =
        terminal("finish_active_session", requestId, sessionId, version)
    fun operationStatus(requestId: String) = read<OperationLookup>("get_operation_status", buildJsonObject {
        put("p_request_id", requestId)
    }) { require((it.state == OperationState.COMMITTED) == (it.receipt != null)) }
    fun recentOperations(from: String, until: String, cursor: String? = null, limit: Int = 20) =
        read<RemotePage<OperationReceipt>>("list_recent_operations", buildJsonObject {
            val duration = java.time.Duration.between(java.time.OffsetDateTime.parse(from), java.time.OffsetDateTime.parse(until))
            require(!duration.isNegative && !duration.isZero && duration <= java.time.Duration.ofDays(90))
            put("p_from", from); put("p_until", until); page(cursor, limit)
        })

    private fun terminal(name: String, request: String, session: String, version: Long) =
        sessionMutation(name, request, session, buildJsonObject { require(version >= 1); put("p_expected_version", version) })

    private fun sessionMutation(name: String, request: String, session: String, extra: JsonObject): RpcCall<SessionMutationReceipt> {
        val body = buildJsonObject { put("p_request_id", request); put("p_session_id", session); extra.forEach { (k, v) -> put(k, v) } }
        require(body.toString().toByteArray(Charsets.UTF_8).size <= 262144)
        return RpcCall(name, body, serializer(), MutationIdentity(request, "SESSION", session)) {
            require(it.requestId == request && it.operation == name && it.sessionId == session)
            require(it.version >= 1 && it.version == it.snapshot.version && it.status == it.snapshot.status && it.snapshot.id == session)
            require(it.snapshot.schemaVersion == 1)
            if (name == "finish_active_session") require(it.status == SessionStatus.COMPLETED && it.workoutId != null)
            if (it.status == SessionStatus.COMPLETED) require(it.workoutId != null && it.workoutId == it.snapshot.completedWorkoutId)
        }
    }

    private fun windowBody(window: DateWindow) = buildJsonObject { window(window) }
    private fun windowBody(window: DateWindow, cursor: String?, limit: Int) = buildJsonObject { window(window); page(cursor, limit) }
    private fun JsonObjectBuilder.window(window: DateWindow) {
        put("p_start_date", window.startDate); put("p_end_date", window.endDate); put("p_timezone", window.timezone)
    }
    private fun JsonObjectBuilder.page(cursor: String?, limit: Int) {
        require(limit in 1..100); put("p_cursor", cursor); put("p_limit", limit)
    }
}

fun normalizeSearch(search: String): String = search.trim(' ').lowercase(Locale.ROOT)

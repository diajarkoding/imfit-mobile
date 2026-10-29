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
    fun startRecoveryStatus() = read<StartRecoveryStatus>("get_start_recovery_status",
        buildJsonObject { put("p_contract_version", 1) }) {
        require(it.contractVersion == 1)
        timestamp(it.checkedAt)
        require(it.blockers.size <= 20 && (!it.hasMoreBlockers || it.blockers.size == 20))
        require(it.canCreate == (it.blockers.isEmpty() && !it.hasMoreBlockers))
        require(it.blockers.map { blocker -> blocker.resourceId }.distinct().size == it.blockers.size)
        it.blockers.forEach { blocker ->
            uuid(blocker.resourceId)
            if (blocker.kind == RecoveryBlockerKind.LEGACY_SESSION) {
                require((blocker.version == null || blocker.version >= 1) && blocker.reason == RecoveryBlockerReason.LEGACY_REVIEW_REQUIRED)
                digest(requireNotNull(blocker.sourceFingerprint))
            } else {
                require(requireNotNull(blocker.version) >= 1 && blocker.sourceFingerprint == null)
                require(blocker.reason == RecoveryBlockerReason.SESSION_REVIEW_REQUIRED)
            }
        }
    }

    fun legacyRecoveryPage(cursor: String? = null, limit: Int = 20) =
        read<RemotePage<LegacyRecoveryItem>>("get_legacy_recovery_page", buildJsonObject { page(cursor, limit) }) {
            require(it.pageInfo.limit == limit && it.data.size <= limit)
            require(it.pageInfo.hasMore == (it.pageInfo.nextCursor != null))
            require(it.data.map { item -> item.sessionId }.distinct().size == it.data.size)
            require(it.data.map { item -> item.sessionId } == it.data.map { item -> item.sessionId }.sorted())
            require(!it.pageInfo.hasMore || it.data.size == limit)
            it.data.forEach { item ->
                uuid(item.sessionId); digest(item.sourceFingerprint)
                require(item.rawSource["id"]?.jsonPrimitive?.content == item.sessionId)
                uuid(item.rawSource.getValue("user_id").jsonPrimitive.content)
                require((item.rawSource["version"] == null || item.rawSource["version"] == JsonNull) ||
                    (item.rawSource["schema_version"] == null || item.rawSource["schema_version"] == JsonNull))
            }
        }

    fun resolveLegacySession(requestId: String, sessionId: String, sourceFingerprint: String,
        resolution: String = "KEEP_ARCHIVED"): RpcCall<LegacySessionResolutionReceipt> {
        uuid(requestId); uuid(sessionId); digest(sourceFingerprint); require(resolution == "KEEP_ARCHIVED")
        return RpcCall("resolve_legacy_session", buildJsonObject {
            put("p_request_id", requestId); put("p_session_id", sessionId)
            put("p_expected_source_fingerprint", sourceFingerprint); put("p_resolution", resolution)
        }, serializer(), MutationIdentity(requestId, "SESSION", sessionId)) {
            require(it.requestId == requestId && it.operation == "resolve_legacy_session" && it.sessionId == sessionId)
            require(it.sourceFingerprint == sourceFingerprint && it.resolution == LegacyResolution.KEEP_ARCHIVED)
            timestamp(it.committedAt)
        }
    }

    fun importLegacyWorkout(requestId: String, source: JsonObject, workout: JsonObject,
        exercises: JsonArray, sets: JsonArray): RpcCall<LegacyWorkoutImportReceipt> {
        uuid(requestId)
        require(source.keys == setOf("schema_version", "kind", "id", "fingerprint", "mapping_version"))
        require(source.getValue("schema_version").jsonPrimitive.int in setOf(6, 7, 9))
        require(source.getValue("kind").jsonPrimitive.content == "WORKOUT" && source.getValue("mapping_version").jsonPrimitive.int == 1)
        val id = source.getValue("id").jsonPrimitive.content
        val fingerprint = source.getValue("fingerprint").jsonPrimitive.content
        uuid(id); digest(fingerprint); require(workout.getValue("id").jsonPrimitive.content == id)
        val ids = listOf(id) + exercises.map { it.jsonObject.getValue("id").jsonPrimitive.content } +
            sets.map { it.jsonObject.getValue("id").jsonPrimitive.content }
        ids.forEach(::uuid); require(ids.distinct().size == ids.size)
        val parentFields = setOf("id", "user_id", "template_id", "template_name", "date", "start_time", "end_time",
            "total_volume", "total_sets", "total_reps", "deleted_at", "created_at", "updated_at")
        val exerciseFields = setOf("id", "workout_log_id", "exercise_id", "exercise_name", "muscle_category",
            "order_index", "total_volume", "total_sets", "total_reps", "input_defaults", "created_at", "updated_at")
        val setFields = setOf("id", "exercise_log_id", "workout_log_id", "exercise_id", "set_number", "weight",
            "reps", "is_completed", "created_at", "updated_at")
        require(workout.keys.all { it in parentFields })
        require(workout.keys.containsAll(parentFields - setOf("template_id", "deleted_at")))
        uuid(workout.getValue("user_id").jsonPrimitive.content)
        workout["template_id"]?.takeUnless { it == JsonNull }?.let { uuid(it.jsonPrimitive.content) }
        LocalDate.parse(workout.getValue("date").jsonPrimitive.content)
        listOf("start_time", "end_time", "created_at", "updated_at").forEach { timestamp(workout.getValue(it).jsonPrimitive.content) }
        workout["deleted_at"]?.takeUnless { it == JsonNull }?.let { timestamp(it.jsonPrimitive.content) }
        exercises.forEach { require(it.jsonObject.keys.all { key -> key in exerciseFields }) }
        sets.forEach { require(it.jsonObject.keys.all { key -> key in setFields }) }
        exercises.forEach {
            val row = it.jsonObject
            require(row.keys.containsAll(exerciseFields - "input_defaults"))
            // Catalog identity is text (for example ex_back_1), unlike aggregate UUIDs.
            val exerciseId = row.getValue("exercise_id").jsonPrimitive
            require(exerciseId.isString && exerciseId.content.isNotBlank())
            timestamp(row.getValue("created_at").jsonPrimitive.content); timestamp(row.getValue("updated_at").jsonPrimitive.content)
        }
        sets.forEach {
            val row = it.jsonObject
            require(row.keys.containsAll(setFields))
            require(row.getValue("set_number").jsonPrimitive.int >= 1 && row.getValue("reps").jsonPrimitive.int in 0..1000)
            require(row.getValue("weight").jsonPrimitive.content.toBigDecimal() in BigDecimal.ZERO..BigDecimal(1000))
            row.getValue("is_completed").jsonPrimitive.boolean
            timestamp(row.getValue("created_at").jsonPrimitive.content); timestamp(row.getValue("updated_at").jsonPrimitive.content)
        }
        val children = exercises.map { it.jsonObject }
        require(children.all { it.getValue("workout_log_id").jsonPrimitive.content == id })
        require(children.map { it.getValue("order_index").jsonPrimitive.int }.let { it.distinct().size == it.size && it.all { index -> index >= 0 } })
        require(sets.map { it.jsonObject }.all { row ->
            row.getValue("workout_log_id").jsonPrimitive.content == id &&
                children.any { child -> child.getValue("id") == row.getValue("exercise_log_id") &&
                    child.getValue("exercise_id") == row.getValue("exercise_id") }
        })
        require(sets.map { it.jsonObject }.map { it.getValue("exercise_log_id") to it.getValue("set_number") }
            .let { it.distinct().size == it.size })
        val body = buildJsonObject {
            put("p_request_id", requestId); put("p_source", source); put("p_workout", workout)
            put("p_exercises", exercises); put("p_sets", sets)
        }
        require(body.toString().toByteArray(Charsets.UTF_8).size <= 262144)
        return RpcCall("import_legacy_workout", body, serializer(), MutationIdentity(requestId, "WORKOUT", id)) {
            require(it.requestId == requestId && it.operation == "import_legacy_workout" && it.workoutId == id)
            require(it.sourceFingerprint == fingerprint && it.exerciseCount == exercises.size && it.setCount == sets.size)
            digest(it.payloadDigest); timestamp(it.committedAt)
            require(it.idMapping == JsonObject(ids.associateWith { key -> JsonPrimitive(key) }))
        }
    }

    private fun uuid(value: String) {
        require(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}").matches(value))
    }
    private fun digest(value: String) { require(Regex("[0-9a-f]{64}").matches(value)) }
    private fun timestamp(value: String) { java.time.OffsetDateTime.parse(value) }

    fun homeSummary() = read<HomeSummary>("get_home_summary", buildJsonObject {})
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
    }) {
        require((it.state == OperationState.COMMITTED) == (it.receipt != null))
        it.receipt?.let { receipt -> require(receipt.requestId == requestId); validateOperation(receipt) }
    }
    fun recentOperations(from: String, until: String, cursor: String? = null, limit: Int = 20) =
        read<RemotePage<OperationReceipt>>("list_recent_operations", buildJsonObject {
            val duration = java.time.Duration.between(java.time.OffsetDateTime.parse(from), java.time.OffsetDateTime.parse(until))
            require(!duration.isNegative && !duration.isZero && duration <= java.time.Duration.ofDays(90))
            put("p_from", from); put("p_until", until); page(cursor, limit)
        }) {
            require(it.data.size <= limit && it.pageInfo.limit == limit)
            require(it.pageInfo.hasMore == (it.pageInfo.nextCursor != null))
            it.data.forEach(::validateOperation)
        }

    private fun validateOperation(receipt: OperationReceipt) {
        val expected = when (receipt.operation) {
            "save_template_revision" -> "TEMPLATE"
            "create_active_session", "update_active_session", "finish_active_session", "cancel_active_session",
            "resolve_legacy_session" -> "SESSION"
            "import_legacy_workout" -> "WORKOUT"
            else -> throw IllegalArgumentException("Unknown receipt operation")
        }
        require(receipt.resourceType == expected)
        require(receipt.result.getValue("request_id").jsonPrimitive.content == receipt.requestId)
        val resourceField = when (expected) { "TEMPLATE" -> "template_id"; "WORKOUT" -> "workout_id"; else -> "session_id" }
        require(receipt.result.getValue(resourceField).jsonPrimitive.content == receipt.resourceId)
        timestamp(receipt.committedAt)
    }

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

package com.diajarkoding.imfit.data.remote.online

import com.diajarkoding.imfit.data.local.FakeExerciseDataSource
import com.diajarkoding.imfit.data.local.FakeWorkoutDataSource
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.UUID

/** In-memory demo server, not a network/RLS proof and never a production fallback. */
internal class DemoSessionApi(private val clock: () -> Instant = Instant::now,
    private val templateRevision: (String) -> Long = { 1L }) {
    private val snapshots = mutableMapOf<Pair<String, String>, SessionSnapshot>()
    private val receipts = mutableMapOf<Pair<String, String>, Pair<JsonObject, OperationReceipt>>()
    private val imports = mutableMapOf<Pair<String, String>, Pair<JsonObject, LegacyWorkoutImportReceipt>>()
    private val legacy = mutableMapOf<Pair<String, String>, LegacyRecoveryItem>()

    internal fun retainLegacy(owner: String, item: LegacyRecoveryItem) {
        require(item.rawSource.getValue("user_id").jsonPrimitive.content == owner)
        legacy[owner to item.sessionId] = item
    }

    internal fun retainSession(owner: String, snapshot: SessionSnapshot) {
        snapshots[owner to snapshot.id] = snapshot
    }

    internal fun importedWorkouts(owner: String): List<WorkoutDetail> = imports.filterKeys { it.first == owner }.values
        .distinctBy { it.second.workoutId }.mapNotNull { (payload, receipt) ->
            val parent = payload.getValue("p_workout").jsonObject
            if (parent["deleted_at"] != null && parent["deleted_at"] != JsonNull) return@mapNotNull null
            fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
            val sets = payload.getValue("p_sets").jsonArray.map { it.jsonObject }
            val children = payload.getValue("p_exercises").jsonArray.map { childElement ->
                val child = childElement.jsonObject
                ExerciseLog(child.text("id"), child.text("exercise_id"), child.text("exercise_name"), child.text("muscle_category"),
                    child.text("order_index").toInt(), child["input_defaults"]?.takeUnless { it == JsonNull }?.let { onlineJson.decodeFromJsonElement<InputDefaults>(it) },
                    child.text("total_volume"), child.text("total_sets").toLong(), child.text("total_reps").toLong(),
                    sets.filter { it.text("exercise_log_id") == child.text("id") }.map {
                        WorkoutSet(it.text("id"), it.text("set_number").toInt(), it.text("weight"), it.text("reps").toInt(), it.getValue("is_completed").jsonPrimitive.boolean)
                    })
            }
            WorkoutDetail(receipt.workoutId, parent["template_id"]?.jsonPrimitive?.contentOrNull, parent.text("template_name"),
                parent.text("start_time"), parent.text("end_time"),
                java.time.Duration.between(Instant.parse(parent.text("start_time")), Instant.parse(parent.text("end_time"))).seconds,
                parent.text("total_volume"), parent.text("total_sets").toLong(), parent.text("total_reps").toLong(), children)
        }

    private fun effective(value: SessionSnapshot): SessionSnapshot =
        if (value.status in setOf(SessionStatus.ACTIVE, SessionStatus.PAUSED) && Instant.parse(value.expiresAt) <= clock())
            value.copy(effectiveStatus = SessionStatus.EXPIRED, reviewRequired = true) else value
    fun read(owner: String, name: String, body: JsonObject): JsonElement {
        fun text(key: String) = body[key]?.jsonPrimitive?.contentOrNull
        return when (name) {
            "get_start_recovery_status" -> {
                require(body.getValue("p_contract_version").jsonPrimitive.int == 1)
                val blockers = legacy.filterKeys { it.first == owner }.values.filter { it.resolution == null }.map {
                    StartRecoveryBlocker(RecoveryBlockerKind.LEGACY_SESSION, it.sessionId, null, it.sourceFingerprint, RecoveryBlockerReason.LEGACY_REVIEW_REQUIRED)
                } + snapshots.filterKeys { it.first == owner }.values.map(::effective).filter {
                    it.effectiveStatus in setOf(SessionStatus.ACTIVE, SessionStatus.PAUSED) || it.reviewRequired
                }.map {
                    StartRecoveryBlocker(if (it.effectiveStatus == SessionStatus.EXPIRED) RecoveryBlockerKind.EXPIRED_SESSION else RecoveryBlockerKind.ACTIVE_SESSION,
                        it.id, it.version, null, RecoveryBlockerReason.SESSION_REVIEW_REQUIRED)
                }
                onlineJson.encodeToJsonElement(StartRecoveryStatus(1, clock().toString(), blockers.isEmpty(),
                    blockers.sortedBy { it.resourceId }.take(20), blockers.size > 20))
            }
            "get_legacy_recovery_page" -> {
                val limit = body.getValue("p_limit").jsonPrimitive.int.also { require(it in 1..100) }
                val cursor = text("p_cursor")?.let {
                    val decoded = String(java.util.Base64.getUrlDecoder().decode(it), Charsets.UTF_8)
                    require(decoded.startsWith("$owner|legacy|"))
                    decoded.removePrefix("$owner|legacy|")
                }
                val rows = legacy.filterKeys { it.first == owner }.values.sortedBy { it.sessionId }.filter { cursor == null || it.sessionId > cursor }
                val selected = rows.take(limit)
                val next = if (rows.size > limit) java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString("$owner|legacy|${selected.last().sessionId}".toByteArray(Charsets.UTF_8)) else null
                onlineJson.encodeToJsonElement(RemotePage(selected, PageInfo(next, next != null, limit)))
            }
            "get_active_session" -> onlineJson.encodeToJsonElement(SessionLookup(
                session(owner, text("p_session_id")) != null, session(owner, text("p_session_id"))))
            "get_operation_status" -> onlineJson.encodeToJsonElement(receipts[owner to text("p_request_id")]?.second.let {
                OperationLookup(if (it == null) OperationState.NOT_FOUND else OperationState.COMMITTED, it)
            })
            "list_recent_operations" -> onlineJson.encodeToJsonElement(RemotePage(
                receipts.filterKeys { it.first == owner }.values.map { it.second }
                    .filter { Instant.parse(it.committedAt) >= Instant.parse(text("p_from")) && Instant.parse(it.committedAt) < Instant.parse(text("p_until")) }
                    .sortedByDescending { it.committedAt }.take(body.getValue("p_limit").jsonPrimitive.int),
                PageInfo(null, false, body.getValue("p_limit").jsonPrimitive.int)))
            else -> throw OnlineFailure(RemoteError.ContractUnavailable)
        }
    }
    private fun session(owner: String, id: String?) = (if (id != null) snapshots[owner to id]
        else snapshots.filterKeys { it.first == owner }.values.sortedBy { it.id }.firstOrNull {
            effective(it).effectiveStatus in setOf(SessionStatus.ACTIVE, SessionStatus.PAUSED) || effective(it).reviewRequired
        })?.let(::effective)

    fun post(owner: String, name: String, body: JsonObject): RpcResponse = try {
        postValidated(owner, name, body)
    } catch (_: IllegalArgumentException) {
        fail("VALIDATION", 400)
    } catch (_: NoSuchElementException) {
        fail("VALIDATION", 400)
    }

    private fun postValidated(owner: String, name: String, body: JsonObject): RpcResponse {
        val request = body.getValue("p_request_id").jsonPrimitive.content
        receipts[owner to request]?.let { (old, receipt) ->
            if (old != body || receipt.operation != name) return fail("IDEMPOTENCY_MISMATCH")
            return RpcResponse(200, receipt.result.toString())
        }
        if (name == "resolve_legacy_session") return resolveLegacy(owner, body)
        if (name == "import_legacy_workout") return importLegacy(owner, body)
        val id = body.getValue("p_session_id").jsonPrimitive.content
        val now = clock()
        val old = snapshots[owner to id]?.let(::effective)
        var workoutId: String? = null
        val next = if (name == "create_active_session") {
            if (session(owner, null) != null || old != null || legacy.any { it.key.first == owner && it.value.resolution == null }) return fail("ACTIVE_SESSION_EXISTS")
            val template = FakeWorkoutDataSource.getTemplateById(body.getValue("p_template_id").jsonPrimitive.content)?.takeIf { it.userId == owner }
                ?: return fail("NOT_FOUND", 404)
            if (body.getValue("p_expected_template_revision").jsonPrimitive.long != templateRevision(template.id)) return fail("REVISION_CONFLICT")
            SessionSnapshot(id, 1, SessionStatus.ACTIVE, SessionStatus.ACTIVE, false, template.id, template.name,
                body.getValue("p_expected_template_revision").jsonPrimitive.long, now.toString(), null, 0, now.toString(),
                now.plusSeconds(30L * 86400).toString(), 1, null, template.exercises.mapIndexed { index, child ->
                    SessionExercise(UUID.randomUUID().toString(), child.id, child.name, index, child.restSeconds,
                        InputDefaults(child.sets, child.reps), (1..child.sets).map { number -> WorkoutSet(UUID.randomUUID().toString(), number, "0.00", child.reps, false) }, child.exercise.muscleCategory.displayName)
                }, null)
        } else {
            if (old == null) return fail("NOT_FOUND", 404)
            if (old.version != body.getValue("p_expected_version").jsonPrimitive.long) return fail("VERSION_CONFLICT")
            val ack = name == "update_active_session" &&
                body.getValue("p_command").jsonObject["type"]?.jsonPrimitive?.content == "acknowledge_expiry"
            if (old.reviewRequired && !ack) return fail("SESSION_REVIEW_REQUIRED")
            if (ack && old.effectiveStatus != SessionStatus.EXPIRED) return fail("VALIDATION", 400)
            if (!ack && old.effectiveStatus !in setOf(SessionStatus.ACTIVE, SessionStatus.PAUSED)) return fail("SESSION_TERMINAL")
            when (name) {
                "update_active_session" -> {
                    val command = onlineJson.decodeFromJsonElement<SessionCommand>(body.getValue("p_command"))
                    apply(old, command, now)
                }
                "finish_active_session" -> {
                    if (old.exercises.none { child -> child.sets.any { it.isCompleted } }) return fail("NO_COMPLETED_SETS", 400)
                    workoutId = id // Stable demo session→workout mapping.
                    val domain = old.toDomain()
                    val exercises = domain.exerciseLogs.mapNotNull { e -> e.sets.filter { it.isCompleted }.takeIf { it.isNotEmpty() }?.let { e.copy(sets = it) } }
                    FakeWorkoutDataSource.addConfirmedWorkout(com.diajarkoding.imfit.domain.model.WorkoutLog(id, owner, old.templateName,
                        domain.startTime, domain.startTime, now.toEpochMilli(), exercises.sumOf { it.totalVolume.toDouble() }.toFloat(), exercises))
                    terminal(old, SessionStatus.COMPLETED, now).copy(completedWorkoutId = workoutId)
                }
                "cancel_active_session" -> terminal(old, SessionStatus.CANCELLED, now)
                else -> return fail("CONTRACT_UNAVAILABLE", 404)
            }.copy(version = old.version + 1, lastActivityAt = now.toString())
        }
        snapshots[owner to id] = next
        val receipt = SessionMutationReceipt(request, name, id, next.version, next.status, now.toString(), next, workoutId)
        val encoded = onlineJson.encodeToJsonElement(receipt).jsonObject
        receipts[owner to request] = body to OperationReceipt(request, name, "SESSION", id, now.toString(), CommitTimeSource.COMMIT, encoded)
        return RpcResponse(200, encoded.toString())
    }
    private fun terminal(old: SessionSnapshot, status: SessionStatus, now: Instant) =
        old.copy(status = status, effectiveStatus = status, pausedAt = null,
            totalPausedMs = old.totalPausedMs + (old.pausedAt?.let { java.time.Duration.between(Instant.parse(it), now).toMillis() } ?: 0))
    private fun apply(old: SessionSnapshot, command: SessionCommand, now: Instant): SessionSnapshot = when (command) {
        SessionCommand.Pause -> {
            require(old.effectiveStatus == SessionStatus.ACTIVE)
            old.copy(status = SessionStatus.PAUSED, effectiveStatus = SessionStatus.PAUSED, pausedAt = now.toString())
        }
        SessionCommand.Resume -> {
            require(old.effectiveStatus == SessionStatus.PAUSED)
            old.copy(status = SessionStatus.ACTIVE, effectiveStatus = SessionStatus.ACTIVE, pausedAt = null,
                totalPausedMs = old.totalPausedMs + (old.pausedAt?.let { java.time.Duration.between(Instant.parse(it), now).toMillis() } ?: 0))
        }
        SessionCommand.AcknowledgeExpiry -> terminal(old, SessionStatus.EXPIRED, now).copy(reviewRequired = false)
        is SessionCommand.SelectExercise -> {
            require(old.exercises.any { it.id == command.exerciseLogId })
            old.copy(currentExerciseId = command.exerciseLogId)
        }
        is SessionCommand.AddExercise -> {
            val exercise = requireNotNull(FakeExerciseDataSource.getExerciseById(command.exerciseId))
            require(old.exercises.none { it.exerciseId == exercise.id })
            old.copy(exercises = old.exercises + SessionExercise(command.exerciseLogId, exercise.id, exercise.name, old.exercises.size, 60,
                InputDefaults(3, 10), (1..3).map { WorkoutSet(UUID.randomUUID().toString(), it, "0.00", 10, false) }, exercise.muscleCategory.displayName))
        }
        else -> {
            val exerciseId = when (command) {
                is SessionCommand.EditSet -> command.exerciseLogId
                is SessionCommand.CompleteSet -> command.exerciseLogId
                is SessionCommand.UncompleteSet -> command.exerciseLogId
                is SessionCommand.AddSet -> command.exerciseLogId
                is SessionCommand.RemoveSet -> command.exerciseLogId
                is SessionCommand.RemoveExercise -> command.exerciseLogId
                is SessionCommand.SetRestSeconds -> command.exerciseLogId
                else -> error("Unsupported demo command")
            }
            val child = requireNotNull(old.exercises.singleOrNull { it.id == exerciseId })
            val setId = when (command) {
                is SessionCommand.EditSet -> command.setId
                is SessionCommand.CompleteSet -> command.setId
                is SessionCommand.UncompleteSet -> command.setId
                is SessionCommand.RemoveSet -> command.setId
                else -> null
            }
            if (setId != null) require(child.sets.any { it.id == setId })
            if (command is SessionCommand.RemoveSet) require(child.sets.single { it.id == command.setId }.isCompleted.not())
            if (command is SessionCommand.SetRestSeconds) require(command.restSeconds in 0..600)
            if (command is SessionCommand.AddSet) require(old.exercises.none { e -> e.sets.any { it.id == command.setId } })
            old.copy(exercises = old.exercises.mapNotNull { child ->
            if (child.id != exerciseId) child else when (command) {
                is SessionCommand.RemoveExercise -> { require(child.sets.none { it.isCompleted }); null }
                is SessionCommand.SetRestSeconds -> child.copy(restSeconds = command.restSeconds)
                is SessionCommand.AddSet -> child.copy(sets = child.sets + WorkoutSet(command.setId, (child.sets.maxOfOrNull { it.setNumber } ?: 0) + 1, "0.00", child.inputDefaults.reps, false))
                is SessionCommand.RemoveSet -> child.copy(sets = child.sets.filterNot { it.id == command.setId && !it.isCompleted })
                else -> child.copy(sets = child.sets.map { set -> when {
                    command is SessionCommand.EditSet && set.id == command.setId -> { require(!set.isCompleted); set.copy(weight = command.weight, reps = command.reps) }
                    command is SessionCommand.CompleteSet && set.id == command.setId -> { require(set.weight.toBigDecimal().signum() > 0 && set.reps > 0); set.copy(isCompleted = true) }
                    command is SessionCommand.UncompleteSet && set.id == command.setId -> set.copy(isCompleted = false)
                    else -> set
                } })
            }
            }).let { updated ->
                updated.copy(currentExerciseId = updated.currentExerciseId?.takeIf { id -> updated.exercises.any { it.id == id } })
            }
        }
    }
    private fun resolveLegacy(owner: String, body: JsonObject): RpcResponse {
        val request = body.getValue("p_request_id").jsonPrimitive.content
        val id = body.getValue("p_session_id").jsonPrimitive.content
        val old = legacy[owner to id] ?: return fail("RESOURCE_NOT_FOUND", 404)
        if (body.getValue("p_resolution").jsonPrimitive.content != "KEEP_ARCHIVED") return fail("VALIDATION", 400)
        if (body.getValue("p_expected_source_fingerprint").jsonPrimitive.content != old.sourceFingerprint) return fail("LEGACY_SOURCE_CHANGED")
        val result = LegacySessionResolutionReceipt(request, "resolve_legacy_session", id, old.sourceFingerprint,
            LegacyResolution.KEEP_ARCHIVED, clock().toString())
        legacy[owner to id] = old.copy(resolution = LegacyResolution.KEEP_ARCHIVED)
        return record(owner, request, "resolve_legacy_session", "SESSION", id, body, onlineJson.encodeToJsonElement(result).jsonObject)
    }

    private fun importLegacy(owner: String, body: JsonObject): RpcResponse {
        val request = body.getValue("p_request_id").jsonPrimitive.content
        val source = body.getValue("p_source").jsonObject
        val workout = body.getValue("p_workout").jsonObject
        val exercises = body.getValue("p_exercises").jsonArray
        val sets = body.getValue("p_sets").jsonArray
        val id = source.getValue("id").jsonPrimitive.content
        if (workout["user_id"]?.jsonPrimitive?.content != owner) return fail("RESOURCE_NOT_FOUND", 404)
        try { OnlineRpc.importLegacyWorkout(request, source, workout, exercises, sets) }
        catch (_: IllegalArgumentException) { return fail("VALIDATION", 400) }
        val key = owner to "${source.getValue("schema_version")}:$id:${source.getValue("mapping_version")}"
        val normalized = JsonObject((body - "p_request_id") + mapOf(
            "p_workout" to JsonObject(mapOf("template_id" to JsonNull, "deleted_at" to JsonNull) + workout),
            "p_exercises" to JsonArray(exercises.map { JsonObject(mapOf("input_defaults" to JsonNull) + it.jsonObject) })))
        val payload = canonical(normalized).jsonObject
        imports[key]?.let { (previous, receipt) ->
            if (previous != payload) return fail("LEGACY_IMPORT_CONFLICT")
            return record(owner, request, "import_legacy_workout", "WORKOUT", id, body,
                onlineJson.encodeToJsonElement(receipt.copy(requestId = request, committedAt = clock().toString())).jsonObject)
        }
        val templateId = workout["template_id"]?.jsonPrimitive?.contentOrNull
        if (templateId != null && FakeWorkoutDataSource.getTemplateById(templateId)?.userId != owner) return fail("LEGACY_REVIEW_REQUIRED")
        if (exercises.any { FakeExerciseDataSource.getExerciseById(it.jsonObject.getValue("exercise_id").jsonPrimitive.content) == null })
            return fail("LEGACY_REVIEW_REQUIRED")
        FakeWorkoutDataSource.getWorkoutLogById(id)?.let {
            return fail(if (it.userId == owner) "LEGACY_IMPORT_CONFLICT" else "RESOURCE_NOT_FOUND", if (it.userId == owner) 409 else 404)
        }
        if (imports.any { (entry, value) -> entry.first != owner && value.second.workoutId == id }) return fail("RESOURCE_NOT_FOUND", 404)
        val matching = imports.entries.firstOrNull { it.key.first == owner && it.value.second.workoutId == id }
        if (matching != null) {
            if (JsonObject(matching.value.first - "p_source") != JsonObject(payload - "p_source")) return fail("LEGACY_IMPORT_CONFLICT")
            val receipt = matching.value.second.copy(requestId = request,
                sourceFingerprint = source.getValue("fingerprint").jsonPrimitive.content,
                outcome = LegacyImportOutcome.MATCHED, committedAt = clock().toString())
            imports[key] = payload to receipt
            return record(owner, request, "import_legacy_workout", "WORKOUT", id, body, onlineJson.encodeToJsonElement(receipt).jsonObject)
        }
        val exerciseIds = exercises.map { it.jsonObject.getValue("id").jsonPrimitive.content }.toSet()
        if (exercises.any { it.jsonObject["workout_log_id"]?.jsonPrimitive?.content != id } ||
            sets.any { it.jsonObject["workout_log_id"]?.jsonPrimitive?.content != id || it.jsonObject["exercise_log_id"]?.jsonPrimitive?.content !in exerciseIds })
            return fail("VALIDATION", 400)
        require(exercises.map { it.jsonObject.getValue("order_index").jsonPrimitive.int }.distinct().size == exercises.size)
        require(sets.map { it.jsonObject.getValue("exercise_log_id") to it.jsonObject.getValue("set_number") }.distinct().size == sets.size)
        sets.forEach {
            val row = it.jsonObject
            require(row.getValue("weight").jsonPrimitive.content.toBigDecimal() in java.math.BigDecimal.ZERO..java.math.BigDecimal(1000))
            require(row.getValue("reps").jsonPrimitive.int in 0..1000)
            require(row.getValue("set_number").jsonPrimitive.int > 0)
            require(exercises.single { exercise -> exercise.jsonObject.getValue("id") == row.getValue("exercise_log_id") }
                .jsonObject.getValue("exercise_id") == row.getValue("exercise_id"))
        }
        val allIds = listOf(id) + exerciseIds + sets.map { it.jsonObject.getValue("id").jsonPrimitive.content }
        if (imports.values.any { (_, receipt) -> receipt.idMapping.keys.any { it in allIds } }) return fail("LEGACY_IMPORT_CONFLICT")
        val completed = sets.map { it.jsonObject }.filter { it.getValue("is_completed").jsonPrimitive.boolean }
        fun totals(row: JsonObject, children: List<JsonObject>): Boolean {
            val volume = children.fold(java.math.BigDecimal.ZERO) { sum, set ->
                sum + set.getValue("weight").jsonPrimitive.content.toBigDecimal() * set.getValue("reps").jsonPrimitive.int.toBigDecimal()
            }
            return row.getValue("total_sets").jsonPrimitive.int == children.size &&
                row.getValue("total_reps").jsonPrimitive.int == children.sumOf { it.getValue("reps").jsonPrimitive.int } &&
                row.getValue("total_volume").jsonPrimitive.content.toBigDecimal().compareTo(volume) == 0
        }
        if (!totals(workout, completed) || exercises.any { exercise ->
                !totals(exercise.jsonObject, completed.filter { it.getValue("exercise_log_id") == exercise.jsonObject.getValue("id") })
            }) return fail("LEGACY_REVIEW_REQUIRED")
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(payload.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val receipt = LegacyWorkoutImportReceipt(request, "import_legacy_workout", id,
            source.getValue("fingerprint").jsonPrimitive.content, digest, LegacyImportOutcome.IMPORTED,
            JsonObject(allIds.associateWith { JsonPrimitive(it) }), exercises.size, sets.size, clock().toString())
        imports[key] = payload to receipt
        return record(owner, request, "import_legacy_workout", "WORKOUT", id, body, onlineJson.encodeToJsonElement(receipt).jsonObject)
    }

    private fun canonical(value: JsonElement, key: String = ""): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.toSortedMap().mapValues { (field, item) -> canonical(item, field) })
        is JsonArray -> {
            val values = value.map { canonical(it) }
            JsonArray(when (key) {
                "p_exercises" -> values.sortedWith(compareBy<JsonElement> { it.jsonObject.getValue("order_index").jsonPrimitive.int }
                    .thenBy { it.jsonObject.getValue("id").jsonPrimitive.content })
                "p_sets" -> values.sortedWith(compareBy<JsonElement> { it.jsonObject.getValue("exercise_log_id").jsonPrimitive.content }
                    .thenBy { it.jsonObject.getValue("set_number").jsonPrimitive.int }.thenBy { it.jsonObject.getValue("id").jsonPrimitive.content })
                else -> values
            })
        }
        is JsonPrimitive -> when {
            value == JsonNull -> value
            key in setOf("weight", "total_volume") -> JsonPrimitive(value.content.toBigDecimal().stripTrailingZeros().toPlainString())
            key in setOf("start_time", "end_time", "created_at", "updated_at", "deleted_at") ->
                JsonPrimitive(java.time.OffsetDateTime.parse(value.content).toInstant().toString())
            else -> value
        }
    }

    private fun record(owner: String, request: String, operation: String, resource: String, id: String,
        body: JsonObject, result: JsonObject): RpcResponse {
        receipts[owner to request] = body to OperationReceipt(request, operation, resource, id, clock().toString(), CommitTimeSource.COMMIT, result)
        return RpcResponse(200, result.toString())
    }
    private fun fail(code: String, status: Int = 409) = RpcResponse(status, onlineJson.encodeToString(PostgrestError("PT$status", code, null, null)))
}

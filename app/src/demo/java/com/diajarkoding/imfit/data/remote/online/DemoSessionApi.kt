package com.diajarkoding.imfit.data.remote.online

import com.diajarkoding.imfit.data.local.FakeExerciseDataSource
import com.diajarkoding.imfit.data.local.FakeWorkoutDataSource
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.UUID

/** In-memory demo server, not a network/RLS proof and never a production fallback. */
internal class DemoSessionApi {
    private val snapshots = mutableMapOf<Pair<String, String>, SessionSnapshot>()
    private val receipts = mutableMapOf<Pair<String, String>, Pair<JsonObject, OperationReceipt>>()
    fun read(owner: String, name: String, body: JsonObject): JsonElement {
        fun text(key: String) = body[key]?.jsonPrimitive?.contentOrNull
        return when (name) {
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
    private fun session(owner: String, id: String?) = if (id != null) snapshots[owner to id]
        else snapshots.filterKeys { it.first == owner }.values.singleOrNull { it.status in setOf(SessionStatus.ACTIVE, SessionStatus.PAUSED) }

    fun post(owner: String, name: String, body: JsonObject): RpcResponse {
        val request = body.getValue("p_request_id").jsonPrimitive.content
        receipts[owner to request]?.let { (old, receipt) ->
            if (old != body || receipt.operation != name) return fail("IDEMPOTENCY_MISMATCH")
            return RpcResponse(200, receipt.result.toString())
        }
        val id = body.getValue("p_session_id").jsonPrimitive.content
        val now = Instant.now()
        val old = snapshots[owner to id]
        var workoutId: String? = null
        val next = if (name == "create_active_session") {
            if (session(owner, null) != null || old != null) return fail("ACTIVE_SESSION_EXISTS")
            val template = FakeWorkoutDataSource.getTemplateById(body.getValue("p_template_id").jsonPrimitive.content)?.takeIf { it.userId == owner }
                ?: return fail("NOT_FOUND", 404)
            SessionSnapshot(id, 1, SessionStatus.ACTIVE, SessionStatus.ACTIVE, false, template.id, template.name,
                body.getValue("p_expected_template_revision").jsonPrimitive.long, now.toString(), null, 0, now.toString(),
                now.plusSeconds(30L * 86400).toString(), 1, null, template.exercises.mapIndexed { index, child ->
                    SessionExercise(UUID.randomUUID().toString(), child.id, child.name, index, child.restSeconds,
                        InputDefaults(child.sets, child.reps), (1..child.sets).map { number -> WorkoutSet(UUID.randomUUID().toString(), number, "0.00", child.reps, false) }, child.exercise.muscleCategory.displayName)
                }, null)
        } else {
            if (old == null) return fail("NOT_FOUND", 404)
            if (old.version != body.getValue("p_expected_version").jsonPrimitive.long) return fail("VERSION_CONFLICT")
            if (old.status !in setOf(SessionStatus.ACTIVE, SessionStatus.PAUSED)) return fail("SESSION_TERMINAL")
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
                    old.copy(status = SessionStatus.COMPLETED, effectiveStatus = SessionStatus.COMPLETED, pausedAt = null, completedWorkoutId = workoutId)
                }
                "cancel_active_session" -> old.copy(status = SessionStatus.CANCELLED, effectiveStatus = SessionStatus.CANCELLED, pausedAt = null)
                else -> return fail("CONTRACT_UNAVAILABLE", 404)
            }.copy(version = old.version + 1, lastActivityAt = now.toString())
        }
        snapshots[owner to id] = next
        val receipt = SessionMutationReceipt(request, name, id, next.version, next.status, now.toString(), next, workoutId)
        val encoded = onlineJson.encodeToJsonElement(receipt).jsonObject
        receipts[owner to request] = body to OperationReceipt(request, name, "SESSION", id, now.toString(), CommitTimeSource.COMMIT, encoded)
        return RpcResponse(200, encoded.toString())
    }
    private fun apply(old: SessionSnapshot, command: SessionCommand, now: Instant): SessionSnapshot = when (command) {
        SessionCommand.Pause -> old.copy(status = SessionStatus.PAUSED, effectiveStatus = SessionStatus.PAUSED, pausedAt = now.toString())
        SessionCommand.Resume -> old.copy(status = SessionStatus.ACTIVE, effectiveStatus = SessionStatus.ACTIVE, pausedAt = null,
            totalPausedMs = old.totalPausedMs + (old.pausedAt?.let { java.time.Duration.between(Instant.parse(it), now).toMillis() } ?: 0))
        SessionCommand.AcknowledgeExpiry -> old.copy(reviewRequired = false)
        is SessionCommand.SelectExercise -> old.copy(currentExerciseId = command.exerciseLogId)
        is SessionCommand.AddExercise -> {
            val exercise = FakeExerciseDataSource.getExerciseById(command.exerciseId) ?: error("Unknown demo exercise")
            require(old.exercises.none { it.exerciseId == exercise.id })
            old.copy(exercises = old.exercises + SessionExercise(command.exerciseLogId, exercise.id, exercise.name, old.exercises.size, 60,
                InputDefaults(3, 10), (1..3).map { WorkoutSet(UUID.randomUUID().toString(), it, "0.00", 10, false) }, exercise.muscleCategory.displayName))
        }
        else -> old.copy(exercises = old.exercises.mapNotNull { child ->
            val target = when (command) {
                is SessionCommand.EditSet -> command.exerciseLogId
                is SessionCommand.CompleteSet -> command.exerciseLogId
                is SessionCommand.UncompleteSet -> command.exerciseLogId
                is SessionCommand.AddSet -> command.exerciseLogId
                is SessionCommand.RemoveSet -> command.exerciseLogId
                is SessionCommand.RemoveExercise -> command.exerciseLogId
                is SessionCommand.SetRestSeconds -> command.exerciseLogId
                else -> error("Unsupported demo command")
            }
            if (child.id != target) child else when (command) {
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
        })
    }
    private fun fail(code: String, status: Int = 409) = RpcResponse(status, onlineJson.encodeToString(PostgrestError("PT$status", code, null, null)))
}

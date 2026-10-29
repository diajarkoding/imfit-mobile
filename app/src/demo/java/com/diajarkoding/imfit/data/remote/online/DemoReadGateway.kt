package com.diajarkoding.imfit.data.remote.online

import com.diajarkoding.imfit.data.local.FakeExerciseDataSource
import com.diajarkoding.imfit.data.local.FakeUserDataSource
import com.diajarkoding.imfit.data.local.FakeWorkoutDataSource
import com.diajarkoding.imfit.domain.model.WorkoutLog
import com.diajarkoding.imfit.domain.repository.AuthRepository
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.time.*
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/** Deterministic demo-only remote contract adapter. Never reachable in production DI. */
@Singleton
class DemoReadGateway @Inject constructor(authRepository: AuthRepository) : RemoteReadGateway {
    private val revisions = mutableMapOf<String, Long>()
    private val receipts = mutableMapOf<String, Pair<com.diajarkoding.imfit.data.remote.TemplateWriteRequest, com.diajarkoding.imfit.data.remote.TemplateWriteReceipt>>()
    override val auth = OnlineAuth()
    private val sessions = DemoSessionApi(templateRevision = { revisions[it] ?: 1L })
    internal val repository: OnlineRepository = OnlineRepository(OnlineTransport { name, body, credential ->
        sessions.post(credential.identity.ownerId, name, body)
    }, auth)
    private var observedEpoch = -1L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    init {
        auth.synchronizeSession = ::synchronize
        synchronize()
        scope.launch { FakeUserDataSource.sessionEpoch.collect { synchronize() } }
    }
    private fun synchronize() = auth.synchronizedState {
        val epoch = FakeUserDataSource.sessionEpoch.value
        if (epoch == observedEpoch) return@synchronizedState
        observedEpoch = epoch
        val user = FakeUserDataSource.getCurrentUser()
        if (user == null) auth.signOut() else auth.signIn(user.id, "synthetic-demo-token")
    }
    override suspend fun profile(): RemoteProfile {
        auth.capture()
        val user = FakeUserDataSource.getCurrentUser() ?: throw OnlineFailure(RemoteError.Unauthenticated)
        return RemoteProfile(user.id, user.name, user.email, user.birthDate, user.profilePhotoUri)
    }
    override suspend fun updateProfile(patch: com.diajarkoding.imfit.domain.model.ProfilePatch): RemoteProfile {
        val identity = auth.capture().identity
        FakeUserDataSource.updateProfile(patch).getOrThrow()
        if (!auth.isCurrent(identity)) throw StaleAuthException()
        return profile()
    }
    override suspend fun exercises(ids: List<String>) = ids.distinct().mapNotNull { FakeExerciseDataSource.getExerciseById(it) }
    override suspend fun <T> read(call: RpcCall<T>): T {
        require(call.mutation == null)
        val owner = auth.capture().identity.ownerId
        val p = call.parameters
        fun text(key: String) = p[key]?.jsonPrimitive?.contentOrNull
        fun number(key: String) = p.getValue(key).jsonPrimitive.int
        fun summaries(): List<WorkoutSummary> = FakeWorkoutDataSource.getWorkoutLogs(owner).map {
            onlineJson.decodeFromJsonElement<WorkoutSummary>(summary(it))
        } + sessions.importedWorkouts(owner).map {
            WorkoutSummary(it.id, it.templateId, it.templateName, it.startTime, it.endTime, it.durationSeconds,
                it.totalVolume, it.totalSets, it.totalReps)
        }
        fun window(): List<WorkoutSummary> {
            val zone = ZoneId.of(text("p_timezone"))
            val start = LocalDate.parse(text("p_start_date")).atStartOfDay(zone).toInstant()
            val end = LocalDate.parse(text("p_end_date")).atStartOfDay(zone).toInstant()
            return summaries().filter { Instant.parse(it.startTime) >= start && Instant.parse(it.startTime) < end }
        }
        val result: JsonElement = when (call.name) {
            "get_exercise_catalog_page" -> page(call, FakeExerciseDataSource.exercises
                .filter { (text("p_category_id") == null || it.muscleCategory.ordinal + 1 == number("p_category_id")) && it.name.lowercase().contains(text("p_search").orEmpty()) }
                .sortedWith(compareBy<com.diajarkoding.imfit.domain.model.Exercise> { it.name.lowercase() }.thenBy { it.id })
                .map { onlineJson.encodeToJsonElement(ExerciseSummary(it.id, it.muscleCategory.ordinal + 1, it.name, it.imageUrl, null, null)) })
            "get_template_page" -> page(call, FakeWorkoutDataSource.getTemplates(owner).sortedByDescending { it.id }
                .map { onlineJson.encodeToJsonElement(TemplateSummary(it.id, it.name, revisions[it.id] ?: 1, it.exerciseCount, Instant.ofEpochMilli(it.createdAt).toString())) })
            "get_template_detail" -> {
                val template = FakeWorkoutDataSource.getTemplateById(text("p_template_id").orEmpty())?.takeIf { it.userId == owner }
                    ?: throw OnlineFailure(RemoteError.NotFound)
                onlineJson.encodeToJsonElement(TemplateDetail(template.id, template.name, revisions[template.id] ?: 1, Instant.ofEpochMilli(template.createdAt).toString(),
                    template.exercises.mapIndexed { index, child -> TemplateChild("${template.id}-$index", child.id, index, child.sets, child.reps, child.restSeconds) }))
            }
            "get_workout_history_page" -> page(call, window().sortedWith(compareByDescending<WorkoutSummary> { it.endTime }.thenByDescending { it.id }).map { onlineJson.encodeToJsonElement(it) })
            "get_progress_summary" -> {
                val logs = window()
                onlineJson.encodeToJsonElement(ProgressSummary(text("p_start_date")!!, text("p_end_date")!!, text("p_timezone")!!,
                    logs.size.toLong(), logs.fold(java.math.BigDecimal.ZERO) { a, l -> a + l.totalVolume.toString().toBigDecimal() }.toPlainString(),
                    logs.sumOf { it.totalSets }, logs.sumOf { it.totalReps }, logs.sumOf { it.durationSeconds }))
            }
            "get_workout_date_markers" -> onlineJson.encodeToJsonElement(DateMarkers(text("p_start_date")!!, text("p_end_date")!!, text("p_timezone")!!,
                window().groupingBy { Instant.parse(it.startTime).atZone(ZoneId.of(text("p_timezone"))).toLocalDate() }.eachCount()
                    .toSortedMap().map { DateMarker(it.key.toString(), it.value.toLong()) }))
            "get_workout_detail" -> {
                val imported = sessions.importedWorkouts(owner).firstOrNull { it.id == text("p_workout_id") }
                if (imported != null) onlineJson.encodeToJsonElement(imported) else {
                val log = FakeWorkoutDataSource.getWorkoutLogById(text("p_workout_id").orEmpty())?.takeIf { it.userId == owner } ?: throw OnlineFailure(RemoteError.NotFound)
                JsonObject(summary(log).jsonObject + ("exercise_logs" to JsonArray(log.exerciseLogs.mapIndexed { index, exercise ->
                    onlineJson.encodeToJsonElement(ExerciseLog("${log.id}-$index", exercise.exercise.id, exercise.exercise.name, exercise.exercise.muscleCategory.displayName,
                        index, null, exercise.totalVolume.toString(), exercise.sets.count { it.isCompleted }.toLong(), exercise.sets.filter { it.isCompleted }.sumOf { it.reps.toLong() },
                        exercise.sets.filter { it.isCompleted }.map { WorkoutSet("${log.id}-$index-${it.setNumber}", it.setNumber, it.weight.toString(), it.reps, true) }))
                })))
                }
            }
            "get_active_session", "get_operation_status", "list_recent_operations",
            "get_start_recovery_status", "get_legacy_recovery_page" -> sessions.read(owner, call.name, p)
            "get_exercise_prefill" -> {
                val defaults = onlineJson.decodeFromJsonElement<InputDefaults>(p.getValue("p_input_defaults"))
                onlineJson.encodeToJsonElement(ExercisePrefill(PrefillSource.DEFAULTS, null, defaults,
                    (1..defaults.sets).map { PrefillSet(it, "0.00", defaults.reps) }))
            }
            "get_home_summary" -> onlineJson.encodeToJsonElement(HomeSummary(summaries()
                .maxWithOrNull(compareBy<WorkoutSummary> { it.endTime }.thenBy { it.id })))
            else -> throw OnlineFailure(RemoteError.ContractUnavailable)
        }
        return onlineJson.decodeFromJsonElement(call.serializer, result).also(call.validate)
    }
    override suspend fun saveTemplate(request: com.diajarkoding.imfit.data.remote.TemplateWriteRequest): com.diajarkoding.imfit.data.remote.TemplateWriteReceipt {
        val identity = auth.capture().identity
        require(identity.ownerId == request.ownerId)
        val key = identity.ownerId + ":" + request.requestId
        receipts[key]?.let { (old, receipt) ->
            if (old != request) throw OnlineFailure(RemoteError.IdempotencyMismatch)
            return receipt
        }
        val old = FakeWorkoutDataSource.getTemplateById(request.templateId)?.takeIf { it.userId == identity.ownerId }
        if ((old == null) != (request.expectedRevision == null) || (old != null && (revisions[old.id] ?: 1) != request.expectedRevision)) {
            throw OnlineFailure(RemoteError.Conflict("REVISION_CONFLICT", null))
        }
        val children = request.exercises.map { child -> com.diajarkoding.imfit.domain.model.TemplateExercise(
            FakeExerciseDataSource.getExerciseById(child.exerciseId) ?: throw OnlineFailure(RemoteError.Validation("VALIDATION", null)), child.sets, child.reps, child.restSeconds) }
        // Demo creates use the same stable caller ID as the real contract.
        val saved = if (old == null) FakeWorkoutDataSource.createTemplateWithId(request.templateId, request.ownerId, request.name, children)
            else FakeWorkoutDataSource.updateTemplate(old.id, request.name, children)!!
        if (request.isDeleted) FakeWorkoutDataSource.deleteTemplate(saved.id)
        val revision = (request.expectedRevision ?: 0) + 1
        revisions[request.templateId] = revision
        val receipt = com.diajarkoding.imfit.data.remote.TemplateWriteReceipt(request.requestId, request.templateId, revision, children.size, Instant.now().toString())
        receipts[key] = request to receipt
        return receipt
    }
    private fun summary(log: WorkoutLog) = onlineJson.encodeToJsonElement(WorkoutSummary(log.id, null,
        log.templateName, Instant.ofEpochMilli(log.startTime).toString(), Instant.ofEpochMilli(log.endTime).toString(),
        (log.endTime - log.startTime) / 1000, log.totalVolume.toString(),
        log.exerciseLogs.sumOf { it.sets.count { s -> s.isCompleted }.toLong() }, log.exerciseLogs.sumOf { it.sets.filter { s -> s.isCompleted }.sumOf { s -> s.reps.toLong() } }))

    private fun page(call: RpcCall<*>, items: List<JsonElement>): JsonObject {
        val p = call.parameters
        val limit = p.getValue("p_limit").jsonPrimitive.int
        val query = JsonObject(p - "p_cursor").toString()
        val cursor = p["p_cursor"]?.jsonPrimitive?.contentOrNull
        val offset = if (cursor == null) 0 else {
            val decoded = runCatching { onlineJson.parseToJsonElement(String(Base64.getUrlDecoder().decode(cursor))).jsonObject }.getOrElse { throw CursorProtocolException() }
            require(decoded.getValue("query").jsonPrimitive.content == query)
            decoded.getValue("offset").jsonPrimitive.int
        }
        val data = items.drop(offset).take(limit)
        val more = offset + limit < items.size
        val next = if (!more) null else Base64.getUrlEncoder().encodeToString(buildJsonObject { put("query", query); put("offset", offset + limit) }.toString().toByteArray())
        return buildJsonObject { put("data", JsonArray(data)); put("page_info", onlineJson.encodeToJsonElement(PageInfo(next, more, limit))) }
    }
}

package com.diajarkoding.imfit.data.sync

import android.util.Log
import androidx.room.withTransaction
import com.diajarkoding.imfit.core.network.NetworkMonitor
import com.diajarkoding.imfit.data.local.dao.ExerciseDao
import com.diajarkoding.imfit.data.local.dao.ExerciseLogDao
import com.diajarkoding.imfit.data.local.dao.WorkoutLogDao
import com.diajarkoding.imfit.data.local.dao.WorkoutSetDao
import com.diajarkoding.imfit.data.local.dao.WorkoutTemplateDao
import com.diajarkoding.imfit.data.local.dao.TemplateExerciseDao
import com.diajarkoding.imfit.data.local.entity.ExerciseEntity
import com.diajarkoding.imfit.data.local.entity.WorkoutLogEntity
import com.diajarkoding.imfit.data.local.entity.WorkoutTemplateEntity
import com.diajarkoding.imfit.data.local.database.IMFITDatabase
import com.diajarkoding.imfit.data.local.sync.SyncStatus
import com.diajarkoding.imfit.data.remote.ImfitAggregateRemoteDataSource
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.user.UserSession
import io.ktor.http.HttpHeaders
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Explicit remote reads and manual workout recovery; there is no automatic upload queue.
 * Reconnect, foreground refresh and legacy Worker requests never upload pending rows.
 * Sync interfaces are presentation/lifecycle compatibility contracts, not offline scheduling.
 */
@OptIn(io.github.jan.supabase.annotations.SupabaseExperimental::class)
@Singleton
class RemoteWorkoutCoordinator @Inject constructor(
    private val networkMonitor: NetworkMonitor,
    private val supabaseClient: SupabaseClient,
    private val syncPreferences: SyncPreferences,
    private val workoutTemplateDao: WorkoutTemplateDao,
    private val templateExerciseDao: TemplateExerciseDao,
    private val workoutLogDao: WorkoutLogDao,
    private val exerciseLogDao: ExerciseLogDao,
    private val workoutSetDao: WorkoutSetDao,
    private val exerciseDao: ExerciseDao,
    private val database: IMFITDatabase,
    private val aggregateRemoteDataSource: ImfitAggregateRemoteDataSource,
) : SyncStateProvider, SyncRunner, SyncLifecycleController {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Exposed sync state for UI
    private val _syncState = MutableStateFlow(SyncState())
    override val syncState: StateFlow<SyncState> = _syncState.asStateFlow()
    
    private val syncMutex = Mutex()
    private val masterMutex = Mutex()
    private val importer = RemoteSnapshotImporter(database)
    private val blockedUsers = Collections.synchronizedSet(mutableSetOf<String>())

    init {
        // Connectivity changes update presentation only, never upload or schedule work.
        scope.launch {
            networkMonitor.networkStatus.collectLatest { isOnline ->
                if (isOnline) {
                    _syncState.value = _syncState.value.copy(status = SyncState.SyncStatus.IDLE)
                } else {
                    _syncState.value = _syncState.value.copy(status = SyncState.SyncStatus.OFFLINE)
                }
            }
        }
        
        // Initial pending count update
        scope.launch {
            database.invalidationTracker.createFlow("workout_logs", "exercise_logs", "workout_sets", "workout_templates", "template_exercises", "template_write_intents")
                .collect { updatePendingCount() }
        }
        scope.launch {
            supabaseClient.auth.sessionStatus.collectLatest {
                _syncState.value = SyncState()
                updatePendingCount()
            }
        }
    }
    
    /**
     * Updates the pending count in the sync state.
     */
    private suspend fun updatePendingCount() {
        val userId = supabaseClient.auth.currentUserOrNull()?.id
        if (userId == null) {
            _syncState.value = _syncState.value.copy(pendingCount = 0)
            return
        }
        if (userId in blockedUsers) return
        val pendingTemplates = workoutTemplateDao.countRecoveryAggregates(userId) + database.templateWriteIntentDao().count(userId)
        val pendingLogs = workoutLogDao.countRecoveryAggregates(userId)
        val total = pendingTemplates + pendingLogs
        if (supabaseClient.auth.currentUserOrNull()?.id != userId || userId in blockedUsers) return
        
        _syncState.value = _syncState.value.copy(pendingCount = total)
        Log.d(TAG, "Pending aggregate count updated: $total ($pendingTemplates templates, $pendingLogs logs)")
    }

    /**
     * Compatibility read refresh only. Never pushes pending data.
     */
    suspend fun syncAll() { run(null) }

    /** Template writes share the logout/account fence with reads and explicit workout uploads. */
    suspend fun <T> withRemoteSession(owner: String, operation: suspend (UserSession) -> T): T = syncMutex.withLock {
        val credential = supabaseClient.auth.currentSessionOrNull() ?: error("Authentication required")
        require(credential.user?.id == owner)
        requireCurrentSession(credential)
        check(networkMonitor.isOnline) { "Network required" }
        operation(credential).also { requireCurrentSession(credential) }
    }

    fun requireCurrentSession(credential: UserSession) {
        check(supabaseClient.auth.currentSessionOrNull() === credential) { "Authentication session changed" }
        requireCurrentUser(requireNotNull(credential.user).id)
    }

    suspend fun refreshTemplates(owner: String): SyncRunResult = refreshOwner(owner) { pullTemplates(it) }
    suspend fun refreshHistory(owner: String): SyncRunResult = refreshOwner(owner) { pullWorkoutLogs(it) }

    private suspend fun refreshOwner(owner: String, read: suspend (UserSession) -> Unit): SyncRunResult = try {
        withRemoteSession(owner) { read(it) }
        SyncRunResult.SUCCESS
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { SyncRunResult.RETRYABLE_FAILURE }

    override suspend fun run(expectedUserId: String?): SyncRunResult = syncMutex.withLock {
        val currentUserId = supabaseClient.auth.currentUserOrNull()?.id
            ?: return@withLock SyncRunResult.NO_AUTHENTICATED_USER
        if (expectedUserId != null && expectedUserId != currentUserId) {
            return@withLock SyncRunResult.NO_AUTHENTICATED_USER
        }

        if (currentUserId in blockedUsers) return@withLock SyncRunResult.NO_AUTHENTICATED_USER
        if (!networkMonitor.isOnline) return@withLock SyncRunResult.RETRYABLE_FAILURE
        val credential = supabaseClient.auth.currentSessionOrNull() ?: return@withLock SyncRunResult.NO_AUTHENTICATED_USER
        _syncState.value = _syncState.value.copy(status = SyncState.SyncStatus.SYNCING, errorMessage = null)
        try {
            // Remote-first reads may refresh the cache; they NEVER acknowledge pending writes.
            val failures = listOf<suspend () -> Unit>(
                { pullExercises(credential) }, { pullProfile(credential) },
                { pullTemplates(credential) }, { pullWorkoutLogs(credential) }
            ).mapNotNull { read -> captureRead(read) }
            failures.firstOrNull()?.let { throw it }
            updatePendingCount()
            _syncState.value = _syncState.value.copy(status = SyncState.SyncStatus.IDLE, lastSyncTime = System.currentTimeMillis())
            SyncRunResult.SUCCESS
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            _syncState.value = _syncState.value.copy(status = SyncState.SyncStatus.FAILED)
            SyncRunResult.RETRYABLE_FAILURE
        } finally {
            if (_syncState.value.status == SyncState.SyncStatus.SYNCING)
                _syncState.value = _syncState.value.copy(status = SyncState.SyncStatus.IDLE)
        }
    }

    override suspend fun retryPendingUploads(expectedUserId: String): SyncRunResult = syncMutex.withLock {
        if (supabaseClient.auth.currentUserOrNull()?.id != expectedUserId || expectedUserId in blockedUsers)
            return@withLock SyncRunResult.NO_AUTHENTICATED_USER
        if (!networkMonitor.isOnline) return@withLock SyncRunResult.RETRYABLE_FAILURE
        val credential = supabaseClient.auth.currentSessionOrNull() ?: return@withLock SyncRunResult.NO_AUTHENTICATED_USER
        _syncState.value = _syncState.value.copy(status = SyncState.SyncStatus.SYNCING)
        try {
            for (log in workoutLogDao.getPendingLogs(expectedUserId)) {
                requireCurrentSession(credential)
                // Missing legacy provenance requires review, not an invented remote acknowledgement.
                if (log.pendingOperation in setOf("CREATE", "UPDATE", "DELETE")) syncWorkoutAggregate(log, credential)
            }
            updatePendingCount()
            if (_syncState.value.pendingCount == 0) SyncRunResult.SUCCESS else SyncRunResult.RETRYABLE_FAILURE
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { SyncRunResult.RETRYABLE_FAILURE }
        finally {
            updatePendingCount()
            _syncState.value = _syncState.value.copy(status = SyncState.SyncStatus.IDLE)
        }
    }

    suspend fun attemptFinishedWorkout(userId: String, id: String) = syncMutex.withLock {
        if (supabaseClient.auth.currentUserOrNull()?.id != userId || userId in blockedUsers || !networkMonitor.isOnline)
            return@withLock
        val log = workoutLogDao.getWorkoutLogById(id)?.takeIf { it.userId == userId && it.pendingOperation == "CREATE" }
            ?: return@withLock
        try { syncWorkoutAggregate(log) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Durable pending survives. Never retry in a background loop. */ }
        finally { updatePendingCount() }
    }

    override suspend fun blockAndAwaitIdle(userId: String) {
        blockedUsers += userId
        syncMutex.withLock {
            masterMutex.withLock { /* Drain independent master reads before deleting this user's local data. */ }
            _syncState.value = SyncState(status = SyncState.SyncStatus.IDLE)
        }
    }

    override fun unblock(userId: String) {
        blockedUsers -= userId
    }

    override suspend fun refreshMasterData(): SyncRunResult = masterMutex.withLock {
        val userId = supabaseClient.auth.currentUserOrNull()?.id ?: return@withLock SyncRunResult.NO_AUTHENTICATED_USER
        if (userId in blockedUsers) return@withLock SyncRunResult.NO_AUTHENTICATED_USER
        if (!networkMonitor.isOnline) return@withLock SyncRunResult.RETRYABLE_FAILURE
        try {
            val credential = supabaseClient.auth.currentSessionOrNull() ?: return@withLock SyncRunResult.NO_AUTHENTICATED_USER
            pullExercises(credential)
            SyncRunResult.SUCCESS
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { SyncRunResult.RETRYABLE_FAILURE }
    }

    /**
     * Syncs a workout session atomically (WorkoutLog + ExerciseLogs + WorkoutSets).
     * Either all entities sync successfully, or none are marked as synced.
     */
    private suspend fun syncWorkoutAggregate(log: WorkoutLogEntity,
        credential: UserSession = supabaseClient.auth.currentSessionOrNull() ?: error("Authentication required")) {
        val operation = log.pendingOperation ?: return
        require(credential.user?.id == log.userId)
        val snapshot = database.withTransaction {
            check(workoutLogDao.getWorkoutLogById(log.id) == log) { "Workout changed before upload" }
            WorkoutUploadSnapshot(log, exerciseLogDao.getExerciseLogsByWorkoutLogId(log.id), workoutSetDao.getSetsByWorkoutLogId(log.id))
        }
        val exerciseLogs = snapshot.exercises
        val workoutSets = snapshot.sets
        require(exerciseLogs.all { it.workoutLogId == log.id })
        val exerciseIds = exerciseLogs.associateBy { it.id }
        require(exerciseIds.size == exerciseLogs.size)
        require(workoutSets.map { it.id }.distinct().size == workoutSets.size)
        require(workoutSets.all { it.workoutLogId == log.id && exerciseIds[it.exerciseLogId]?.exerciseId == it.exerciseId })
        
        try {
            requireCurrentSession(credential)
            val result = aggregateRemoteDataSource.upsertWorkout(
                workout = rpcJson.encodeToJsonElement(workoutDto(log)).jsonObject,
                exercises = rpcJson.encodeToJsonElement(exerciseLogs.map(::exerciseLogDto)).jsonArray,
                sets = rpcJson.encodeToJsonElement(workoutSets.map(::workoutSetDto)).jsonArray,
                accessToken = credential.accessToken,
            )
            check(result.workoutId == log.id && result.exerciseCount == exerciseLogs.size && result.setCount == workoutSets.size) {
                "Unexpected aggregate receipt; pending preserved"
            }

            database.withTransaction {
                check(supabaseClient.auth.currentSessionOrNull() === credential) { "Authentication changed during upload" }
                val current = workoutLogDao.getWorkoutLogById(log.id) ?: error("Workout removed during upload")
                check(snapshot.matches(WorkoutUploadSnapshot(current,
                    exerciseLogDao.getExerciseLogsByWorkoutLogId(log.id), workoutSetDao.getSetsByWorkoutLogId(log.id)))) {
                    "Workout content changed; pending preserved"
                }
                val marked = workoutLogDao.markAsSyncedIfUnchanged(
                    log.id,
                    log.updatedAt,
                    operation
                )
                if (marked == 1) {
                    exerciseLogDao.markWorkoutExerciseLogsAsSynced(log.id)
                    workoutSetDao.markWorkoutSetsAsSynced(log.id)
                }
            }
            
            Log.d(TAG, "Aggregate sync completed for workout: ${result.workoutId} (${result.exerciseCount} exercises, ${result.setCount} sets)")
        } catch (e: Exception) {
            Log.e(TAG, "Aggregate sync failed for workout ${log.id}: ${e.message}", e)
            // Rollback: keep all as PENDING_SYNC
            throw e
        }
    }

    // ============ PULL METHODS (Remote -> Local) ============

    /**
     * Pulls exercises from server. Server-authoritative - always overwrite local.
     */
    private suspend fun pullExercises(credential: UserSession) {
        val userId = requireNotNull(credential.user).id
        try {
            val remoteExercises = pages { from, to -> supabaseClient.postgrest.from("exercises").select {
                headers[HttpHeaders.Authorization] = "Bearer ${credential.accessToken}"
                order("id", Order.ASCENDING); range(from, to)
            }.decodeList<RemoteExerciseDto>() }

            Log.d(TAG, "Pulled ${remoteExercises.size} exercises from server")

            val entities = remoteExercises.map { remote ->
                ExerciseEntity(
                    id = remote.id,
                    name = remote.name,
                        description = remote.description ?: "",
                        muscleCategoryId = remote.muscleCategoryId,
                        imageUrl = remote.imageUrl,
                        isActive = remote.isActive ?: true
                )
            }
            requireCurrentUser(userId)
            database.withTransaction {
                requireCurrentSession(credential)
                // Offset pages do not prove a complete server snapshot; preserve omitted cache.
                exerciseDao.insertExercises(entities)
            }
            
            syncPreferences.setLastExercisesSyncTimestamp(userId, System.currentTimeMillis())
            Log.d(TAG, "Exercises sync completed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pull exercises: ${e.message}", e)
            throw e
        }
    }

    private suspend fun pullProfile(credential: UserSession) {
        val userId = requireNotNull(credential.user).id
        val profile = supabaseClient.postgrest.from("profiles").select {
            headers[HttpHeaders.Authorization] = "Bearer ${credential.accessToken}"
            filter { eq("id", userId) }
        }.decodeList<com.diajarkoding.imfit.data.remote.dto.ProfileDto>().singleOrNull() ?: return
        require(profile.id == userId)
        requireCurrentUser(userId)
        database.withTransaction {
            requireCurrentSession(credential)
            val existing = database.userDao().getUserById(userId)
            if (existing == null || (existing.syncStatus == SyncStatus.SYNCED.name && existing.pendingOperation == null))
                database.userDao().insertUser(com.diajarkoding.imfit.data.local.entity.UserEntity(
                    profile.id, profile.name, profile.email, profile.birthDate, profile.avatarUrl,
                    syncStatus = SyncStatus.SYNCED.name))
        }
    }

    private suspend fun captureRead(read: suspend () -> Unit): Exception? = try {
        read(); null
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { e }

    /** Explicit pagination; a server row limit must not silently truncate history. */
    private suspend fun <T> pages(fetch: suspend (Long, Long) -> List<T>): List<T> {
        val result = mutableListOf<T>()
        var offset = 0L
        while (true) {
            val page = fetch(offset, offset + 499)
            result.addAll(page)
            if (page.size < 500) return result
            offset += page.size
        }
    }

    /**
     * Pulls owner-scoped complete aggregates; pending/newer local state is protected.
     */
    private suspend fun pullTemplates(credential: UserSession) {
        val userId = requireNotNull(credential.user).id
        try {
            val remoteTemplates = pages { from, to -> supabaseClient.postgrest.from("workout_templates").select {
                headers[HttpHeaders.Authorization] = "Bearer ${credential.accessToken}"
                filter { eq("user_id", userId) }; order("id", Order.ASCENDING); range(from, to)
            }.decodeList<RemoteTemplateDto>() }

            Log.d(TAG, "Pulled ${remoteTemplates.size} templates from server")

            for (remote in remoteTemplates) {
                val remoteUpdatedAt = parseTimestamp(remote.updatedAt)
                require(remote.userId == userId)
                val children = pages { from, to -> supabaseClient.postgrest.from("template_exercises").select {
                    headers[HttpHeaders.Authorization] = "Bearer ${credential.accessToken}"
                    filter { eq("template_id", remote.id) }; order("order_index", Order.ASCENDING)
                    order("exercise_id", Order.ASCENDING); range(from, to)
                }.decodeList<RemoteTemplateExerciseDto>() }
                val latest = supabaseClient.postgrest.from("workout_templates").select {
                    headers[HttpHeaders.Authorization] = "Bearer ${credential.accessToken}"
                    filter { eq("id", remote.id); eq("user_id", userId) }
                }.decodeList<RemoteTemplateDto>().singleOrNull()
                check(latest == remote) { "Remote template changed while reading its children; retry required" }
                requireCurrentUser(userId)
                importer.template(WorkoutTemplateEntity(
                        id = remote.id,
                        userId = remote.userId,
                        name = remote.name,
                        isDeleted = remote.isDeleted ?: false,
                        syncStatus = SyncStatus.SYNCED.name,
                        pendingOperation = null,
                        createdAt = parseTimestamp(remote.createdAt),
                        updatedAt = remoteUpdatedAt,
                        remoteRevision = remote.revision
                    ), children.map { child -> com.diajarkoding.imfit.data.local.entity.TemplateExerciseEntity(
                        child.templateId, child.exerciseId, child.orderIndex ?: 0,
                        child.sets ?: 3, child.reps ?: 8, child.restSeconds ?: 60,
                        syncStatus = SyncStatus.SYNCED.name, pendingOperation = null,
                        createdAt = remoteUpdatedAt, updatedAt = remoteUpdatedAt) },
                    beforeApply = { requireCurrentSession(credential) })
            }
            
            syncPreferences.setLastTemplatesSyncTimestamp(userId, System.currentTimeMillis())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pull templates: ${e.message}", e)
            throw e
        }
    }

    /**
     * Pulls complete owner-scoped history snapshots, including updates/removals of children.
     */
    private suspend fun pullWorkoutLogs(credential: UserSession) {
        val userId = requireNotNull(credential.user).id
        try {
            val remoteLogs = pages { from, to -> supabaseClient.postgrest.from("workout_logs").select {
                headers[HttpHeaders.Authorization] = "Bearer ${credential.accessToken}"
                filter { eq("user_id", userId) }; order("id", Order.ASCENDING); range(from, to)
            }.decodeList<RemoteWorkoutLogDto>() }

            Log.d(TAG, "Pulled ${remoteLogs.size} workout logs from server")

            for (remote in remoteLogs) {
                val remoteUpdatedAt = parseTimestamp(remote.updatedAt ?: remote.startTime)
                require(remote.userId == userId)
                val children = pages { from, to -> supabaseClient.postgrest.from("exercise_logs").select {
                    headers[HttpHeaders.Authorization] = "Bearer ${credential.accessToken}"
                    filter { eq("workout_log_id", remote.id) }; order("id", Order.ASCENDING); range(from, to)
                }.decodeList<RemoteExerciseLogDto>() }
                val sets = pages { from, to -> supabaseClient.postgrest.from("workout_sets").select {
                    headers[HttpHeaders.Authorization] = "Bearer ${credential.accessToken}"
                    filter { eq("workout_log_id", remote.id) }; order("id", Order.ASCENDING); range(from, to)
                }.decodeList<RemoteWorkoutSetDto>() }
                val latest = supabaseClient.postgrest.from("workout_logs").select {
                    headers[HttpHeaders.Authorization] = "Bearer ${credential.accessToken}"
                    filter { eq("id", remote.id); eq("user_id", userId) }
                }.decodeList<RemoteWorkoutLogDto>().singleOrNull()
                check(latest == remote) { "Remote workout changed while reading its children; retry required" }
                requireCurrentUser(userId)
                importer.workout(WorkoutLogEntity(
                        id = remote.id,
                        userId = remote.userId,
                        templateId = remote.templateId,
                        templateName = remote.templateName,
                        date = parseTimestamp(remote.date),
                        startTime = parseTimestamp(remote.startTime),
                        endTime = parseTimestamp(remote.endTime),
                        totalVolume = remote.totalVolume.toFloat(),
                        totalSets = remote.totalSets,
                        totalReps = remote.totalReps,
                        syncStatus = SyncStatus.SYNCED.name,
                        pendingOperation = null,
                        deletedAt = remote.deletedAt?.let(::parseTimestamp),
                        createdAt = parseTimestamp(remote.createdAt ?: remote.startTime),
                        updatedAt = remoteUpdatedAt
                    ), children.map { child -> com.diajarkoding.imfit.data.local.entity.ExerciseLogEntity(
                        child.id, child.workoutLogId, child.exerciseId, child.exerciseName, child.muscleCategory,
                        child.orderIndex, child.totalVolume.toFloat(), child.totalSets, child.totalReps,
                        syncStatus = SyncStatus.SYNCED.name, pendingOperation = null) },
                    sets.map { set -> com.diajarkoding.imfit.data.local.entity.WorkoutSetEntity(
                        set.id, set.exerciseLogId, set.workoutLogId, set.exerciseId, set.setNumber,
                        set.weight.toFloat(), set.reps, set.isCompleted ?: false,
                        syncStatus = SyncStatus.SYNCED.name, pendingOperation = null) },
                    beforeApply = { requireCurrentSession(credential) })
            }
            
            syncPreferences.setLastWorkoutLogsSyncTimestamp(userId, System.currentTimeMillis())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pull workout logs: ${e.message}", e)
            throw e
        }
    }

    /**
     * Parses ISO8601 timestamp string to epoch milliseconds.
     */
    private fun parseTimestamp(timestamp: String): Long {
        return try {
            OffsetDateTime.parse(timestamp).toInstant().toEpochMilli()
        } catch (e: Exception) {
            try {
                // Try parsing as date only
                java.time.LocalDate.parse(timestamp).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            } catch (e2: Exception) {
                throw IllegalArgumentException("Invalid remote timestamp", e2)
            }
        }
    }

    private fun requireCurrentUser(expectedUserId: String) {
        check(supabaseClient.auth.currentUserOrNull()?.id == expectedUserId && expectedUserId !in blockedUsers) {
            "Authenticated user changed during synchronization"
        }
    }

    // ============ PUSH METHODS (Local -> Remote) ============

    private fun workoutDto(log: WorkoutLogEntity): UpsertWorkoutLogDto {
        val startDateTime = OffsetDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(log.startTime),
            ZoneOffset.UTC
        )
        val endDateTime = OffsetDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(log.endTime),
            ZoneOffset.UTC
        )
        
        val deletedAtString = log.deletedAt?.let { timestamp ->
            OffsetDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(timestamp),
                ZoneOffset.UTC
            ).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        }

        return UpsertWorkoutLogDto(
            id = log.id,
            userId = log.userId,
            templateId = log.templateId,
            templateName = log.templateName,
            date = startDateTime.toLocalDate().toString(),
            startTime = startDateTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            endTime = endDateTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            totalVolume = log.totalVolume.toDouble(),
            totalSets = log.totalSets,
            totalReps = log.totalReps,
            deletedAt = deletedAtString
        )
    }

    private fun exerciseLogDto(
        log: com.diajarkoding.imfit.data.local.entity.ExerciseLogEntity
    ) = UpsertExerciseLogDto(
            id = log.id,
            workoutLogId = log.workoutLogId,
            exerciseId = log.exerciseId,
            exerciseName = log.exerciseName,
            muscleCategory = log.muscleCategory,
            orderIndex = log.orderIndex,
            totalVolume = log.totalVolume.toDouble(),
            totalSets = log.totalSets,
            totalReps = log.totalReps
        )
    private fun workoutSetDto(
        set: com.diajarkoding.imfit.data.local.entity.WorkoutSetEntity
    ) = UpsertWorkoutSetDto(
            id = set.id,
            exerciseLogId = set.exerciseLogId,
            workoutLogId = set.workoutLogId,
            exerciseId = set.exerciseId,
            setNumber = set.setNumber,
            weight = set.weight.toDouble(),
            reps = set.reps,
            isCompleted = set.isCompleted
        )
    companion object {
        private const val TAG = "RemoteWorkoutCoordinator"
        private val rpcJson = Json {
            encodeDefaults = true
            explicitNulls = false
        }
    }
}

@Serializable
private data class UpsertWorkoutLogDto(
    val id: String,
    @SerialName("user_id")
    val userId: String,
    @SerialName("template_id")
    val templateId: String?,
    @SerialName("template_name")
    val templateName: String,
    val date: String,
    @SerialName("start_time")
    val startTime: String,
    @SerialName("end_time")
    val endTime: String,
    @SerialName("total_volume")
    val totalVolume: Double,
    @SerialName("total_sets")
    val totalSets: Int,
    @SerialName("total_reps")
    val totalReps: Int,
    @SerialName("deleted_at")
    val deletedAt: String? = null
)

@Serializable
private data class UpsertExerciseLogDto(
    val id: String,
    @SerialName("workout_log_id")
    val workoutLogId: String,
    @SerialName("exercise_id")
    val exerciseId: String,
    @SerialName("exercise_name")
    val exerciseName: String,
    @SerialName("muscle_category")
    val muscleCategory: String,
    @SerialName("order_index")
    val orderIndex: Int,
    @SerialName("total_volume")
    val totalVolume: Double,
    @SerialName("total_sets")
    val totalSets: Int,
    @SerialName("total_reps")
    val totalReps: Int
)

@Serializable
private data class UpsertWorkoutSetDto(
    val id: String,
    @SerialName("exercise_log_id")
    val exerciseLogId: String,
    @SerialName("workout_log_id")
    val workoutLogId: String,
    @SerialName("exercise_id")
    val exerciseId: String,
    @SerialName("set_number")
    val setNumber: Int,
    val weight: Double,
    val reps: Int,
    @SerialName("is_completed")
    val isCompleted: Boolean
)

// ============ REMOTE DTOs (for Pull) ============

@Serializable
private data class RemoteExerciseDto(
    val id: String,
    val name: String,
    val description: String? = null,
    @SerialName("image_url")
    val imageUrl: String? = null,
    @SerialName("muscle_category_id")
    val muscleCategoryId: Int,
    @SerialName("is_active")
    val isActive: Boolean? = true
)

@Serializable
private data class RemoteTemplateDto(
    val id: String,
    @SerialName("user_id")
    val userId: String,
    val name: String,
    @SerialName("is_deleted")
    val isDeleted: Boolean? = false,
    @SerialName("created_at")
    val createdAt: String,
    @SerialName("updated_at")
    val updatedAt: String,
    val revision: Long? = null
)

@Serializable
private data class RemoteWorkoutLogDto(
    val id: String,
    @SerialName("user_id")
    val userId: String,
    @SerialName("template_id")
    val templateId: String? = null,
    @SerialName("template_name")
    val templateName: String,
    val date: String,
    @SerialName("start_time")
    val startTime: String,
    @SerialName("end_time")
    val endTime: String,
    @SerialName("total_volume")
    val totalVolume: Double,
    @SerialName("total_sets")
    val totalSets: Int,
    @SerialName("total_reps")
    val totalReps: Int,
    @SerialName("created_at")
    val createdAt: String? = null,
    @SerialName("updated_at")
    val updatedAt: String? = null,
    @SerialName("deleted_at")
    val deletedAt: String? = null
)

@Serializable
private data class RemoteExerciseLogDto(
    val id: String,
    @SerialName("workout_log_id")
    val workoutLogId: String,
    @SerialName("exercise_id")
    val exerciseId: String,
    @SerialName("exercise_name")
    val exerciseName: String,
    @SerialName("muscle_category")
    val muscleCategory: String,
    @SerialName("order_index")
    val orderIndex: Int,
    @SerialName("total_volume")
    val totalVolume: Double,
    @SerialName("total_sets")
    val totalSets: Int,
    @SerialName("total_reps")
    val totalReps: Int
)

@Serializable
private data class RemoteWorkoutSetDto(
    val id: String,
    @SerialName("exercise_log_id")
    val exerciseLogId: String,
    @SerialName("workout_log_id")
    val workoutLogId: String,
    @SerialName("exercise_id")
    val exerciseId: String,
    @SerialName("set_number")
    val setNumber: Int,
    val weight: Double,
    val reps: Int,
    @SerialName("is_completed")
    val isCompleted: Boolean? = false
)

@Serializable
private data class RemoteTemplateExerciseDto(
    @SerialName("template_id")
    val templateId: String,
    @SerialName("exercise_id")
    val exerciseId: String,
    @SerialName("order_index")
    val orderIndex: Int? = 0,
    val sets: Int? = 3,
    val reps: Int? = 10,
    @SerialName("rest_seconds")
    val restSeconds: Int? = 60
)

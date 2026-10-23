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
import com.diajarkoding.imfit.data.local.sync.PendingOperation
import com.diajarkoding.imfit.data.local.sync.SyncStatus
import com.diajarkoding.imfit.data.remote.ImfitAggregateRemoteDataSource
import com.diajarkoding.imfit.data.remote.dto.TemplateExerciseDto
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages synchronization of local data with remote Supabase server.
 * Handles offline-first pattern with automatic sync when connectivity is restored.
 * Exposes syncState for UI observation.
 */
@Singleton
class SyncManager @Inject constructor(
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
    private val snapshots = AggregateSnapshotStore(database)
    private val blockedUsers = Collections.synchronizedSet(mutableSetOf<String>())

    init {
        // Observe network changes and trigger sync when online
        scope.launch {
            networkMonitor.networkStatus.collectLatest { isOnline ->
                if (isOnline) {
                    Log.d(TAG, "Network available, starting sync...")
                    _syncState.value = _syncState.value.copy(status = SyncState.SyncStatus.SYNCING)
                    syncAll()
                } else {
                    _syncState.value = _syncState.value.copy(status = SyncState.SyncStatus.OFFLINE)
                }
            }
        }
        
        // Initial pending count update
        scope.launch {
            updatePendingCount()
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
        val pendingTemplates = workoutTemplateDao.getPendingSyncTemplates(userId).size
        val pendingLogs = workoutLogDao.getPendingLogs(userId).size
        val total = pendingTemplates + pendingLogs
        
        _syncState.value = _syncState.value.copy(pendingCount = total)
        Log.d(TAG, "Pending aggregate count updated: $total ($pendingTemplates templates, $pendingLogs logs)")
    }

    /**
     * Syncs all pending data with the remote server.
     * Bidirectional sync: push local changes first, then pull remote updates.
     */
    suspend fun syncAll() = syncMutex.withLock {
        val userId = supabaseClient.auth.currentUserOrNull()?.id
        if (userId == null) {
            _syncState.value = SyncState(status = SyncState.SyncStatus.IDLE)
            return@withLock
        }
        if (userId !in blockedUsers) syncAllLocked(userId)
    }

    override suspend fun run(expectedUserId: String?): SyncRunResult = syncMutex.withLock {
        val currentUserId = supabaseClient.auth.currentUserOrNull()?.id
            ?: return@withLock SyncRunResult.NO_AUTHENTICATED_USER
        if (expectedUserId != null && expectedUserId != currentUserId) {
            return@withLock SyncRunResult.NO_AUTHENTICATED_USER
        }

        if (currentUserId in blockedUsers) return@withLock SyncRunResult.NO_AUTHENTICATED_USER
        syncAllLocked(currentUserId)
        when (_syncState.value.status) {
            SyncState.SyncStatus.SYNCED -> SyncRunResult.SUCCESS
            SyncState.SyncStatus.IDLE -> SyncRunResult.NO_AUTHENTICATED_USER
            else -> SyncRunResult.RETRYABLE_FAILURE
        }
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
            pullExercises(userId)
            SyncRunResult.SUCCESS
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { SyncRunResult.RETRYABLE_FAILURE }
    }

    private suspend fun syncAllLocked(userId: String) {
        if (!networkMonitor.isOnline) {
            Log.d(TAG, "Network unavailable, skipping sync")
            _syncState.value = _syncState.value.copy(status = SyncState.SyncStatus.OFFLINE)
            return
        }

        _syncState.value = _syncState.value.copy(
            status = SyncState.SyncStatus.SYNCING,
            errorMessage = null
        )

        try {
            // Independent server-owned reads must not be blocked by an unrelated failed upload.
            val exerciseFailure = captureRead { pullExercises(userId) }
            val profileFailure = captureRead { pullProfile(userId) }
            // Always preserve and upload durable local changes before pulling remote state.
            val templatesSynced = syncPendingTemplates(userId)
            val workoutsSynced = syncPendingWorkoutLogs(userId)
            if (!templatesSynced || !workoutsSynced) {
                throw IllegalStateException("One or more local aggregates are still pending retry")
            }

            // Check if initial sync is needed (fresh install / reinstall)
            if (needsInitialSync(userId)) {
                performInitialSync(userId)
                return
            }

            // PULL: Remote changes -> Local (delta sync)
            requireCurrentUser(userId)
            pullTemplates(userId)
            pullWorkoutLogs(userId)

            exerciseFailure?.let { throw it }
            profileFailure?.let { throw it }
            
            updatePendingCount()
            syncPreferences.setLastSyncTimestamp(userId, System.currentTimeMillis())
            
            _syncState.value = _syncState.value.copy(
                status = SyncState.SyncStatus.SYNCED,
                lastSyncTime = System.currentTimeMillis()
            )
            Log.d(TAG, "Sync completed successfully")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed: ${e.message}", e)
            _syncState.value = _syncState.value.copy(
                status = SyncState.SyncStatus.FAILED,
                errorMessage = e.message
            )
        }
    }

    /**
     * Checks if initial sync is needed (fresh install, reinstall, or first login).
     */
    private suspend fun needsInitialSync(userId: String): Boolean {
        // If initial sync was never completed OR local DB is empty
        if (!syncPreferences.isInitialSyncCompleted(userId)) {
            return true
        }
        
        // Check if templates exist locally (basic DB presence check)
        val templates = workoutTemplateDao.getTemplatesByUserList(userId)
        
        return templates.isEmpty() && syncPreferences.getLastSyncTimestamp(userId) == 0L
    }

    /**
     * Performs initial sync for cold start (reinstall, new device, first login).
     * Downloads ALL user data from Supabase and inserts into Room.
     * Reports progress for UI display.
     */
    private suspend fun performInitialSync(userId: String) {
        Log.d(TAG, "Starting initial sync for user: $userId")
        _syncState.value = _syncState.value.copy(
            status = SyncState.SyncStatus.SYNCING,
            isInitialSync = true,
            progress = 0f,
            progressMessage = "Preparing sync...",
            errorMessage = null
        )

        try {
            // Step 1: Pull exercises (15%)
            updateProgress(0.05f, "Downloading exercises...")
            requireCurrentUser(userId)
            pullExercises(userId)
            pullProfile(userId)
            
            // Step 2: Pull all user templates (30%)
            updateProgress(0.15f, "Downloading workout templates...")
            pullTemplates(userId)
            
            // Step 3: Pull template exercises (45%)
            updateProgress(0.30f, "Downloading template exercises...")
            // Template children were applied atomically with each parent.
            
            // Step 4: Pull all workout logs (60%)
            updateProgress(0.45f, "Downloading workout history...")
            pullWorkoutLogs(userId)
            
            // Step 5: Pull exercise logs (75%)
            updateProgress(0.60f, "Downloading exercise logs...")
            // Workout children were applied atomically with each parent.
            
            // Step 6: Pull workout sets (90%)
            updateProgress(0.75f, "Downloading workout sets...")
            // No separately visible partial history snapshots.
            
            // Mark initial sync as completed
            syncPreferences.setInitialSyncCompleted(userId, true)
            syncPreferences.setLastSyncTimestamp(userId, System.currentTimeMillis())
            
            updateProgress(1.0f, "Sync complete!")
            
            _syncState.value = _syncState.value.copy(
                status = SyncState.SyncStatus.SYNCED,
                lastSyncTime = System.currentTimeMillis(),
                isInitialSync = false,
                progress = 1f,
                progressMessage = null
            )
            Log.d(TAG, "Initial sync completed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Initial sync failed: ${e.message}", e)
            _syncState.value = _syncState.value.copy(
                status = SyncState.SyncStatus.FAILED,
                errorMessage = "Initial sync failed: ${e.message}",
                isInitialSync = false
            )
            throw e
        }
    }
    
    /**
     * Updates sync progress for UI.
     */
    private fun updateProgress(progress: Float, message: String) {
        _syncState.value = _syncState.value.copy(
            progress = progress,
            progressMessage = message
        )
        Log.d(TAG, "Sync progress: ${(progress * 100).toInt()}% - $message")
    }
    
    /**
     * Syncs pending workout templates with the remote server.
     */
    private suspend fun syncPendingTemplates(userId: String): Boolean {
        val pendingTemplates = workoutTemplateDao.getPendingSyncTemplates(userId)
        Log.d(TAG, "Found ${pendingTemplates.size} pending templates to sync")
        var allSucceeded = true

        for (template in pendingTemplates) {
            try {
                requireCurrentUser(userId)
                val snapshot = snapshots.template(userId, template.id) ?: continue
                val captured = snapshot.parent
                when (captured.pendingOperation) {
                    PendingOperation.CREATE.name -> {
                        createTemplateRemote(captured.id, captured.userId, captured.name)
                        syncTemplateExercises(captured.id, snapshot.exercises)
                    }
                    PendingOperation.UPDATE.name -> {
                        updateTemplateRemote(captured.id, captured.name)
                        syncTemplateExercises(captured.id, snapshot.exercises)
                    }
                    PendingOperation.DELETE.name -> {
                        deleteTemplateRemote(captured.id)
                    }
                }
                // Revision CAS is independent of the still-blocked SDK epoch adapter (see B1 report).
                requireCurrentUser(userId)
                if (!snapshots.acknowledge(snapshot)) allSucceeded = false
                Log.d(TAG, "Synced template: ${template.id} (${template.pendingOperation})")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync template ${template.id}: ${e.message}", e)
                workoutTemplateDao.updateSyncStatus(template.id, SyncStatus.SYNC_FAILED.name)
                allSucceeded = false
            }
        }
        return allSucceeded
    }

    /**
     * Syncs pending workout logs with the remote server.
     * Uses atomic sync to ensure WorkoutLog + ExerciseLogs + WorkoutSets sync together.
     */
    private suspend fun syncPendingWorkoutLogs(userId: String): Boolean {
        val pendingLogs = workoutLogDao.getPendingLogs(userId)
        Log.d(TAG, "Found ${pendingLogs.size} pending workout logs to sync")
        var allSucceeded = true

        for (log in pendingLogs) {
            try {
                syncWorkoutAggregate(log)
                Log.d(TAG, "Synced workout log: ${log.id} (operation: ${log.pendingOperation})")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync workout log ${log.id}: ${e.message}", e)
                allSucceeded = false
            }
        }
        return allSucceeded
    }

    /**
     * Syncs a workout session atomically (WorkoutLog + ExerciseLogs + WorkoutSets).
     * Either all entities sync successfully, or none are marked as synced.
     */
    private suspend fun syncWorkoutAggregate(log: WorkoutLogEntity) {
        requireCurrentUser(log.userId)
        val snapshot = snapshots.workout(log.userId, log.id) ?: return
        
        try {
            val result = aggregateRemoteDataSource.upsertWorkout(
                workout = rpcJson.encodeToJsonElement(workoutDto(snapshot.parent)).jsonObject,
                exercises = rpcJson.encodeToJsonElement(snapshot.exercises.map(::exerciseLogDto)).jsonArray,
                sets = rpcJson.encodeToJsonElement(snapshot.sets.map(::workoutSetDto)).jsonArray,
            )

            requireCurrentUser(log.userId)
            check(snapshots.acknowledge(snapshot)) { "Local workout changed during upload; pending preserved" }
            
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
    private suspend fun pullExercises(userId: String) {
        try {
            val remoteExercises = pages { from, to -> supabaseClient.postgrest.from("exercises").select {
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
                val remoteIds = entities.map { it.id }.toSet()
                exerciseDao.getAllActiveExercisesList().filterNot { it.id in remoteIds }
                    .forEach { exerciseDao.deactivateExercise(it.id) }
                exerciseDao.insertExercises(entities)
            }
            
            syncPreferences.setLastExercisesSyncTimestamp(userId, System.currentTimeMillis())
            Log.d(TAG, "Exercises sync completed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pull exercises: ${e.message}", e)
            throw e
        }
    }

    private suspend fun pullProfile(userId: String) {
        val profile = supabaseClient.postgrest.from("profiles").select {
            filter { eq("id", userId) }
        }.decodeList<com.diajarkoding.imfit.data.remote.dto.ProfileDto>().singleOrNull() ?: return
        require(profile.id == userId)
        requireCurrentUser(userId)
        database.userDao().insertUser(com.diajarkoding.imfit.data.local.entity.UserEntity(
            profile.id, profile.name, profile.email, profile.birthDate, profile.avatarUrl))
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
    private suspend fun pullTemplates(userId: String) {
        try {
            val remoteTemplates = pages { from, to -> supabaseClient.postgrest.from("workout_templates").select {
                filter { eq("user_id", userId) }; order("id", Order.ASCENDING); range(from, to)
            }.decodeList<RemoteTemplateDto>() }

            Log.d(TAG, "Pulled ${remoteTemplates.size} templates from server")

            for (remote in remoteTemplates) {
                val remoteUpdatedAt = parseTimestamp(remote.updatedAt)
                require(remote.userId == userId)
                val children = pages { from, to -> supabaseClient.postgrest.from("template_exercises").select {
                    filter { eq("template_id", remote.id) }; order("order_index", Order.ASCENDING)
                    order("exercise_id", Order.ASCENDING); range(from, to)
                }.decodeList<RemoteTemplateExerciseDto>() }
                val latest = supabaseClient.postgrest.from("workout_templates").select {
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
                        updatedAt = remoteUpdatedAt
                    ), children.map { child -> com.diajarkoding.imfit.data.local.entity.TemplateExerciseEntity(
                        child.templateId, child.exerciseId, child.orderIndex ?: 0,
                        child.sets ?: 3, child.reps ?: 8, child.restSeconds ?: 60,
                        syncStatus = SyncStatus.SYNCED.name, pendingOperation = null,
                        createdAt = remoteUpdatedAt, updatedAt = remoteUpdatedAt) })
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
    private suspend fun pullWorkoutLogs(userId: String) {
        try {
            val remoteLogs = pages { from, to -> supabaseClient.postgrest.from("workout_logs").select {
                filter { eq("user_id", userId) }; order("id", Order.ASCENDING); range(from, to)
            }.decodeList<RemoteWorkoutLogDto>() }

            Log.d(TAG, "Pulled ${remoteLogs.size} workout logs from server")

            for (remote in remoteLogs) {
                val remoteUpdatedAt = parseTimestamp(remote.updatedAt ?: remote.startTime)
                require(remote.userId == userId)
                val children = pages { from, to -> supabaseClient.postgrest.from("exercise_logs").select {
                    filter { eq("workout_log_id", remote.id) }; order("id", Order.ASCENDING); range(from, to)
                }.decodeList<RemoteExerciseLogDto>() }
                val sets = pages { from, to -> supabaseClient.postgrest.from("workout_sets").select {
                    filter { eq("workout_log_id", remote.id) }; order("id", Order.ASCENDING); range(from, to)
                }.decodeList<RemoteWorkoutSetDto>() }
                val latest = supabaseClient.postgrest.from("workout_logs").select {
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
                        syncStatus = SyncStatus.SYNCED.name, pendingOperation = null) })
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

    private suspend fun createTemplateRemote(id: String, userId: String, name: String) {
        val dto = CreateTemplateDto(id = id, userId = userId, name = name)
        supabaseClient.postgrest.from("workout_templates").upsert(dto) {
            onConflict = "id"
        }
    }

    private suspend fun updateTemplateRemote(id: String, name: String) {
        supabaseClient.postgrest.from("workout_templates")
            .update({
                set("name", name)
                set("updated_at", OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
            }) {
                filter { eq("id", id) }
            }
    }

    private suspend fun deleteTemplateRemote(id: String) {
        supabaseClient.postgrest.from("workout_templates")
            .update({ set("is_deleted", true) }) {
                filter { eq("id", id) }
            }
    }

    private suspend fun syncTemplateExercises(
        templateId: String,
        exercises: List<com.diajarkoding.imfit.data.local.entity.TemplateExerciseEntity>
    ) {
        // Delete existing and re-insert
        supabaseClient.postgrest.from("template_exercises")
            .delete { filter { eq("template_id", templateId) } }

        exercises.forEach { exercise ->
            val dto = TemplateExerciseDto(
                templateId = templateId,
                exerciseId = exercise.exerciseId,
                orderIndex = exercise.orderIndex,
                sets = exercise.sets,
                reps = exercise.reps,
                restSeconds = exercise.restSeconds
            )
            supabaseClient.postgrest.from("template_exercises").insert(dto)
        }
    }

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
        private const val TAG = "SyncManager"
        private val rpcJson = Json {
            encodeDefaults = true
            explicitNulls = false
        }
    }
}

@Serializable
private data class CreateTemplateDto(
    val id: String,
    @SerialName("user_id")
    val userId: String,
    val name: String
)

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
    val updatedAt: String
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

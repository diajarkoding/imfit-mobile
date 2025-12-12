package com.diajarkoding.imfit.data.sync

import android.util.Log
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
import com.diajarkoding.imfit.data.local.sync.PendingOperation
import com.diajarkoding.imfit.data.local.sync.SyncStatus
import com.diajarkoding.imfit.data.remote.dto.TemplateExerciseDto
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
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
    private val exerciseDao: ExerciseDao
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Exposed sync state for UI
    private val _syncState = MutableStateFlow(SyncState())
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()
    
    // Debounced trigger job
    private var triggerJob: Job? = null
    private val debounceDelayMs = 2000L

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
     * Triggers a debounced sync operation.
     * Multiple triggers within debounceDelayMs will be consolidated into one sync.
     */
    fun trigger() {
        triggerJob?.cancel()
        triggerJob = scope.launch {
            delay(debounceDelayMs)
            updatePendingCount()
            syncAll()
        }
    }

    /**
     * Updates the pending count in the sync state.
     */
    private suspend fun updatePendingCount() {
        val pendingTemplates = workoutTemplateDao.getPendingSyncTemplates().size
        val pendingLogs = workoutLogDao.getPendingLogs().size
        val pendingExerciseLogs = exerciseLogDao.getPendingExerciseLogs().size
        val pendingSets = workoutSetDao.getPendingWorkoutSets().size
        val total = pendingTemplates + pendingLogs + pendingExerciseLogs + pendingSets
        
        _syncState.value = _syncState.value.copy(pendingCount = total)
        Log.d(TAG, "Pending count updated: $total ($pendingTemplates templates, $pendingLogs logs, $pendingExerciseLogs exerciseLogs, $pendingSets sets)")
    }

    /**
     * Syncs all pending data with the remote server.
     * Bidirectional sync: push local changes first, then pull remote updates.
     */
    suspend fun syncAll() {
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
            // Check if initial sync is needed (fresh install / reinstall)
            if (needsInitialSync()) {
                performInitialSync()
                return
            }
            
            // PUSH: Local changes -> Remote
            syncPendingTemplates()
            syncPendingWorkoutLogs()
            
            // PULL: Remote changes -> Local (delta sync)
            pullExercises() // Server-authoritative
            pullTemplates()
            pullWorkoutLogs()
            
            updatePendingCount()
            syncPreferences.lastSyncTimestamp = System.currentTimeMillis()
            
            _syncState.value = _syncState.value.copy(
                status = SyncState.SyncStatus.SYNCED,
                lastSyncTime = System.currentTimeMillis()
            )
            Log.d(TAG, "Sync completed successfully")
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
    private suspend fun needsInitialSync(): Boolean {
        // If initial sync was never completed OR local DB is empty
        if (!syncPreferences.isInitialSyncCompleted) {
            return true
        }
        
        // Check if templates exist locally (basic DB presence check)
        val userId = supabaseClient.auth.currentUserOrNull()?.id ?: return false
        val templates = workoutTemplateDao.getTemplatesByUserList(userId)
        
        return templates.isEmpty() && syncPreferences.lastSyncTimestamp == 0L
    }

    /**
     * Performs initial sync for cold start (reinstall, new device, first login).
     * Downloads ALL user data from Supabase and inserts into Room.
     */
    suspend fun performInitialSync() {
        val userId = supabaseClient.auth.currentUserOrNull()?.id ?: run {
            Log.e(TAG, "Cannot perform initial sync - no authenticated user")
            return
        }
        
        Log.d(TAG, "Starting initial sync for user: $userId")
        _syncState.value = _syncState.value.copy(
            status = SyncState.SyncStatus.SYNCING,
            errorMessage = null
        )

        try {
            // 1. Pull exercises (server-authoritative)
            pullExercises()
            
            // 2. Pull all user templates
            pullTemplates()
            
            // 3. Pull all workout logs (includes exercise logs and sets)
            pullWorkoutLogs()
            pullExerciseLogs()
            pullWorkoutSets()
            
            // Mark initial sync as completed
            syncPreferences.isInitialSyncCompleted = true
            syncPreferences.lastSyncTimestamp = System.currentTimeMillis()
            
            _syncState.value = _syncState.value.copy(
                status = SyncState.SyncStatus.SYNCED,
                lastSyncTime = System.currentTimeMillis()
            )
            Log.d(TAG, "Initial sync completed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Initial sync failed: ${e.message}", e)
            _syncState.value = _syncState.value.copy(
                status = SyncState.SyncStatus.FAILED,
                errorMessage = "Initial sync failed: ${e.message}"
            )
            throw e
        }
    }

    /**
     * Syncs pending workout templates with the remote server.
     */
    suspend fun syncPendingTemplates() {
        val pendingTemplates = workoutTemplateDao.getPendingSyncTemplates()
        Log.d(TAG, "Found ${pendingTemplates.size} pending templates to sync")

        for (template in pendingTemplates) {
            try {
                when (template.pendingOperation) {
                    PendingOperation.CREATE.name -> {
                        createTemplateRemote(template.id, template.userId, template.name)
                        syncTemplateExercises(template.id)
                        workoutTemplateDao.markAsSynced(template.id, SyncStatus.SYNCED.name)
                    }
                    PendingOperation.UPDATE.name -> {
                        updateTemplateRemote(template.id, template.name)
                        syncTemplateExercises(template.id)
                        workoutTemplateDao.markAsSynced(template.id, SyncStatus.SYNCED.name)
                    }
                    PendingOperation.DELETE.name -> {
                        deleteTemplateRemote(template.id)
                        workoutTemplateDao.markAsSynced(template.id, SyncStatus.SYNCED.name)
                    }
                }
                Log.d(TAG, "Synced template: ${template.id} (${template.pendingOperation})")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync template ${template.id}: ${e.message}", e)
                workoutTemplateDao.updateSyncStatus(template.id, SyncStatus.SYNC_FAILED.name)
            }
        }
    }

    /**
     * Syncs pending workout logs with the remote server.
     * Uses atomic sync to ensure WorkoutLog + ExerciseLogs + WorkoutSets sync together.
     */
    suspend fun syncPendingWorkoutLogs() {
        val pendingLogs = workoutLogDao.getPendingLogs()
        Log.d(TAG, "Found ${pendingLogs.size} pending workout logs to sync")

        for (log in pendingLogs) {
            try {
                syncWorkoutAtomic(log.id)
                Log.d(TAG, "Synced workout log: ${log.id} (operation: ${log.pendingOperation})")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync workout log ${log.id}: ${e.message}", e)
                workoutLogDao.updateSyncStatus(log.id, SyncStatus.SYNC_FAILED.name)
            }
        }
    }

    /**
     * Syncs a workout session atomically (WorkoutLog + ExerciseLogs + WorkoutSets).
     * Either all entities sync successfully, or none are marked as synced.
     */
    suspend fun syncWorkoutAtomic(workoutLogId: String) {
        val log = workoutLogDao.getWorkoutLogById(workoutLogId) ?: return
        val exerciseLogs = exerciseLogDao.getExerciseLogsByWorkoutLogId(workoutLogId)
        val workoutSets = workoutSetDao.getSetsByWorkoutLogId(workoutLogId)
        
        try {
            // 1. Push WorkoutLog
            upsertWorkoutLogRemote(log)
            
            // 2. Push ExerciseLogs
            for (exerciseLog in exerciseLogs) {
                upsertExerciseLogRemote(exerciseLog)
            }
            
            // 3. Push WorkoutSets
            for (set in workoutSets) {
                upsertWorkoutSetRemote(set)
            }
            
            // 4. Mark all as SYNCED (only if all succeeded)
            workoutLogDao.markAsSynced(workoutLogId)
            exerciseLogs.forEach { exerciseLogDao.markAsSynced(it.id) }
            workoutSets.forEach { workoutSetDao.markAsSynced(it.id) }
            
            Log.d(TAG, "Atomic sync completed for workout: $workoutLogId (${exerciseLogs.size} exercises, ${workoutSets.size} sets)")
        } catch (e: Exception) {
            Log.e(TAG, "Atomic sync failed for workout $workoutLogId: ${e.message}", e)
            // Rollback: keep all as PENDING_SYNC
            throw e
        }
    }

    // ============ PULL METHODS (Remote -> Local) ============

    /**
     * Pulls exercises from server. Server-authoritative - always overwrite local.
     */
    private suspend fun pullExercises() {
        try {
            val remoteExercises = supabaseClient.postgrest
                .from("exercises")
                .select()
                .decodeList<RemoteExerciseDto>()

            Log.d(TAG, "Pulled ${remoteExercises.size} exercises from server")

            for (remote in remoteExercises) {
                val entity = ExerciseEntity(
                    id = remote.id,
                    name = remote.name,
                    description = remote.description ?: "",
                    muscleCategoryId = remote.muscleCategoryId,
                    isActive = remote.isActive ?: true
                )
                exerciseDao.insertExercise(entity)
            }
            
            syncPreferences.lastExercisesSyncTimestamp = System.currentTimeMillis()
            Log.d(TAG, "Exercises sync completed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pull exercises: ${e.message}", e)
            // Don't throw - exercises are optional
        }
    }

    /**
     * Pulls templates from server using delta sync based on updated_at.
     * Uses last-write-wins conflict resolution.
     */
    private suspend fun pullTemplates() {
        val userId = supabaseClient.auth.currentUserOrNull()?.id ?: return
        
        try {
            val remoteTemplates = supabaseClient.postgrest
                .from("workout_templates")
                .select() {
                    filter {
                        eq("user_id", userId)
                        eq("is_deleted", false)
                    }
                }
                .decodeList<RemoteTemplateDto>()

            Log.d(TAG, "Pulled ${remoteTemplates.size} templates from server")

            for (remote in remoteTemplates) {
                val remoteUpdatedAt = parseTimestamp(remote.updatedAt)
                val local = workoutTemplateDao.getTemplateById(remote.id)
                
                // Conflict resolution: last-write-wins
                if (local == null) {
                    // New from server - insert
                    val entity = WorkoutTemplateEntity(
                        id = remote.id,
                        userId = remote.userId,
                        name = remote.name,
                        isDeleted = remote.isDeleted ?: false,
                        syncStatus = SyncStatus.SYNCED.name,
                        pendingOperation = null,
                        createdAt = parseTimestamp(remote.createdAt),
                        updatedAt = remoteUpdatedAt
                    )
                    workoutTemplateDao.insertTemplate(entity)
                    Log.d(TAG, "Inserted template from server: ${remote.id}")
                } else if (local.syncStatus == SyncStatus.SYNCED.name && remoteUpdatedAt > local.updatedAt) {
                    // Remote is newer and local has no pending changes - update
                    val updated = local.copy(
                        name = remote.name,
                        isDeleted = remote.isDeleted ?: false,
                        updatedAt = remoteUpdatedAt
                    )
                    workoutTemplateDao.updateTemplate(updated)
                    Log.d(TAG, "Updated template from server: ${remote.id}")
                }
                // If local has pending changes, keep local version (it will push on next sync)
            }
            
            syncPreferences.lastTemplatesSyncTimestamp = System.currentTimeMillis()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pull templates: ${e.message}", e)
            throw e
        }
    }

    /**
     * Pulls workout logs from server using delta sync.
     */
    private suspend fun pullWorkoutLogs() {
        val userId = supabaseClient.auth.currentUserOrNull()?.id ?: return
        
        try {
            val remoteLogs = supabaseClient.postgrest
                .from("workout_logs")
                .select() {
                    filter {
                        eq("user_id", userId)
                    }
                }
                .decodeList<RemoteWorkoutLogDto>()
                .filter { it.deletedAt == null } // Filter non-deleted locally

            Log.d(TAG, "Pulled ${remoteLogs.size} workout logs from server")

            for (remote in remoteLogs) {
                val remoteUpdatedAt = parseTimestamp(remote.updatedAt ?: remote.startTime)
                val local = workoutLogDao.getWorkoutLogById(remote.id)
                
                if (local == null) {
                    // New from server - insert
                    val entity = WorkoutLogEntity(
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
                        deletedAt = null,
                        createdAt = parseTimestamp(remote.createdAt ?: remote.startTime),
                        updatedAt = remoteUpdatedAt
                    )
                    workoutLogDao.insertWorkoutLog(entity)
                    Log.d(TAG, "Inserted workout log from server: ${remote.id}")
                } else if (local.syncStatus == SyncStatus.SYNCED.name && remoteUpdatedAt > local.updatedAt) {
                    // Remote is newer - update
                    val updated = local.copy(
                        templateName = remote.templateName,
                        totalVolume = remote.totalVolume.toFloat(),
                        totalSets = remote.totalSets,
                        totalReps = remote.totalReps,
                        updatedAt = remoteUpdatedAt
                    )
                    workoutLogDao.updateWorkoutLog(updated)
                    Log.d(TAG, "Updated workout log from server: ${remote.id}")
                }
            }
            
            syncPreferences.lastWorkoutLogsSyncTimestamp = System.currentTimeMillis()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pull workout logs: ${e.message}", e)
            throw e
        }
    }

    /**
     * Pulls exercise logs from server for initial sync.
     */
    private suspend fun pullExerciseLogs() {
        val userId = supabaseClient.auth.currentUserOrNull()?.id ?: return
        
        try {
            // Get all workout log IDs for this user
            val workoutLogs = workoutLogDao.getWorkoutLogsByUserList(userId)
            if (workoutLogs.isEmpty()) {
                Log.d(TAG, "No workout logs found, skipping exercise logs pull")
                return
            }
            
            val workoutLogIds = workoutLogs.map { it.id }
            
            val remoteLogs = supabaseClient.postgrest
                .from("exercise_logs")
                .select()
                .decodeList<RemoteExerciseLogDto>()
                .filter { it.workoutLogId in workoutLogIds }
            
            Log.d(TAG, "Pulled ${remoteLogs.size} exercise logs from server")
            
            for (remote in remoteLogs) {
                val existing = exerciseLogDao.getExerciseLog(remote.workoutLogId, remote.exerciseId)
                if (existing == null) {
                    val entity = com.diajarkoding.imfit.data.local.entity.ExerciseLogEntity(
                        id = remote.id,
                        workoutLogId = remote.workoutLogId,
                        exerciseId = remote.exerciseId,
                        exerciseName = remote.exerciseName,
                        muscleCategory = remote.muscleCategory,
                        orderIndex = remote.orderIndex,
                        totalVolume = remote.totalVolume.toFloat(),
                        totalSets = remote.totalSets,
                        totalReps = remote.totalReps,
                        syncStatus = SyncStatus.SYNCED.name,
                        pendingOperation = null
                    )
                    exerciseLogDao.insertExerciseLog(entity)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pull exercise logs: ${e.message}", e)
            // Don't throw - continue with sync
        }
    }

    /**
     * Pulls workout sets from server for initial sync.
     */
    private suspend fun pullWorkoutSets() {
        val userId = supabaseClient.auth.currentUserOrNull()?.id ?: return
        
        try {
            val workoutLogs = workoutLogDao.getWorkoutLogsByUserList(userId)
            if (workoutLogs.isEmpty()) {
                Log.d(TAG, "No workout logs found, skipping workout sets pull")
                return
            }
            
            val workoutLogIds = workoutLogs.map { it.id }
            
            val remoteSets = supabaseClient.postgrest
                .from("workout_sets")
                .select()
                .decodeList<RemoteWorkoutSetDto>()
                .filter { it.workoutLogId in workoutLogIds }
            
            Log.d(TAG, "Pulled ${remoteSets.size} workout sets from server")
            
            for (remote in remoteSets) {
                val existing = workoutSetDao.getSetById(remote.id)
                if (existing == null) {
                    val entity = com.diajarkoding.imfit.data.local.entity.WorkoutSetEntity(
                        id = remote.id,
                        exerciseLogId = remote.exerciseLogId,
                        workoutLogId = remote.workoutLogId,
                        exerciseId = remote.exerciseId,
                        setNumber = remote.setNumber,
                        weight = remote.weight.toFloat(),
                        reps = remote.reps,
                        isCompleted = remote.isCompleted ?: false,
                        syncStatus = SyncStatus.SYNCED.name,
                        pendingOperation = null
                    )
                    workoutSetDao.insertWorkoutSet(entity)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pull workout sets: ${e.message}", e)
            // Don't throw - continue with sync
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
                System.currentTimeMillis()
            }
        }
    }

    // ============ PUSH METHODS (Local -> Remote) ============

    private suspend fun createTemplateRemote(id: String, userId: String, name: String) {
        val dto = CreateTemplateDto(id = id, userId = userId, name = name)
        supabaseClient.postgrest.from("workout_templates").insert(dto)
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

    private suspend fun syncTemplateExercises(templateId: String) {
        // Delete existing and re-insert
        supabaseClient.postgrest.from("template_exercises")
            .delete { filter { eq("template_id", templateId) } }

        val exercises = templateExerciseDao.getExercisesForTemplateList(templateId)
        exercises.forEachIndexed { index, exercise ->
            val dto = TemplateExerciseDto(
                templateId = templateId,
                exerciseId = exercise.exerciseId,
                orderIndex = index,
                sets = exercise.sets,
                reps = exercise.reps,
                restSeconds = exercise.restSeconds
            )
            supabaseClient.postgrest.from("template_exercises").insert(dto)
        }
    }

    /**
     * Upserts a workout log to the remote server.
     */
    private suspend fun upsertWorkoutLogRemote(
        log: com.diajarkoding.imfit.data.local.entity.WorkoutLogEntity
    ) {
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

        val dto = UpsertWorkoutLogDto(
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
        
        supabaseClient.postgrest.from("workout_logs").upsert(dto) {
            onConflict = "id"
        }
    }

    /**
     * Upserts an exercise log to the remote server.
     */
    private suspend fun upsertExerciseLogRemote(
        log: com.diajarkoding.imfit.data.local.entity.ExerciseLogEntity
    ) {
        val dto = UpsertExerciseLogDto(
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
        
        supabaseClient.postgrest.from("exercise_logs").upsert(dto) {
            onConflict = "id"
        }
    }

    /**
     * Upserts a workout set to the remote server.
     */
    private suspend fun upsertWorkoutSetRemote(
        set: com.diajarkoding.imfit.data.local.entity.WorkoutSetEntity
    ) {
        val dto = UpsertWorkoutSetDto(
            id = set.id,
            exerciseLogId = set.exerciseLogId,
            workoutLogId = set.workoutLogId,
            exerciseId = set.exerciseId,
            setNumber = set.setNumber,
            weight = set.weight.toDouble(),
            reps = set.reps,
            isCompleted = set.isCompleted
        )
        
        supabaseClient.postgrest.from("workout_sets").upsert(dto) {
            onConflict = "id"
        }
    }

    companion object {
        private const val TAG = "SyncManager"
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

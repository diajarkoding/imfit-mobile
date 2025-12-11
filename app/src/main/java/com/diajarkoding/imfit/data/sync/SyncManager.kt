package com.diajarkoding.imfit.data.sync

import android.util.Log
import com.diajarkoding.imfit.core.network.NetworkMonitor
import com.diajarkoding.imfit.data.local.dao.WorkoutLogDao
import com.diajarkoding.imfit.data.local.dao.WorkoutTemplateDao
import com.diajarkoding.imfit.data.local.dao.TemplateExerciseDao
import com.diajarkoding.imfit.data.local.sync.PendingOperation
import com.diajarkoding.imfit.data.local.sync.SyncStatus
import com.diajarkoding.imfit.data.remote.dto.TemplateExerciseDto
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages synchronization of local data with remote Supabase server.
 * Handles offline-first pattern with automatic sync when connectivity is restored.
 */
@Singleton
class SyncManager @Inject constructor(
    private val networkMonitor: NetworkMonitor,
    private val supabaseClient: SupabaseClient,
    private val workoutTemplateDao: WorkoutTemplateDao,
    private val templateExerciseDao: TemplateExerciseDao,
    private val workoutLogDao: WorkoutLogDao
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        // Observe network changes and trigger sync when online
        scope.launch {
            networkMonitor.networkStatus.collectLatest { isOnline ->
                if (isOnline) {
                    Log.d(TAG, "Network available, starting sync...")
                    syncAll()
                }
            }
        }
    }

    /**
     * Syncs all pending data with the remote server.
     */
    suspend fun syncAll() {
        if (!networkMonitor.isOnline) {
            Log.d(TAG, "Network unavailable, skipping sync")
            return
        }

        try {
            syncPendingTemplates()
            syncPendingWorkoutLogs()
            Log.d(TAG, "Sync completed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed: ${e.message}", e)
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
     */
    suspend fun syncPendingWorkoutLogs() {
        val pendingLogs = workoutLogDao.getWorkoutLogsBySyncStatus(SyncStatus.PENDING_SYNC.name)
        Log.d(TAG, "Found ${pendingLogs.size} pending workout logs to sync")

        for (log in pendingLogs) {
            try {
                createWorkoutLogRemote(log)
                workoutLogDao.updateSyncStatus(log.id, SyncStatus.SYNCED.name)
                Log.d(TAG, "Synced workout log: ${log.id}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync workout log ${log.id}: ${e.message}", e)
                workoutLogDao.updateSyncStatus(log.id, SyncStatus.SYNC_FAILED.name)
            }
        }
    }

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

    private suspend fun createWorkoutLogRemote(
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

        val dto = CreateWorkoutLogDto(
            id = log.id,
            userId = log.userId,
            templateId = log.templateId,
            templateName = log.templateName,
            date = startDateTime.toLocalDate().toString(),
            startTime = startDateTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            endTime = endDateTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            totalVolume = log.totalVolume.toDouble(),
            totalSets = log.totalSets,
            totalReps = log.totalReps
        )
        supabaseClient.postgrest.from("workout_logs").insert(dto)
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
private data class CreateWorkoutLogDto(
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
    val totalReps: Int
)

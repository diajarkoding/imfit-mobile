package com.diajarkoding.imfit.data.local.dao

import androidx.room.*
import com.diajarkoding.imfit.data.local.entity.WorkoutLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkoutLogDao {
    // Get all workout logs (including soft deleted - for admin/debug purposes)
    @Query("SELECT * FROM workout_logs WHERE user_id = :userId ORDER BY date DESC")
    fun getWorkoutLogsByUser(userId: String): Flow<List<WorkoutLogEntity>>

    // Get only active (non-deleted) workout logs
    @Query("SELECT * FROM workout_logs WHERE user_id = :userId AND deleted_at IS NULL ORDER BY date DESC")
    fun getActiveWorkoutLogsByUser(userId: String): Flow<List<WorkoutLogEntity>>

    // Get last active workout log
    @Query("SELECT * FROM workout_logs WHERE user_id = :userId AND deleted_at IS NULL ORDER BY date DESC LIMIT 1")
    suspend fun getLastWorkoutLog(userId: String): WorkoutLogEntity?

    @Query("SELECT * FROM workout_logs WHERE id = :id")
    suspend fun getWorkoutLogById(id: String): WorkoutLogEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWorkoutLog(workoutLog: WorkoutLogEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWorkoutLogs(workoutLogs: List<WorkoutLogEntity>)

    @Update
    suspend fun updateWorkoutLog(workoutLog: WorkoutLogEntity)

    @Delete
    suspend fun deleteWorkoutLog(workoutLog: WorkoutLogEntity)

    @Query("DELETE FROM workout_logs WHERE user_id = :userId")
    suspend fun deleteWorkoutLogsByUser(userId: String)

    @Query("DELETE FROM workout_logs")
    suspend fun deleteAllWorkoutLogs()

    // Soft delete - marks record as deleted and sets pending sync
    @Query("""
        UPDATE workout_logs 
        SET deleted_at = :timestamp,
            sync_status = 'PENDING_SYNC',
            pending_operation = 'DELETE',
            updated_at = :timestamp
        WHERE id = :logId
    """)
    suspend fun softDeleteLog(logId: String, timestamp: Long = System.currentTimeMillis())

    // Hard delete - only used after sync is complete
    @Query("DELETE FROM workout_logs WHERE id = :logId")
    suspend fun hardDeleteLog(logId: String)

    // Sync-aware queries
    @Query("SELECT * FROM workout_logs WHERE sync_status = :syncStatus")
    suspend fun getWorkoutLogsBySyncStatus(syncStatus: String): List<WorkoutLogEntity>

    // Get all pending logs for sync (including soft deleted)
    @Query("SELECT * FROM workout_logs WHERE sync_status = 'PENDING_SYNC'")
    suspend fun getPendingLogs(): List<WorkoutLogEntity>

    @Query("UPDATE workout_logs SET sync_status = :syncStatus WHERE id = :id")
    suspend fun updateSyncStatus(id: String, syncStatus: String)

    // Mark as synced and clear pending operation
    @Query("UPDATE workout_logs SET sync_status = 'SYNCED', pending_operation = NULL WHERE id = :id")
    suspend fun markAsSynced(id: String)

    @Query("SELECT * FROM workout_logs WHERE user_id = :userId AND deleted_at IS NULL ORDER BY date DESC")
    suspend fun getWorkoutLogsByUserList(userId: String): List<WorkoutLogEntity>
}

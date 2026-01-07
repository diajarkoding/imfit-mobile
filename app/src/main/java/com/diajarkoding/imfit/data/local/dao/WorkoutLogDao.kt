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

    // Soft delete - marks record as deleted
    @Query("UPDATE workout_logs SET deleted_at = :timestamp, updated_at = :timestamp WHERE id = :logId")
    suspend fun softDeleteLog(logId: String, timestamp: Long = System.currentTimeMillis())

    // Hard delete
    @Query("DELETE FROM workout_logs WHERE id = :logId")
    suspend fun hardDeleteLog(logId: String)

    @Query("SELECT * FROM workout_logs WHERE user_id = :userId AND deleted_at IS NULL ORDER BY date DESC")
    suspend fun getWorkoutLogsByUserList(userId: String): List<WorkoutLogEntity>
}

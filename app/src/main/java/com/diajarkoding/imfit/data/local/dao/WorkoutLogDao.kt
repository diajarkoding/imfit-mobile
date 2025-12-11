package com.diajarkoding.imfit.data.local.dao

import androidx.room.*
import com.diajarkoding.imfit.data.local.entity.WorkoutLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkoutLogDao {
    @Query("SELECT * FROM workout_logs WHERE user_id = :userId ORDER BY date DESC")
    fun getWorkoutLogsByUser(userId: String): Flow<List<WorkoutLogEntity>>

    @Query("SELECT * FROM workout_logs WHERE user_id = :userId ORDER BY date DESC LIMIT 1")
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
}
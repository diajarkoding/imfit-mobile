package com.diajarkoding.imfit.data.local.dao

import androidx.room.*
import com.diajarkoding.imfit.data.local.entity.ExerciseLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ExerciseLogDao {
    @Query("SELECT * FROM exercise_logs WHERE workout_log_id = :workoutLogId ORDER BY order_index")
    fun getExerciseLogsByWorkout(workoutLogId: String): Flow<List<ExerciseLogEntity>>

    @Query("SELECT * FROM exercise_logs WHERE workout_log_id = :workoutLogId AND exercise_id = :exerciseId")
    suspend fun getExerciseLog(workoutLogId: String, exerciseId: String): ExerciseLogEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExerciseLog(exerciseLog: ExerciseLogEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExerciseLogs(exerciseLogs: List<ExerciseLogEntity>)

    @Update
    suspend fun updateExerciseLog(exerciseLog: ExerciseLogEntity)

    @Delete
    suspend fun deleteExerciseLog(exerciseLog: ExerciseLogEntity)

    @Query("DELETE FROM exercise_logs WHERE workout_log_id = :workoutLogId")
    suspend fun deleteExerciseLogsByWorkout(workoutLogId: String)

    @Query("DELETE FROM exercise_logs")
    suspend fun deleteAllExerciseLogs()
}
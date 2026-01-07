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

    @Query("DELETE FROM exercise_logs WHERE workout_log_id IN (SELECT id FROM workout_logs WHERE user_id = :userId)")
    suspend fun deleteExerciseLogsByUser(userId: String)

    @Query("DELETE FROM exercise_logs")
    suspend fun deleteAllExerciseLogs()

    @Query("""
        SELECT e.* FROM exercise_logs e
        INNER JOIN workout_logs w ON e.workout_log_id = w.id
        WHERE e.exercise_id = :exerciseId
        ORDER BY w.date DESC
        LIMIT 1
    """)
    suspend fun getLastExerciseLog(exerciseId: String): ExerciseLogEntity?

    @Query("SELECT * FROM exercise_logs WHERE workout_log_id = :workoutLogId ORDER BY order_index")
    suspend fun getExerciseLogsByWorkoutLogId(workoutLogId: String): List<ExerciseLogEntity>
}
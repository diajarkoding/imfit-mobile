package com.diajarkoding.imfit.data.local.dao

import androidx.room.*
import com.diajarkoding.imfit.data.local.entity.WorkoutSetEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkoutSetDao {
    @Query("SELECT * FROM workout_sets WHERE exercise_log_id = :exerciseLogId ORDER BY set_number")
    fun getSetsByExerciseLog(exerciseLogId: String): Flow<List<WorkoutSetEntity>>

    @Query("SELECT * FROM workout_sets WHERE workout_log_id = :workoutLogId")
    fun getSetsByWorkoutLog(workoutLogId: String): Flow<List<WorkoutSetEntity>>

    @Query("SELECT * FROM workout_sets WHERE id = :id")
    suspend fun getSetById(id: String): WorkoutSetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWorkoutSet(workoutSet: WorkoutSetEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWorkoutSets(workoutSets: List<WorkoutSetEntity>)

    @Update
    suspend fun updateWorkoutSet(workoutSet: WorkoutSetEntity)

    @Delete
    suspend fun deleteWorkoutSet(workoutSet: WorkoutSetEntity)

    @Query("DELETE FROM workout_sets WHERE exercise_log_id = :exerciseLogId")
    suspend fun deleteSetsByExerciseLog(exerciseLogId: String)

    @Query("DELETE FROM workout_sets WHERE workout_log_id = :workoutLogId")
    suspend fun deleteSetsByWorkoutLog(workoutLogId: String)

    @Query("SELECT * FROM workout_sets WHERE workout_log_id = :workoutLogId AND exercise_id = :exerciseId ORDER BY set_number")
    suspend fun getSetsForExercise(workoutLogId: String, exerciseId: String): List<WorkoutSetEntity>

    @Query("DELETE FROM workout_sets")
    suspend fun deleteAllSets()

    /**
     * Gets all sets from the last completed workout for a specific exercise.
     * Returns sets ordered by set_number for per-set weight mapping.
     * Filters out soft-deleted workout logs and sets with weight <= 0.
     */
    @Query("""
        SELECT ws.* FROM workout_sets ws
        INNER JOIN workout_logs wl ON ws.workout_log_id = wl.id
        WHERE ws.exercise_id = :exerciseId
            AND wl.user_id = :userId
            AND wl.deleted_at IS NULL
            AND ws.weight > 0
            AND ws.is_completed = 1
        ORDER BY wl.date DESC, ws.set_number ASC
    """)
    suspend fun getLastSetsForExercise(exerciseId: String, userId: String): List<WorkoutSetEntity>

    /**
     * Gets a map of set_number to weight for the last workout of a specific exercise.
     * Uses a subquery to get only sets from the most recent workout log.
     */
    @Query("""
        SELECT ws.* FROM workout_sets ws
        WHERE ws.workout_log_id = (
            SELECT ws2.workout_log_id FROM workout_sets ws2
            INNER JOIN workout_logs wl ON ws2.workout_log_id = wl.id
            WHERE ws2.exercise_id = :exerciseId
                AND wl.user_id = :userId
                AND wl.deleted_at IS NULL
                AND ws2.weight > 0
                AND ws2.is_completed = 1
            ORDER BY wl.date DESC
            LIMIT 1
        )
        AND ws.exercise_id = :exerciseId
        ORDER BY ws.set_number ASC
    """)
    suspend fun getLastWorkoutSetsForExercise(exerciseId: String, userId: String): List<WorkoutSetEntity>
}

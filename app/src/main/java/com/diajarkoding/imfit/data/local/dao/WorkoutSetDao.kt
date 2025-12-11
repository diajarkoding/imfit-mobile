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
}
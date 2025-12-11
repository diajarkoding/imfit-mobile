package com.diajarkoding.imfit.data.local.dao

import androidx.room.*
import com.diajarkoding.imfit.data.local.entity.ExerciseEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ExerciseDao {
    @Query("SELECT * FROM exercises WHERE is_active = 1")
    fun getAllActiveExercises(): Flow<List<ExerciseEntity>>

    @Query("SELECT * FROM exercises WHERE id = :id")
    suspend fun getExerciseById(id: String): ExerciseEntity?

    @Query("SELECT * FROM exercises WHERE muscle_category_id = :categoryId AND is_active = 1")
    fun getExercisesByCategory(categoryId: Int): Flow<List<ExerciseEntity>>

    @Query("SELECT * FROM exercises WHERE name LIKE '%' || :query || '%' AND is_active = 1")
    fun searchExercises(query: String): Flow<List<ExerciseEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExercise(exercise: ExerciseEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExercises(exercises: List<ExerciseEntity>)

    @Update
    suspend fun updateExercise(exercise: ExerciseEntity)

    @Query("UPDATE exercises SET is_active = 0 WHERE id = :id")
    suspend fun deactivateExercise(id: String)

    @Query("DELETE FROM exercises WHERE id = :id")
    suspend fun deleteExercise(id: String)

    @Query("DELETE FROM exercises")
    suspend fun deleteAllExercises()
}
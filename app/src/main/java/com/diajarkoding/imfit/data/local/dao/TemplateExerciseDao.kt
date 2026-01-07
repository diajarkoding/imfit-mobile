package com.diajarkoding.imfit.data.local.dao

import androidx.room.*
import com.diajarkoding.imfit.data.local.entity.TemplateExerciseEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TemplateExerciseDao {
    @Query("SELECT * FROM template_exercises WHERE template_id = :templateId ORDER BY order_index")
    fun getExercisesByTemplate(templateId: String): Flow<List<TemplateExerciseEntity>>

    @Query("SELECT * FROM template_exercises WHERE template_id = :templateId ORDER BY order_index")
    suspend fun getExercisesForTemplateList(templateId: String): List<TemplateExerciseEntity>

    @Query("SELECT * FROM template_exercises WHERE template_id = :templateId AND exercise_id = :exerciseId")
    suspend fun getTemplateExercise(templateId: String, exerciseId: String): TemplateExerciseEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTemplateExercise(templateExercise: TemplateExerciseEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTemplateExercises(templateExercises: List<TemplateExerciseEntity>)

    @Update
    suspend fun updateTemplateExercise(templateExercise: TemplateExerciseEntity)

    @Delete
    suspend fun deleteTemplateExercise(templateExercise: TemplateExerciseEntity)

    @Query("DELETE FROM template_exercises WHERE template_id = :templateId")
    suspend fun deleteExercisesByTemplate(templateId: String)

    @Query("DELETE FROM template_exercises WHERE template_id IN (SELECT id FROM workout_templates WHERE user_id = :userId)")
    suspend fun deleteExercisesByUser(userId: String)

    @Query("DELETE FROM template_exercises")
    suspend fun deleteAllTemplateExercises()
}
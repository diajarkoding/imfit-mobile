package com.diajarkoding.imfit.data.local.dao

import androidx.room.*
import com.diajarkoding.imfit.data.local.entity.WorkoutTemplateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkoutTemplateDao {
    @Query("SELECT * FROM workout_templates WHERE user_id = :userId AND is_deleted = 0")
    fun getTemplatesByUser(userId: String): Flow<List<WorkoutTemplateEntity>>

    @Query("SELECT * FROM workout_templates WHERE id = :id AND is_deleted = 0")
    suspend fun getTemplateById(id: String): WorkoutTemplateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTemplate(template: WorkoutTemplateEntity)

    @Update
    suspend fun updateTemplate(template: WorkoutTemplateEntity)

    @Query("UPDATE workout_templates SET is_deleted = 1 WHERE id = :id")
    suspend fun deleteTemplate(id: String)

    @Query("DELETE FROM workout_templates WHERE user_id = :userId")
    suspend fun deleteTemplatesByUser(userId: String)

    @Query("DELETE FROM workout_templates")
    suspend fun deleteAllTemplates()
}
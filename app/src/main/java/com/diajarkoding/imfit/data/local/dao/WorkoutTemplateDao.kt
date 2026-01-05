package com.diajarkoding.imfit.data.local.dao

import androidx.room.*
import com.diajarkoding.imfit.data.local.entity.WorkoutTemplateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkoutTemplateDao {
    @Query("SELECT * FROM workout_templates WHERE user_id = :userId AND is_deleted = 0")
    fun getTemplatesByUser(userId: String): Flow<List<WorkoutTemplateEntity>>

    @Query("SELECT * FROM workout_templates WHERE user_id = :userId AND is_deleted = 0")
    suspend fun getTemplatesByUserList(userId: String): List<WorkoutTemplateEntity>

    @Query("SELECT * FROM workout_templates WHERE id = :id AND is_deleted = 0")
    suspend fun getTemplateById(id: String): WorkoutTemplateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTemplate(template: WorkoutTemplateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTemplates(templates: List<WorkoutTemplateEntity>)

    @Update
    suspend fun updateTemplate(template: WorkoutTemplateEntity)

    @Query("UPDATE workout_templates SET is_deleted = 1, sync_status = :syncStatus, pending_operation = 'DELETE' WHERE id = :id")
    suspend fun softDeleteTemplate(id: String, syncStatus: String)

    @Query("UPDATE workout_templates SET is_deleted = 1 WHERE id = :id")
    suspend fun deleteTemplate(id: String)

    @Query("DELETE FROM workout_templates WHERE user_id = :userId")
    suspend fun deleteTemplatesByUser(userId: String)

    @Query("DELETE FROM workout_templates")
    suspend fun deleteAllTemplates()

    // Sync-aware queries
    @Query("SELECT * FROM workout_templates WHERE sync_status = :syncStatus")
    suspend fun getTemplatesBySyncStatus(syncStatus: String): List<WorkoutTemplateEntity>

    @Query("SELECT * FROM workout_templates WHERE pending_operation IS NOT NULL")
    suspend fun getPendingSyncTemplates(): List<WorkoutTemplateEntity>

    @Query("UPDATE workout_templates SET sync_status = :syncStatus, pending_operation = NULL WHERE id = :id")
    suspend fun markAsSynced(id: String, syncStatus: String)

    @Query("UPDATE workout_templates SET sync_status = :syncStatus WHERE id = :id")
    suspend fun updateSyncStatus(id: String, syncStatus: String)
}
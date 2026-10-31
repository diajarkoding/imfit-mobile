package com.diajarkoding.imfit.data.local.dao

import androidx.room.*
import com.diajarkoding.imfit.data.local.entity.WorkoutTemplateEntity

@Dao
interface WorkoutTemplateDao {
    /** Legacy quarantine only; includes tombstones and dirty children without reviving them. */
    @Query("""
        SELECT * FROM workout_templates AS parent
        WHERE parent.user_id = :userId AND (
            parent.pending_operation IS NOT NULL OR parent.sync_status != 'SYNCED'
            OR EXISTS (
                SELECT 1 FROM template_exercises AS child
                WHERE child.template_id = parent.id
                AND (child.pending_operation IS NOT NULL OR child.sync_status != 'SYNCED')
            )
        )
        ORDER BY parent.id
    """)
    suspend fun getQuarantinedTemplates(userId: String): List<WorkoutTemplateEntity>

    @Query("""
        SELECT COUNT(*) FROM workout_templates AS parent
        WHERE parent.user_id = :userId AND (
            parent.pending_operation IS NOT NULL OR parent.sync_status != 'SYNCED'
            OR EXISTS (
                SELECT 1 FROM template_exercises AS child
                WHERE child.template_id = parent.id
                AND (child.pending_operation IS NOT NULL OR child.sync_status != 'SYNCED')
            )
        )
    """)
    suspend fun countQuarantinedTemplates(userId: String): Int

    @Query("SELECT * FROM workout_templates WHERE user_id = :userId")
    suspend fun getAllTemplatesByUserIncludingDeleted(userId: String): List<WorkoutTemplateEntity>

}

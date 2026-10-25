package com.diajarkoding.imfit.data.local.database

import androidx.sqlite.db.SupportSQLiteDatabase

/** Called inside a Room transaction after owner uploads have been drained. */
internal object LogoutRecoveryStore {
    fun pruneSyncedCache(db: SupportSQLiteDatabase, owner: String) {
        require(owner.isNotBlank())
        val disposableLogs = """
            SELECT w.id FROM workout_logs w
            WHERE w.user_id = ? AND w.sync_status = 'SYNCED' AND w.pending_operation IS NULL
              AND NOT EXISTS (SELECT 1 FROM exercise_logs e WHERE e.workout_log_id = w.id
                AND (e.sync_status != 'SYNCED' OR e.pending_operation IS NOT NULL))
              AND NOT EXISTS (SELECT 1 FROM workout_sets s WHERE s.workout_log_id = w.id
                AND (s.sync_status != 'SYNCED' OR s.pending_operation IS NOT NULL))
        """.trimIndent()
        db.execSQL("DELETE FROM workout_sets WHERE workout_log_id IN ($disposableLogs)", arrayOf(owner))
        db.execSQL("DELETE FROM exercise_logs WHERE workout_log_id IN ($disposableLogs)", arrayOf(owner))
        db.execSQL("DELETE FROM workout_logs WHERE id IN ($disposableLogs)", arrayOf(owner))

        // Only pending aggregates remain for this owner. Their templates, and templates used
        // by unfinished active-session drafts, are recovery dependencies rather than cache.
        val disposableTemplates = """
            SELECT t.id FROM workout_templates t
            WHERE t.user_id = ? AND t.sync_status = 'SYNCED' AND t.pending_operation IS NULL
              AND NOT EXISTS (SELECT 1 FROM template_exercises e WHERE e.template_id = t.id
                AND (e.sync_status != 'SYNCED' OR e.pending_operation IS NOT NULL))
              AND NOT EXISTS (SELECT 1 FROM workout_logs w WHERE w.template_id = t.id)
              AND NOT EXISTS (SELECT 1 FROM active_sessions a WHERE a.template_id = t.id)
              AND NOT EXISTS (SELECT 1 FROM template_write_intents i WHERE i.template_id = t.id)
        """.trimIndent()
        db.execSQL("DELETE FROM template_exercises WHERE template_id IN ($disposableTemplates)", arrayOf(owner))
        db.execSQL("DELETE FROM workout_templates WHERE id IN ($disposableTemplates)", arrayOf(owner))
        db.execSQL("DELETE FROM users WHERE id = ? AND sync_status = 'SYNCED' AND pending_operation IS NULL", arrayOf(owner))
    }
}

package com.diajarkoding.imfit.data.local.database

import androidx.sqlite.db.SupportSQLiteDatabase

/** Keep SQL compatible with minSdk 24 SQLite (no UPSERT or row-value comparisons). */
internal object AggregateRevisionTriggers {
    fun install(db: SupportSQLiteDatabase) {
        // Ownerless high-water mark survives an existing owner purge without retaining owner IDs.
        db.execSQL("""INSERT INTO aggregate_revisions(owner_id, aggregate_type, aggregate_id, revision)
            SELECT '', 'clock', '', 1 WHERE NOT EXISTS(
                SELECT 1 FROM aggregate_revisions WHERE owner_id = '' AND aggregate_type = 'clock' AND aggregate_id = '')""")
        parent(db, "workout_logs", "workout")
        parent(db, "workout_templates", "template")
        child(db, "exercise_logs", "workout_logs", "workout", "workout_log_id", "id")
        child(db, "workout_sets", "workout_logs", "workout", "workout_log_id", "id")
        child(db, "template_exercises", "workout_templates", "template", "template_id", "template_id", "exercise_id")
    }

    private fun bump(type: String, identities: String): String {
        // NOT EXISTS is deliberate: an outer OR REPLACE must not replace/reset revision rows.
        val matches = "r.owner_id = p.owner_id AND r.aggregate_type = '$type' AND r.aggregate_id = p.aggregate_id"
        val clock = "owner_id = '' AND aggregate_type = 'clock' AND aggregate_id = ''"
        return """
            SELECT CASE WHEN NOT EXISTS(SELECT 1 FROM aggregate_revisions WHERE $clock)
                THEN RAISE(ABORT, 'Missing aggregate revision clock') END;
            SELECT CASE WHEN EXISTS(SELECT 1 FROM aggregate_revisions WHERE $clock AND revision >= 9223372036854775807)
                THEN RAISE(ABORT, 'Aggregate revision exhausted') END;
            UPDATE aggregate_revisions SET revision = revision + 1 WHERE $clock AND EXISTS($identities);
            INSERT INTO aggregate_revisions(owner_id, aggregate_type, aggregate_id, revision)
            SELECT p.owner_id, '$type', p.aggregate_id, (SELECT revision FROM aggregate_revisions WHERE $clock) FROM ($identities) p
            WHERE NOT EXISTS(SELECT 1 FROM aggregate_revisions r WHERE $matches);
            UPDATE aggregate_revisions SET revision = (SELECT revision FROM aggregate_revisions WHERE $clock)
            WHERE aggregate_type = '$type' AND EXISTS(SELECT 1 FROM ($identities) p
                WHERE p.owner_id = aggregate_revisions.owner_id AND p.aggregate_id = aggregate_revisions.aggregate_id);
        """.trimIndent()
    }

    private fun trigger(db: SupportSQLiteDatabase, table: String, suffix: String, event: String, body: String) {
        db.execSQL("CREATE TRIGGER IF NOT EXISTS revision_${table}_$suffix $event ON $table BEGIN $body END")
    }

    private fun parent(db: SupportSQLiteDatabase, table: String, type: String) {
        fun identity(row: String) = "SELECT $row.user_id AS owner_id, $row.id AS aggregate_id"
        trigger(db, table, "insert", "AFTER INSERT", bump(type, identity("NEW")))
        trigger(db, table, "update_old", "BEFORE UPDATE", bump(type, identity("OLD")))
        trigger(db, table, "update_new", "AFTER UPDATE", bump(type, identity("NEW")))
        trigger(db, table, "delete", "BEFORE DELETE", bump(type, identity("OLD")))
    }

    private fun child(db: SupportSQLiteDatabase, table: String, parent: String, type: String,
                      parentId: String, vararg keys: String) {
        fun identity(row: String) = "SELECT user_id AS owner_id, id AS aggregate_id FROM $parent WHERE id = $row.$parentId"
        val replaced = "SELECT p.user_id AS owner_id, p.id AS aggregate_id FROM $parent p JOIN $table c " +
            "ON p.id = c.$parentId WHERE " + keys.joinToString(" AND ") { "c.$it = NEW.$it" }
        trigger(db, table, "replace_old", "BEFORE INSERT", bump(type, replaced))
        trigger(db, table, "insert", "AFTER INSERT", bump(type, identity("NEW")))
        trigger(db, table, "update_old", "BEFORE UPDATE", bump(type, identity("OLD")))
        trigger(db, table, "update_new", "AFTER UPDATE", bump(type, identity("NEW")))
        trigger(db, table, "delete", "BEFORE DELETE", bump(type, identity("OLD")))
    }
}

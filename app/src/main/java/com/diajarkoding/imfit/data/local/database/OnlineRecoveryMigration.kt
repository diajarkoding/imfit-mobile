package com.diajarkoding.imfit.data.local.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Both historical version7 layouts converge here. Never drop pending metadata or active JSON.
 * Version8 belongs to the separate proof branch and is deliberately not guessed/migrated.
 */
object OnlineRecoveryMigration : Migration(7, 9) {
    private val definitions = linkedMapOf(
        "users" to "`id` TEXT NOT NULL, `name` TEXT NOT NULL, `email` TEXT NOT NULL, `birth_date` TEXT, `profile_photo_uri` TEXT, `sync_status` TEXT NOT NULL, `pending_operation` TEXT, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`)",
        "exercises" to "`id` TEXT NOT NULL, `name` TEXT NOT NULL, `muscle_category_id` INTEGER NOT NULL, `description` TEXT NOT NULL, `image_url` TEXT, `is_active` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`)",
        "workout_templates" to "`id` TEXT NOT NULL, `user_id` TEXT NOT NULL, `name` TEXT NOT NULL, `is_deleted` INTEGER NOT NULL, `sync_status` TEXT NOT NULL, `pending_operation` TEXT, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `remote_revision` INTEGER, PRIMARY KEY(`id`)",
        "template_exercises" to "`template_id` TEXT NOT NULL, `exercise_id` TEXT NOT NULL, `order_index` INTEGER NOT NULL, `sets` INTEGER NOT NULL, `reps` INTEGER NOT NULL, `rest_seconds` INTEGER NOT NULL, `sync_status` TEXT NOT NULL, `pending_operation` TEXT, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`template_id`, `exercise_id`)",
        "workout_logs" to "`id` TEXT NOT NULL, `user_id` TEXT NOT NULL, `template_id` TEXT, `template_name` TEXT NOT NULL, `date` INTEGER NOT NULL, `start_time` INTEGER NOT NULL, `end_time` INTEGER NOT NULL, `total_volume` REAL NOT NULL, `total_sets` INTEGER NOT NULL, `total_reps` INTEGER NOT NULL, `sync_status` TEXT NOT NULL, `pending_operation` TEXT, `deleted_at` INTEGER, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`)",
        "exercise_logs" to "`id` TEXT NOT NULL, `workout_log_id` TEXT NOT NULL, `exercise_id` TEXT NOT NULL, `exercise_name` TEXT NOT NULL, `muscle_category` TEXT NOT NULL, `order_index` INTEGER NOT NULL, `total_volume` REAL NOT NULL, `total_sets` INTEGER NOT NULL, `total_reps` INTEGER NOT NULL, `sync_status` TEXT NOT NULL, `pending_operation` TEXT, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `input_defaults` TEXT, PRIMARY KEY(`id`)",
        "workout_sets" to "`id` TEXT NOT NULL, `exercise_log_id` TEXT NOT NULL, `workout_log_id` TEXT NOT NULL, `exercise_id` TEXT NOT NULL, `set_number` INTEGER NOT NULL, `weight` REAL NOT NULL, `reps` INTEGER NOT NULL, `is_completed` INTEGER NOT NULL, `sync_status` TEXT NOT NULL, `pending_operation` TEXT, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`)",
        "active_sessions" to "`id` TEXT NOT NULL, `user_id` TEXT NOT NULL, `template_id` TEXT NOT NULL, `template_name` TEXT NOT NULL, `start_time` INTEGER NOT NULL, `current_exercise_index` INTEGER NOT NULL, `session_data_json` TEXT NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `is_paused` INTEGER NOT NULL DEFAULT 0, `total_paused_time_ms` INTEGER NOT NULL DEFAULT 0, `last_pause_time` INTEGER, `rest_timer_end_time` INTEGER NOT NULL DEFAULT 0, `rest_timer_exercise_name` TEXT, `session_rest_override` INTEGER, PRIMARY KEY(`id`)",
    )

    override fun migrate(db: SupportSQLiteDatabase) {
        for ((table, definition) in definitions) {
            val original = columns(db, table)
            check(original.isNotEmpty()) { "Missing historical table: $table" }
            val temporary = "online_recovery_$table"
            db.execSQL("CREATE TABLE `$temporary` ($definition)")
            val target = columns(db, temporary)
            check(original.keys.all { it in target }) { "Unknown historical columns in $table; preserve database for review" }
            val fields = target.keys.joinToString { "`$it`" }
            val expressions = target.map { (name, required) ->
                when {
                    name in original -> if (required && name in setOf("created_at", "updated_at")) "COALESCE(`$name`, 0)" else "`$name`"
                    name == "sync_status" -> "'SYNC_FAILED'"
                    name == "pending_operation" -> "'REVIEW'"
                    !required -> "NULL"
                    else -> error("Unrecognized required column $table.$name; no destructive fallback")
                }
            }.joinToString()
            db.execSQL("INSERT INTO `$temporary` ($fields) SELECT $expressions FROM `$table`")
            check(count(db, temporary) == count(db, table)) { "Recovery row count mismatch" }
            // Room runs this migration in a transaction: any validation failure rolls back the copy.
            db.execSQL("DROP TABLE `$table`")
            db.execSQL("ALTER TABLE `$temporary` RENAME TO `$table`")
        }
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_exercise_logs_workout_log_id` ON `exercise_logs` (`workout_log_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_exercise_logs_exercise_id` ON `exercise_logs` (`exercise_id`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `template_write_intents` (`key` TEXT NOT NULL, `user_id` TEXT NOT NULL, `template_id` TEXT NOT NULL, `payload` TEXT NOT NULL, PRIMARY KEY(`key`))")
    }

    private fun columns(db: SupportSQLiteDatabase, table: String): Map<String, Boolean> =
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            buildMap {
                while (cursor.moveToNext()) put(cursor.getString(cursor.getColumnIndexOrThrow("name")), cursor.getInt(cursor.getColumnIndexOrThrow("notnull")) != 0)
            }
        }

    private fun count(db: SupportSQLiteDatabase, table: String): Long =
        db.query("SELECT COUNT(*) FROM `$table`").use { check(it.moveToFirst()); it.getLong(0) }
}

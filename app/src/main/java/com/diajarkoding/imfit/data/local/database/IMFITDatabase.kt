package com.diajarkoding.imfit.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.withTransaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.diajarkoding.imfit.data.local.dao.ActiveSessionDao
import com.diajarkoding.imfit.data.local.dao.ExerciseDao
import com.diajarkoding.imfit.data.local.dao.ExerciseLogDao
import com.diajarkoding.imfit.data.local.dao.TemplateExerciseDao
import com.diajarkoding.imfit.data.local.dao.UserDao
import com.diajarkoding.imfit.data.local.dao.WorkoutLogDao
import com.diajarkoding.imfit.data.local.dao.WorkoutSetDao
import com.diajarkoding.imfit.data.local.dao.WorkoutTemplateDao
import com.diajarkoding.imfit.data.local.entity.ActiveSessionEntity
import com.diajarkoding.imfit.data.local.entity.ExerciseEntity
import com.diajarkoding.imfit.data.local.entity.ExerciseLogEntity
import com.diajarkoding.imfit.data.local.entity.TemplateExerciseEntity
import com.diajarkoding.imfit.data.local.entity.UserEntity
import com.diajarkoding.imfit.data.local.entity.WorkoutLogEntity
import com.diajarkoding.imfit.data.local.entity.WorkoutSetEntity
import com.diajarkoding.imfit.data.local.entity.WorkoutTemplateEntity

@Database(
    entities = [
        UserEntity::class,
        ExerciseEntity::class,
        WorkoutTemplateEntity::class,
        TemplateExerciseEntity::class,
        WorkoutLogEntity::class,
        ExerciseLogEntity::class,
        WorkoutSetEntity::class,
        ActiveSessionEntity::class
    ],
    version = 8,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class IMFITDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun exerciseDao(): ExerciseDao
    abstract fun workoutTemplateDao(): WorkoutTemplateDao
    abstract fun templateExerciseDao(): TemplateExerciseDao
    abstract fun workoutLogDao(): WorkoutLogDao
    abstract fun exerciseLogDao(): ExerciseLogDao
    abstract fun workoutSetDao(): WorkoutSetDao
    abstract fun activeSessionDao(): ActiveSessionDao

    suspend fun deleteUserData(userId: String) = withTransaction {
        workoutSetDao().deleteSetsByUser(userId)
        exerciseLogDao().deleteExerciseLogsByUser(userId)
        workoutLogDao().deleteWorkoutLogsByUser(userId)
        // Legacy routines are an owner-scoped recovery archive, not cached server data.
        // Preserve originals across logout; only that owner can access their export after login.
        activeSessionDao().deleteSession(userId)
        userDao().deleteUserById(userId)
    }

    companion object {
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE exercise_logs ADD COLUMN input_defaults TEXT")
            }
        }
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Recreate template_exercises to allow nullable reps and add typed target columns
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `template_exercises_new` (
                        `template_id` TEXT NOT NULL,
                        `exercise_id` TEXT NOT NULL,
                        `order_index` INTEGER NOT NULL,
                        `sets` INTEGER NOT NULL,
                        `reps` INTEGER,
                        `rest_seconds` INTEGER NOT NULL,
                        `sync_status` TEXT NOT NULL,
                        `pending_operation` TEXT,
                        `created_at` INTEGER NOT NULL,
                        `updated_at` INTEGER NOT NULL,
                        `tracking_type` TEXT NOT NULL DEFAULT 'WEIGHT_REPS',
                        `target_duration_seconds` INTEGER,
                        `target_distance_meters` INTEGER,
                        PRIMARY KEY(`template_id`, `exercise_id`)
                    )
                """.trimIndent())
                database.execSQL("""
                    INSERT INTO `template_exercises_new` (
                        `template_id`, `exercise_id`, `order_index`, `sets`, `reps`, `rest_seconds`,
                        `sync_status`, `pending_operation`, `created_at`, `updated_at`,
                        `tracking_type`, `target_duration_seconds`, `target_distance_meters`
                    )
                    SELECT
                        `template_id`, `exercise_id`, `order_index`, `sets`, `reps`, `rest_seconds`,
                        `sync_status`, `pending_operation`, `created_at`, `updated_at`,
                        'WEIGHT_REPS', NULL, NULL
                    FROM `template_exercises`
                """.trimIndent())
                database.execSQL("DROP TABLE `template_exercises`")
                database.execSQL("ALTER TABLE `template_exercises_new` RENAME TO `template_exercises`")

                // workout_sets
                database.execSQL("ALTER TABLE `workout_sets` ADD COLUMN `duration_seconds` INTEGER")
                database.execSQL("ALTER TABLE `workout_sets` ADD COLUMN `distance_meters` INTEGER")
                database.execSQL("ALTER TABLE `workout_sets` ADD COLUMN `additional_weight` REAL")
                database.execSQL("ALTER TABLE `workout_sets` ADD COLUMN `assistance_weight` REAL")

                // exercise_logs
                database.execSQL("ALTER TABLE `exercise_logs` ADD COLUMN `tracking_type` TEXT NOT NULL DEFAULT 'WEIGHT_REPS'")
                database.execSQL("ALTER TABLE `exercise_logs` ADD COLUMN `modality` TEXT NOT NULL DEFAULT 'STRENGTH'")
                database.execSQL("ALTER TABLE `exercise_logs` ADD COLUMN `total_duration_seconds` INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE `exercise_logs` ADD COLUMN `total_distance_meters` INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE `exercise_logs` ADD COLUMN `additional_volume` REAL NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE `exercise_logs` ADD COLUMN `metric_quality` TEXT NOT NULL DEFAULT 'LEGACY_UNVERIFIED'")

                // workout_logs
                database.execSQL("ALTER TABLE `workout_logs` ADD COLUMN `total_duration_seconds` INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE `workout_logs` ADD COLUMN `total_distance_meters` INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE `workout_logs` ADD COLUMN `additional_volume` REAL NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE `workout_logs` ADD COLUMN `data_quality` TEXT NOT NULL DEFAULT 'LEGACY_UNVERIFIED'")

                // exercises
                database.execSQL("ALTER TABLE `exercises` ADD COLUMN `default_tracking_type` TEXT NOT NULL DEFAULT 'WEIGHT_REPS'")
                database.execSQL("ALTER TABLE `exercises` ADD COLUMN `modality` TEXT NOT NULL DEFAULT 'STRENGTH'")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE active_sessions ADD COLUMN is_paused INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE active_sessions ADD COLUMN total_paused_time_ms INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE active_sessions ADD COLUMN last_pause_time INTEGER")
            }
        }
        
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE active_sessions ADD COLUMN rest_timer_end_time INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE active_sessions ADD COLUMN rest_timer_exercise_name TEXT")
                database.execSQL("ALTER TABLE active_sessions ADD COLUMN session_rest_override INTEGER")
            }
        }
    }
}

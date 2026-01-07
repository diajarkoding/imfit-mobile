package com.diajarkoding.imfit.data.local.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
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
    version = 7,
    exportSchema = false
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

    companion object {
        @Volatile
        private var INSTANCE: IMFITDatabase? = null

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

        /**
         * Migration from offline-first to online-first architecture.
         * Removes sync_status and pending_operation columns from all entities.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Migrate workout_templates
                database.execSQL("CREATE TABLE workout_templates_new (id TEXT PRIMARY KEY NOT NULL, user_id TEXT NOT NULL, name TEXT NOT NULL, is_deleted INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
                database.execSQL("INSERT INTO workout_templates_new (id, user_id, name, is_deleted, created_at, updated_at) SELECT id, user_id, name, is_deleted, created_at, updated_at FROM workout_templates")
                database.execSQL("DROP TABLE workout_templates")
                database.execSQL("ALTER TABLE workout_templates_new RENAME TO workout_templates")

                // Migrate workout_logs
                database.execSQL("CREATE TABLE workout_logs_new (id TEXT PRIMARY KEY NOT NULL, user_id TEXT NOT NULL, template_id TEXT, template_name TEXT NOT NULL, date INTEGER NOT NULL, start_time INTEGER NOT NULL, end_time INTEGER NOT NULL, total_volume REAL NOT NULL, total_sets INTEGER NOT NULL DEFAULT 0, total_reps INTEGER NOT NULL DEFAULT 0, deleted_at INTEGER, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
                database.execSQL("INSERT INTO workout_logs_new (id, user_id, template_id, template_name, date, start_time, end_time, total_volume, total_sets, total_reps, deleted_at, created_at, updated_at) SELECT id, user_id, template_id, template_name, date, start_time, end_time, total_volume, total_sets, total_reps, deleted_at, created_at, updated_at FROM workout_logs")
                database.execSQL("DROP TABLE workout_logs")
                database.execSQL("ALTER TABLE workout_logs_new RENAME TO workout_logs")

                // Migrate template_exercises
                database.execSQL("CREATE TABLE template_exercises_new (template_id TEXT NOT NULL, exercise_id TEXT NOT NULL, order_index INTEGER NOT NULL, sets INTEGER NOT NULL, reps INTEGER NOT NULL, rest_seconds INTEGER NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY(template_id, exercise_id))")
                database.execSQL("INSERT INTO template_exercises_new (template_id, exercise_id, order_index, sets, reps, rest_seconds, created_at, updated_at) SELECT template_id, exercise_id, order_index, sets, reps, rest_seconds, created_at, updated_at FROM template_exercises")
                database.execSQL("DROP TABLE template_exercises")
                database.execSQL("ALTER TABLE template_exercises_new RENAME TO template_exercises")

                // Migrate exercise_logs
                database.execSQL("CREATE TABLE exercise_logs_new (id TEXT PRIMARY KEY NOT NULL, workout_log_id TEXT NOT NULL, exercise_id TEXT NOT NULL, exercise_name TEXT NOT NULL, muscle_category TEXT NOT NULL, order_index INTEGER NOT NULL, total_volume REAL NOT NULL, total_sets INTEGER NOT NULL, total_reps INTEGER NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
                database.execSQL("INSERT INTO exercise_logs_new (id, workout_log_id, exercise_id, exercise_name, muscle_category, order_index, total_volume, total_sets, total_reps, created_at, updated_at) SELECT id, workout_log_id, exercise_id, exercise_name, muscle_category, order_index, total_volume, total_sets, total_reps, created_at, updated_at FROM exercise_logs")
                database.execSQL("DROP TABLE exercise_logs")
                database.execSQL("ALTER TABLE exercise_logs_new RENAME TO exercise_logs")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_exercise_logs_workout_log_id ON exercise_logs(workout_log_id)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_exercise_logs_exercise_id ON exercise_logs(exercise_id)")

                // Migrate workout_sets
                database.execSQL("CREATE TABLE workout_sets_new (id TEXT PRIMARY KEY NOT NULL, exercise_log_id TEXT NOT NULL, workout_log_id TEXT NOT NULL, exercise_id TEXT NOT NULL, set_number INTEGER NOT NULL, weight REAL NOT NULL, reps INTEGER NOT NULL, is_completed INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
                database.execSQL("INSERT INTO workout_sets_new (id, exercise_log_id, workout_log_id, exercise_id, set_number, weight, reps, is_completed, created_at, updated_at) SELECT id, exercise_log_id, workout_log_id, exercise_id, set_number, weight, reps, is_completed, created_at, updated_at FROM workout_sets")
                database.execSQL("DROP TABLE workout_sets")
                database.execSQL("ALTER TABLE workout_sets_new RENAME TO workout_sets")

                // Migrate users
                database.execSQL("CREATE TABLE users_new (id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, email TEXT NOT NULL, birth_date TEXT, profile_photo_uri TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
                database.execSQL("INSERT INTO users_new (id, name, email, birth_date, profile_photo_uri, created_at, updated_at) SELECT id, name, email, birth_date, profile_photo_uri, created_at, updated_at FROM users")
                database.execSQL("DROP TABLE users")
                database.execSQL("ALTER TABLE users_new RENAME TO users")
            }
        }

        fun getDatabase(context: Context): IMFITDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    IMFITDatabase::class.java,
                    "imfit_database"
                )
                    .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
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
    version = 6,
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

        fun getDatabase(context: Context): IMFITDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    IMFITDatabase::class.java,
                    "imfit_database"
                )
                    .addMigrations(MIGRATION_4_5, MIGRATION_5_6)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
package com.diajarkoding.imfit.data.local.database

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import android.content.Context
import com.diajarkoding.imfit.data.local.dao.*
import com.diajarkoding.imfit.data.local.entity.*

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
    version = 2,
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

        fun getDatabase(context: Context): IMFITDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    IMFITDatabase::class.java,
                    "imfit_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
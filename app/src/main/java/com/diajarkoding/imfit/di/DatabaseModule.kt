package com.diajarkoding.imfit.di

import android.content.Context
import androidx.room.Room
import com.diajarkoding.imfit.data.local.database.IMFITDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideIMFITDatabase(
        @ApplicationContext context: Context
    ): IMFITDatabase {
        return Room.databaseBuilder(
            context,
            IMFITDatabase::class.java,
            "imfit_database"
        )
        .fallbackToDestructiveMigration()
        .build()
    }

    @Provides
    fun provideUserDao(database: IMFITDatabase) = database.userDao()

    @Provides
    fun provideExerciseDao(database: IMFITDatabase) = database.exerciseDao()

    @Provides
    fun provideWorkoutTemplateDao(database: IMFITDatabase) = database.workoutTemplateDao()

    @Provides
    fun provideTemplateExerciseDao(database: IMFITDatabase) = database.templateExerciseDao()

    @Provides
    fun provideWorkoutLogDao(database: IMFITDatabase) = database.workoutLogDao()

    @Provides
    fun provideExerciseLogDao(database: IMFITDatabase) = database.exerciseLogDao()

    @Provides
    fun provideWorkoutSetDao(database: IMFITDatabase) = database.workoutSetDao()

    @Provides
    fun provideActiveSessionDao(database: IMFITDatabase) = database.activeSessionDao()
}
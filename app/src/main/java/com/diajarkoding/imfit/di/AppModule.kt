package com.diajarkoding.imfit.di

import com.diajarkoding.imfit.core.data.WorkoutPreferences
import com.diajarkoding.imfit.core.network.NetworkMonitor
import com.diajarkoding.imfit.data.local.dao.ActiveSessionDao
import com.diajarkoding.imfit.data.local.dao.ExerciseDao
import com.diajarkoding.imfit.data.local.dao.ExerciseLogDao
import com.diajarkoding.imfit.data.local.dao.TemplateExerciseDao
import com.diajarkoding.imfit.data.local.dao.WorkoutLogDao
import com.diajarkoding.imfit.data.local.dao.WorkoutSetDao
import com.diajarkoding.imfit.data.local.dao.WorkoutTemplateDao
import com.diajarkoding.imfit.data.local.database.IMFITDatabase
import com.diajarkoding.imfit.data.repository.AuthRepositoryImpl
import com.diajarkoding.imfit.data.repository.ExerciseRepositoryImpl
import com.diajarkoding.imfit.data.repository.WorkoutLogRepository
import com.diajarkoding.imfit.data.repository.WorkoutRepositoryImpl
import com.diajarkoding.imfit.data.repository.WorkoutSessionManager
import com.diajarkoding.imfit.data.repository.WorkoutTemplateRepository
import com.diajarkoding.imfit.domain.repository.AuthRepository
import com.diajarkoding.imfit.domain.repository.ExerciseRepository
import com.diajarkoding.imfit.domain.repository.WorkoutRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jan.supabase.SupabaseClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    @Singleton
    abstract fun bindAuthRepository(impl: AuthRepositoryImpl): AuthRepository

    @Binds
    @Singleton
    abstract fun bindExerciseRepository(impl: ExerciseRepositoryImpl): ExerciseRepository

    @Binds
    @Singleton
    abstract fun bindWorkoutRepository(impl: WorkoutRepositoryImpl): WorkoutRepository

    companion object {
        @Provides
        @Singleton
        fun provideWorkoutTemplateRepository(
            supabaseClient: SupabaseClient,
            workoutTemplateDao: WorkoutTemplateDao,
            templateExerciseDao: TemplateExerciseDao,
            exerciseDao: ExerciseDao,
            networkMonitor: NetworkMonitor
        ): WorkoutTemplateRepository {
            return WorkoutTemplateRepository(
                supabaseClient,
                workoutTemplateDao,
                templateExerciseDao,
                exerciseDao,
                networkMonitor
            )
        }

        @Provides
        @Singleton
        fun provideWorkoutSessionManager(
            supabaseClient: SupabaseClient,
            activeSessionDao: ActiveSessionDao,
            workoutPreferences: WorkoutPreferences
        ): WorkoutSessionManager {
            return WorkoutSessionManager(
                supabaseClient,
                activeSessionDao,
                workoutPreferences
            )
        }

        @Provides
        @Singleton
        fun provideWorkoutLogRepository(
            supabaseClient: SupabaseClient,
            database: IMFITDatabase,
            workoutLogDao: WorkoutLogDao,
            exerciseLogDao: ExerciseLogDao,
            workoutSetDao: WorkoutSetDao,
            exerciseDao: ExerciseDao
        ): WorkoutLogRepository {
            return WorkoutLogRepository(
                supabaseClient,
                database,
                workoutLogDao,
                exerciseLogDao,
                workoutSetDao,
                exerciseDao
            )
        }
    }
}

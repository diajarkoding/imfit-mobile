package com.diajarkoding.imfit.di

import com.diajarkoding.imfit.data.repository.DemoAuthRepository
import com.diajarkoding.imfit.data.repository.DemoExerciseRepository
import com.diajarkoding.imfit.data.repository.DemoWorkoutRepository
import com.diajarkoding.imfit.data.sync.DemoSyncStateProvider
import com.diajarkoding.imfit.data.sync.SyncScheduler
import com.diajarkoding.imfit.data.sync.SyncRunner
import com.diajarkoding.imfit.data.sync.SyncStateProvider
import com.diajarkoding.imfit.domain.repository.AuthRepository
import com.diajarkoding.imfit.domain.repository.ExerciseRepository
import com.diajarkoding.imfit.domain.repository.WorkoutRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    @Singleton
    abstract fun bindAuthRepository(impl: DemoAuthRepository): AuthRepository

    @Binds
    @Singleton
    abstract fun bindExerciseRepository(impl: DemoExerciseRepository): ExerciseRepository

    @Binds
    @Singleton
    abstract fun bindWorkoutRepository(impl: DemoWorkoutRepository): WorkoutRepository

    @Binds
    @Singleton
    abstract fun bindSyncStateProvider(impl: DemoSyncStateProvider): SyncStateProvider

    @Binds
    @Singleton
    abstract fun bindSyncScheduler(impl: DemoSyncStateProvider): SyncScheduler

    @Binds
    @Singleton
    abstract fun bindSyncRunner(impl: DemoSyncStateProvider): SyncRunner
}

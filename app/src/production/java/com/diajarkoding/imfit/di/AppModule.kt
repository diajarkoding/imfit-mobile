package com.diajarkoding.imfit.di

import com.diajarkoding.imfit.data.repository.AuthRepositoryImpl
import com.diajarkoding.imfit.data.auth.AuthDeepLinkHandler
import com.diajarkoding.imfit.data.auth.SupabaseAuthDeepLinkHandler
import com.diajarkoding.imfit.data.repository.ExerciseRepositoryImpl
import com.diajarkoding.imfit.data.repository.WorkoutRepositoryImpl
import com.diajarkoding.imfit.data.remote.ImfitAggregateRemoteDataSource
import com.diajarkoding.imfit.data.sync.SyncManager
import com.diajarkoding.imfit.data.sync.SyncScheduler
import com.diajarkoding.imfit.data.sync.SyncRunner
import com.diajarkoding.imfit.data.sync.SyncLifecycleController
import com.diajarkoding.imfit.data.sync.WorkManagerSyncScheduler
import com.diajarkoding.imfit.data.sync.SyncStateProvider
import com.diajarkoding.imfit.domain.repository.AuthRepository
import com.diajarkoding.imfit.domain.repository.ExerciseRepository
import com.diajarkoding.imfit.domain.repository.WorkoutRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.Provides
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    companion object {
        @Provides
        @Singleton
        fun provideAggregateRemoteDataSource(
            supabase: io.github.jan.supabase.SupabaseClient,
        ) = ImfitAggregateRemoteDataSource(supabase)
    }

    @Binds
    @Singleton
    abstract fun bindAuthRepository(impl: AuthRepositoryImpl): AuthRepository

    @Binds
    @Singleton
    abstract fun bindExerciseRepository(impl: ExerciseRepositoryImpl): ExerciseRepository

    @Binds
    @Singleton
    abstract fun bindWorkoutRepository(impl: WorkoutRepositoryImpl): WorkoutRepository

    @Binds
    @Singleton
    abstract fun bindSyncStateProvider(impl: SyncManager): SyncStateProvider

    @Binds
    @Singleton
    abstract fun bindSyncScheduler(impl: WorkManagerSyncScheduler): SyncScheduler

    @Binds
    @Singleton
    abstract fun bindSyncRunner(impl: SyncManager): SyncRunner

    @Binds
    @Singleton
    abstract fun bindSyncLifecycleController(impl: SyncManager): SyncLifecycleController

    @Binds
    @Singleton
    abstract fun bindAuthDeepLinkHandler(impl: SupabaseAuthDeepLinkHandler): AuthDeepLinkHandler
}

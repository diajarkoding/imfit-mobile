package com.diajarkoding.imfit.di

import com.diajarkoding.imfit.data.repository.DemoAuthRepository
import com.diajarkoding.imfit.data.auth.AuthDeepLinkHandler
import com.diajarkoding.imfit.data.auth.DemoAuthDeepLinkHandler
import com.diajarkoding.imfit.data.repository.DemoExerciseRepository
import com.diajarkoding.imfit.data.repository.DemoWorkoutRepository
import com.diajarkoding.imfit.data.remote.WorkoutRemoteDataSource
import com.diajarkoding.imfit.data.remote.dto.WorkoutLogDto
import com.diajarkoding.imfit.data.sync.DemoSyncStateProvider
import com.diajarkoding.imfit.data.sync.SyncScheduler
import com.diajarkoding.imfit.data.sync.SyncRunner
import com.diajarkoding.imfit.data.sync.SyncStateProvider
import com.diajarkoding.imfit.data.sync.SyncLifecycleController
import com.diajarkoding.imfit.domain.repository.AuthRepository
import com.diajarkoding.imfit.domain.repository.ExerciseRepository
import com.diajarkoding.imfit.domain.repository.WorkoutRepository
import com.diajarkoding.imfit.domain.repository.RoutineRepository
import com.diajarkoding.imfit.domain.repository.ExerciseCatalogRepository
import com.diajarkoding.imfit.data.repository.DemoRoutineRepository
import com.diajarkoding.imfit.data.repository.DemoExerciseCatalogRepository
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
        /** Demo routines have never been persisted in Room; there is no legacy upload queue. */
        @Provides
        @Singleton
        fun provideLegacyRoutineRecovery(): com.diajarkoding.imfit.data.recovery.LegacyRoutineRecovery =
            object : com.diajarkoding.imfit.data.recovery.LegacyRoutineRecovery {
                override fun observeCount(ownerId: String) = kotlinx.coroutines.flow.flowOf(0)
                override suspend fun export(ownerId: String): String =
                    kotlinx.serialization.json.buildJsonObject {
                        put("format", kotlinx.serialization.json.JsonPrimitive("imfit-legacy-routine-recovery"))
                        put("version", kotlinx.serialization.json.JsonPrimitive(1))
                        put("owner_id", kotlinx.serialization.json.JsonPrimitive(ownerId))
                        put("routines", kotlinx.serialization.json.JsonArray(emptyList()))
                    }.toString()
            }

        @Provides
        @Singleton
        fun provideWorkoutRemoteDataSource(): WorkoutRemoteDataSource = object : WorkoutRemoteDataSource {
            override suspend fun fetchLatestCompletedWorkoutLog(userId: String): WorkoutLogDto? = null
        }
    }
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
    abstract fun bindRoutineRepository(impl: DemoRoutineRepository): RoutineRepository

    @Binds
    @Singleton
    abstract fun bindExerciseCatalogRepository(impl: DemoExerciseCatalogRepository): ExerciseCatalogRepository

    @Binds
    @Singleton
    abstract fun bindSyncStateProvider(impl: DemoSyncStateProvider): SyncStateProvider

    @Binds
    @Singleton
    abstract fun bindSyncScheduler(impl: DemoSyncStateProvider): SyncScheduler

    @Binds
    @Singleton
    abstract fun bindSyncRunner(impl: DemoSyncStateProvider): SyncRunner

    @Binds
    @Singleton
    abstract fun bindSyncLifecycleController(impl: DemoSyncStateProvider): SyncLifecycleController

    @Binds
    @Singleton
    abstract fun bindAuthDeepLinkHandler(impl: DemoAuthDeepLinkHandler): AuthDeepLinkHandler
}

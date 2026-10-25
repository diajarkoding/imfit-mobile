package com.diajarkoding.imfit.di

import com.diajarkoding.imfit.core.timer.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import dagger.hilt.EntryPoint
import com.diajarkoding.imfit.domain.repository.AuthRepository
import com.diajarkoding.imfit.domain.repository.WorkoutRepository

/** Access to the app's real singleton graph for Android integration checks. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface RestTimerEntryPoint {
    fun timer(): RestTimerCoordinator
    fun workouts(): WorkoutRepository
    fun auth(): AuthRepository
    fun runtime(): RestTimerAndroidRuntime
}

@Module
@InstallIn(SingletonComponent::class)
object RestTimerModule {
    @Provides @Singleton
    fun coordinator(store: AndroidRestTimerStore, clock: AndroidMonotonicClock, runtime: RestTimerAndroidRuntime) =
        RestTimerCoordinator(store, clock, runtime)
}

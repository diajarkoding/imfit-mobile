package com.diajarkoding.imfit.di

import com.diajarkoding.imfit.core.timer.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import dagger.hilt.EntryPoint
import com.diajarkoding.imfit.domain.repository.AuthRepository

/** Access to the app's real singleton graph for Android integration checks. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface RestTimerEntryPoint {
    fun timer(): RestTimerCoordinator
    fun sessions(): com.diajarkoding.imfit.data.remote.online.OnlineSessionStore
    fun auth(): AuthRepository
    fun runtime(): RestTimerAndroidRuntime
    fun reads(): com.diajarkoding.imfit.data.remote.online.RemoteReadGateway
    fun edits(): com.diajarkoding.imfit.data.remote.online.TemplateEditBridge
}

@Module
@InstallIn(SingletonComponent::class)
object RestTimerModule {
    @Provides @Singleton
    fun coordinator(store: AndroidRestTimerStore, clock: AndroidMonotonicClock, runtime: RestTimerAndroidRuntime) =
        RestTimerCoordinator(store, clock, runtime)
}

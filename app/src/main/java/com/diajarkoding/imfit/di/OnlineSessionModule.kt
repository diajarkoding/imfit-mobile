package com.diajarkoding.imfit.di

import com.diajarkoding.imfit.data.legacy.LegacyGuardedSessionStart
import com.diajarkoding.imfit.data.remote.online.OnlineRepository
import com.diajarkoding.imfit.data.remote.online.OnlineMutationChanges
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.diajarkoding.imfit.data.remote.online.OnlineSessionStore
import com.diajarkoding.imfit.data.remote.online.RemoteReadGateway
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Confirmed remote session state shared by UI, notification and timer service. */
@Module
@InstallIn(SingletonComponent::class)
object OnlineSessionModule {
    @Provides @Singleton fun guardedStart(preflight: com.diajarkoding.imfit.data.legacy.LegacyStartPreflight, repository: OnlineRepository) =
        LegacyGuardedSessionStart(preflight, repository)

    @Provides @Singleton fun sessionStore(
        reads: RemoteReadGateway,
        repository: OnlineRepository,
        guarded: LegacyGuardedSessionStart,
        changes: OnlineMutationChanges,
    ) = OnlineSessionStore(reads, repository, guarded,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default), changes = changes)
}

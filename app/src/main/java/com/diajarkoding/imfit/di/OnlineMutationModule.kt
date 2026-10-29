package com.diajarkoding.imfit.di

import com.diajarkoding.imfit.data.remote.online.OnlineMutationChanges
import com.diajarkoding.imfit.data.remote.online.RemoteReadGateway
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object OnlineMutationModule {
    @Provides
    @Singleton
    fun changes(reads: RemoteReadGateway): OnlineMutationChanges = OnlineMutationChanges(reads.auth)
}

package com.diajarkoding.imfit.di

import com.diajarkoding.imfit.data.remote.online.DemoReadGateway
import com.diajarkoding.imfit.data.remote.online.RemoteReadGateway
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import dagger.Provides
import com.diajarkoding.imfit.data.remote.online.LegacyTemplateGuard

@Module
@InstallIn(SingletonComponent::class)
abstract class RemoteReadModule {
    @Binds @Singleton abstract fun reads(implementation: DemoReadGateway): RemoteReadGateway
    companion object {
        @Provides @Singleton fun onlineRepository(reads: DemoReadGateway): com.diajarkoding.imfit.data.remote.online.OnlineRepository = reads.repository
        @Provides @Singleton fun legacyGuard() = LegacyTemplateGuard { _, _ -> }
    }
}

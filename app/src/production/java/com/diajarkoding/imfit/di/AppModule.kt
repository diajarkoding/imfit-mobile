package com.diajarkoding.imfit.di

import com.diajarkoding.imfit.data.repository.AuthRepositoryImpl
import com.diajarkoding.imfit.data.auth.AuthDeepLinkHandler
import com.diajarkoding.imfit.data.auth.SupabaseAuthDeepLinkHandler
import com.diajarkoding.imfit.domain.repository.AuthRepository
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
    abstract fun bindAuthRepository(impl: AuthRepositoryImpl): AuthRepository


    @Binds
    @Singleton
    abstract fun bindWorkoutRepository(impl: com.diajarkoding.imfit.data.repository.OnlineWorkoutRepository): WorkoutRepository


    @Binds
    @Singleton
    abstract fun bindAuthDeepLinkHandler(impl: SupabaseAuthDeepLinkHandler): AuthDeepLinkHandler
}

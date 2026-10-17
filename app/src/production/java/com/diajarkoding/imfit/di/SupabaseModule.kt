package com.diajarkoding.imfit.di

import com.diajarkoding.imfit.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.FlowType
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SupabaseModule {

    @Provides
    @Singleton
    fun provideSupabaseClient(): SupabaseClient {
        return createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_ANON_KEY
        ) {
            install(Auth) {
                flowType = FlowType.PKCE
                scheme = "imfit"
                host = "auth"
                // The rest timer validates the session from background alarm/receiver/worker paths.
                // The SDK's lifecycle callbacks reset auth to Initializing on every backgrounding
                // (and only re-load on foreground), which hangs awaitInitialization() behind the
                // timer's bounded validation and silently drops background expiry. Auth state must
                // stay stable regardless of foreground/background; logout and account switches are
                // still observed explicitly (logout cancels the timer synchronously, and any loaded
                // identity mismatch cancels on the next check).
                enableLifecycleCallbacks = false
            }
            install(Postgrest)
            install(Storage)
        }
    }
}

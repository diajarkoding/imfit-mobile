package com.diajarkoding.imfit.di

import androidx.annotation.Keep
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jan.supabase.SupabaseClient

/** In-process access for offline integration checks. No exported component or test intent. */
@Keep
@EntryPoint
@InstallIn(SingletonComponent::class)
interface LocalSessionEntryPoint {
    fun supabase(): SupabaseClient
}

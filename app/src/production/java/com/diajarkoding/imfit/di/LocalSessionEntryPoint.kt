package com.diajarkoding.imfit.di

import androidx.annotation.Keep
import com.diajarkoding.imfit.data.local.database.IMFITDatabase
import com.diajarkoding.imfit.data.sync.SyncRunner
import com.diajarkoding.imfit.domain.repository.ExerciseCatalogRepository
import com.diajarkoding.imfit.domain.repository.RoutineRepository
import com.diajarkoding.imfit.domain.repository.WorkoutRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jan.supabase.SupabaseClient

/** In-process access for opt-in integration checks. No exported component or test intent. */
@Keep
@EntryPoint
@InstallIn(SingletonComponent::class)
interface LocalSessionEntryPoint {
    fun database(): IMFITDatabase
    fun supabase(): SupabaseClient
    fun routines(): RoutineRepository
    fun catalog(): ExerciseCatalogRepository
    fun workouts(): WorkoutRepository
    fun sync(): SyncRunner
}

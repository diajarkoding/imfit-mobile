 package com.diajarkoding.imfit.core.data
 
 import android.content.Context
 import androidx.datastore.core.DataStore
 import androidx.datastore.preferences.core.Preferences
 import androidx.datastore.preferences.core.edit
 import androidx.datastore.preferences.core.intPreferencesKey
 import androidx.datastore.preferences.preferencesDataStore
 import dagger.hilt.android.qualifiers.ApplicationContext
 import kotlinx.coroutines.flow.Flow
 import kotlinx.coroutines.flow.first
 import kotlinx.coroutines.flow.map
 import javax.inject.Inject
 import javax.inject.Singleton
 
 private val Context.workoutDataStore: DataStore<Preferences> by preferencesDataStore(name = "imfit_workout_preferences")
 
 @Singleton
 class WorkoutPreferences @Inject constructor(
     @ApplicationContext private val context: Context
 ) {
     private val defaultRestTimerKey = intPreferencesKey("default_rest_timer_seconds")
 
     val defaultRestTimerSeconds: Flow<Int?> = context.workoutDataStore.data.map { preferences ->
         preferences[defaultRestTimerKey]
     }
 
     suspend fun getDefaultRestTimerSeconds(): Int? {
         return context.workoutDataStore.data.first()[defaultRestTimerKey]
     }
 
     suspend fun setDefaultRestTimerSeconds(seconds: Int) {
         context.workoutDataStore.edit { preferences ->
             preferences[defaultRestTimerKey] = seconds
         }
     }
 
     suspend fun clearDefaultRestTimer() {
         context.workoutDataStore.edit { preferences ->
             preferences.remove(defaultRestTimerKey)
         }
     }
 }

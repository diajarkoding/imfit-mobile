package com.diajarkoding.imfit.data.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncPreferences @Inject constructor(
    @ApplicationContext context: Context
) {
    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
        migrations = listOf(SharedPreferencesMigration(context, LEGACY_PREFS_NAME)),
        produceFile = { context.preferencesDataStoreFile(DATASTORE_NAME) }
    )

    suspend fun getLastSyncTimestamp(userId: String): Long =
        dataStore.data.first()[longKey(KEY_LAST_SYNC_TIMESTAMP, userId)] ?: 0L

    suspend fun setLastSyncTimestamp(userId: String, value: Long) = setLong(
        KEY_LAST_SYNC_TIMESTAMP,
        userId,
        value
    )

    suspend fun setLastTemplatesSyncTimestamp(userId: String, value: Long) = setLong(
        KEY_LAST_TEMPLATES_SYNC,
        userId,
        value
    )

    suspend fun setLastWorkoutLogsSyncTimestamp(userId: String, value: Long) = setLong(
        KEY_LAST_WORKOUT_LOGS_SYNC,
        userId,
        value
    )

    suspend fun setLastExercisesSyncTimestamp(userId: String, value: Long) = setLong(
        KEY_LAST_EXERCISES_SYNC,
        userId,
        value
    )

    suspend fun isInitialSyncCompleted(userId: String): Boolean =
        dataStore.data.first()[booleanKey(KEY_INITIAL_SYNC_COMPLETED, userId)] ?: false

    suspend fun setInitialSyncCompleted(userId: String, value: Boolean) {
        dataStore.edit { preferences ->
            preferences[booleanKey(KEY_INITIAL_SYNC_COMPLETED, userId)] = value
        }
    }

    suspend fun clearAll() {
        dataStore.edit { it.clear() }
    }

    suspend fun clearForUser(userId: String) {
        dataStore.edit { preferences ->
            preferences.remove(longKey(KEY_LAST_SYNC_TIMESTAMP, userId))
            preferences.remove(longKey(KEY_LAST_TEMPLATES_SYNC, userId))
            preferences.remove(longKey(KEY_LAST_WORKOUT_LOGS_SYNC, userId))
            preferences.remove(longKey(KEY_LAST_EXERCISES_SYNC, userId))
            preferences.remove(booleanKey(KEY_INITIAL_SYNC_COMPLETED, userId))
        }
    }

    private suspend fun setLong(key: String, userId: String, value: Long) {
        dataStore.edit { preferences ->
            preferences[longKey(key, userId)] = value
        }
    }

    private fun longKey(key: String, userId: String) = longPreferencesKey("${key}_$userId")
    private fun booleanKey(key: String, userId: String) = booleanPreferencesKey("${key}_$userId")

    private companion object {
        const val LEGACY_PREFS_NAME = "sync_prefs"
        const val DATASTORE_NAME = "imfit_sync"
        const val KEY_LAST_SYNC_TIMESTAMP = "last_sync_timestamp"
        const val KEY_LAST_TEMPLATES_SYNC = "last_templates_sync"
        const val KEY_LAST_WORKOUT_LOGS_SYNC = "last_workout_logs_sync"
        const val KEY_LAST_EXERCISES_SYNC = "last_exercises_sync"
        const val KEY_INITIAL_SYNC_COMPLETED = "initial_sync_completed"
    }
}

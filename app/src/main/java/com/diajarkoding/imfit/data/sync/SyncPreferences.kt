package com.diajarkoding.imfit.data.sync

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores sync preferences like lastSyncTimestamp.
 */
@Singleton
class SyncPreferences @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var lastSyncTimestamp: Long
        get() = prefs.getLong(KEY_LAST_SYNC_TIMESTAMP, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_SYNC_TIMESTAMP, value).apply()

    var lastTemplatesSyncTimestamp: Long
        get() = prefs.getLong(KEY_LAST_TEMPLATES_SYNC, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_TEMPLATES_SYNC, value).apply()

    var lastWorkoutLogsSyncTimestamp: Long
        get() = prefs.getLong(KEY_LAST_WORKOUT_LOGS_SYNC, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_WORKOUT_LOGS_SYNC, value).apply()

    var lastExercisesSyncTimestamp: Long
        get() = prefs.getLong(KEY_LAST_EXERCISES_SYNC, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_EXERCISES_SYNC, value).apply()

    var isInitialSyncCompleted: Boolean
        get() = prefs.getBoolean(KEY_INITIAL_SYNC_COMPLETED, false)
        set(value) = prefs.edit().putBoolean(KEY_INITIAL_SYNC_COMPLETED, value).apply()

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "sync_prefs"
        private const val KEY_LAST_SYNC_TIMESTAMP = "last_sync_timestamp"
        private const val KEY_LAST_TEMPLATES_SYNC = "last_templates_sync"
        private const val KEY_LAST_WORKOUT_LOGS_SYNC = "last_workout_logs_sync"
        private const val KEY_LAST_EXERCISES_SYNC = "last_exercises_sync"
        private const val KEY_INITIAL_SYNC_COMPLETED = "initial_sync_completed"
    }
}

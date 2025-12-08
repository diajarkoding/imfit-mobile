package com.diajarkoding.imfit.theme

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.themeDataStore: DataStore<Preferences> by preferencesDataStore(name = "imfit_theme")

@Singleton
class ThemeManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val isDarkModeKey = booleanPreferencesKey("is_dark_mode")

    val isDarkMode: Flow<Boolean> = context.themeDataStore.data.map { preferences ->
        preferences[isDarkModeKey] ?: false
    }

    suspend fun setDarkMode(isDark: Boolean) {
        context.themeDataStore.edit { preferences ->
            preferences[isDarkModeKey] = isDark
        }
    }

    suspend fun toggleTheme() {
        context.themeDataStore.edit { preferences ->
            val current = preferences[isDarkModeKey] ?: false
            preferences[isDarkModeKey] = !current
        }
    }
}

package com.diajarkoding.imfit.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

// Deklarasikan DataStore di top-level
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "imfit_session")

@Singleton
class SessionManager @Inject constructor(@ApplicationContext private val context: Context) {

    private val authTokenKey = stringPreferencesKey("auth_token")

    suspend fun saveAuthToken(token: String) {
        context.dataStore.edit { preferences ->
            preferences[authTokenKey] = token
        }
    }

    fun getAuthToken(): Flow<String?> {
        return context.dataStore.data.map { preferences ->
            preferences[authTokenKey]
        }
    }

    suspend fun clearAuthToken() {
        context.dataStore.edit { preferences ->
            preferences.remove(authTokenKey)
        }
    }
}
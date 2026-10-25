package com.diajarkoding.imfit.theme

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.Locale

private val Context.localeDataStore by preferencesDataStore(
    name = "imfit_locale",
    produceMigrations = { context ->
        listOf(SharedPreferencesMigration(context, "imfit_locale_prefs"))
    }
)

object LocaleManager {
    private val languageKey = stringPreferencesKey("language")
    private const val DEFAULT_LANGUAGE = "in" // Indonesian as default
    
    var currentLanguage by mutableStateOf(DEFAULT_LANGUAGE)
        private set
    
    // Configuration version to trigger Compose recomposition when language changes
    // Instead of Activity.recreate() which causes black screen flicker
    var configurationVersion by mutableIntStateOf(0)
        private set
    
    val isIndonesian: Boolean
        get() = currentLanguage == "in"

    fun init(context: Context) {
        currentLanguage = readLanguage(context)
        updateLocale(context, currentLanguage)
    }

    fun toggleLanguage(context: Context) {
        val newLanguage = if (currentLanguage == "in") "en" else "in"
        setLanguage(context, newLanguage)
    }

    fun setLanguage(context: Context, languageCode: String) {
        currentLanguage = languageCode
        
        runBlocking {
            context.localeDataStore.edit { preferences ->
                preferences[languageKey] = languageCode
            }
        }
        
        updateLocale(context, languageCode)
        
        // Increment version to trigger Compose recomposition instead of recreating Activity
        // This avoids the black screen flicker on language change
        configurationVersion++
    }

    private fun createLocale(languageCode: String): Locale {
        // "in" is the legacy code for Indonesian, use "id" for Locale.forLanguageTag
        val tag = if (languageCode == "in") "id" else languageCode
        return Locale.forLanguageTag(tag)
    }

    private fun updateLocale(context: Context, languageCode: String) {
        val locale = createLocale(languageCode)
        Locale.setDefault(locale)
        
        val config = context.resources.configuration
        config.setLocale(locale)
        @Suppress("DEPRECATION")
        context.resources.updateConfiguration(config, context.resources.displayMetrics)
    }

    fun getUpdatedContext(context: Context): Context {
        val locale = createLocale(currentLanguage)
        Locale.setDefault(locale)
        
        val config = context.resources.configuration
        config.setLocale(locale)
        
        return context.createConfigurationContext(config)
    }
    
    fun attachBaseContext(context: Context): Context {
        val language = readLanguage(context)
        
        val locale = createLocale(language)
        Locale.setDefault(locale)
        
        val config = context.resources.configuration
        config.setLocale(locale)
        
        return context.createConfigurationContext(config)
    }

    private fun readLanguage(context: Context): String = runBlocking {
        context.localeDataStore.data.first()[languageKey] ?: DEFAULT_LANGUAGE
    }
}

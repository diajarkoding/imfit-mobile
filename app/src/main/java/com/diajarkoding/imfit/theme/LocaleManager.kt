package com.diajarkoding.imfit.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

object LocaleManager {
    private const val PREFS_NAME = "imfit_locale_prefs"
    private const val KEY_LANGUAGE = "language"
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
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        currentLanguage = prefs.getString(KEY_LANGUAGE, DEFAULT_LANGUAGE) ?: DEFAULT_LANGUAGE
        updateLocale(context, currentLanguage)
    }

    fun toggleLanguage(context: Context) {
        val newLanguage = if (currentLanguage == "in") "en" else "in"
        setLanguage(context, newLanguage)
    }

    fun setLanguage(context: Context, languageCode: String) {
        currentLanguage = languageCode
        
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LANGUAGE, languageCode).apply()
        
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
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val language = prefs.getString(KEY_LANGUAGE, DEFAULT_LANGUAGE) ?: DEFAULT_LANGUAGE
        
        val locale = createLocale(language)
        Locale.setDefault(locale)
        
        val config = context.resources.configuration
        config.setLocale(locale)
        
        return context.createConfigurationContext(config)
    }
}

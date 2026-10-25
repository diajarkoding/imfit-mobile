package com.diajarkoding.imfit.core.timer

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.emptyPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.restTimerDataStore by preferencesDataStore("imfit_rest_timer",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() })

@Singleton
class AndroidRestTimerStore @Inject constructor(@ApplicationContext private val context: Context) : RestTimerStore {
    private val key = stringPreferencesKey("state_v1")
    override suspend fun read(): RestTimerState? {
        val encoded = context.restTimerDataStore.data.first()[key] ?: return null
        // Corruption must not be interpreted as an expired alarm.
        return try {
            RestTimerStateCodec.decode(encoded)
        } catch (e: IllegalArgumentException) {
            context.restTimerDataStore.edit { it.remove(key) }
            null
        }
    }
    override suspend fun write(state: RestTimerState) {
        context.restTimerDataStore.edit { it[key] = RestTimerStateCodec.encode(state) }
        if (com.diajarkoding.imfit.BuildConfig.DEBUG) android.util.Log.d("IMFITRest",
            "persist timer=${state.timerId} generation=${state.generation} status=${state.status} scheduling=${state.schedulingMode} deadline=${state.deadlineElapsedMs} now=${SystemClock.elapsedRealtime()}")
    }
}

class AndroidMonotonicClock @Inject constructor(@ApplicationContext private val context: Context) : MonotonicClock {
    override fun elapsedRealtime() = SystemClock.elapsedRealtime()
    override fun epochMillis() = System.currentTimeMillis()
    // BOOT_COUNT exists on our minSdk 24. No wall-clock heuristic across boots.
    override fun bootIdentity(): Int? = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
    } catch (_: Exception) { null }
}

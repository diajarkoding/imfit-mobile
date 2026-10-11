package com.diajarkoding.imfit.core.timer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.diajarkoding.imfit.core.notification.WorkoutActions
import com.diajarkoding.imfit.core.service.WorkoutService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class RestTimerAlarmReceiver : BroadcastReceiver() {
    @Inject lateinit var coordinator: RestTimerCoordinator
    @Inject lateinit var store: AndroidRestTimerStore
    @Inject lateinit var runtime: RestTimerAndroidRuntime

    override fun onReceive(context: Context, intent: Intent) {
        if (com.diajarkoding.imfit.BuildConfig.DEBUG) android.util.Log.d("IMFITRest",
            "alarm delivered timer=${intent.getStringExtra("timer_id")} generation=${intent.getLongExtra("generation", -1)} now=${android.os.SystemClock.elapsedRealtime()}")
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            try {
                val state = store.read()
                if (state == null) { runtime.cancelOrphanAlarm(); coordinator.reconcile(); return@launch }
                if (state.timerId != intent.getStringExtra("timer_id") || state.generation != intent.getLongExtra("generation", -1)) return@launch
                if (state.status != TimerStatus.RUNNING && state.status != TimerStatus.EXPIRED) {
                    coordinator.reconcile()
                    return@launch
                }
                if (!runtime.validSession(state) || runtime.userStopped(state)) {
                    coordinator.cancel("RECOVERY_INVALID")
                    return@launch
                }
                try {
                    ContextCompat.startForegroundService(context, Intent(context, WorkoutService::class.java).apply {
                        action = WorkoutActions.EXPIRE
                        putExtra("timer_id", state.timerId)
                        putExtra("generation", state.generation)
                    })
                } catch (_: RuntimeException) {
                    // A denied background service start still records expiry, without uncontrolled audio.
                    coordinator.expiry(state.timerId, state.generation)
                }
            } catch (_: Exception) {
                runtime.stopAudio()
            } finally { pending.finish() }
        }
    }
}

package com.diajarkoding.imfit.core.timer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.diajarkoding.imfit.core.notification.WorkoutActions
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

@AndroidEntryPoint
class RestTimerAlarmReceiver : BroadcastReceiver() {
    @Inject lateinit var coordinator: RestTimerCoordinator

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WorkoutActions.EXPIRE) return
        val timerId = intent.getStringExtra("timer_id") ?: return
        val generation = intent.getLongExtra("generation", -1)
        if (intent.data?.toString() != "imfit-rest://alarm/$timerId/$generation") return
        RestTimerTrace.event("receiver_received", detail = "receivedTimer=$timerId receivedGeneration=$generation")
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            try {
                // Includes lock acquisition and local recovery; no playback polling in the receiver.
                withTimeout(4000) {
                    coordinator.expiry(timerId, generation, alarmDelivery = true)
                }
            } catch (e: TimeoutCancellationException) {
                RestTimerTrace.event("receiver_timeout", detail = "receivedTimer=$timerId receivedGeneration=$generation")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RestTimerTrace.event("receiver_failed", detail = "receivedTimer=$timerId exception=${e.javaClass.simpleName}")
            } finally { pending.finish() }
        }
    }
}

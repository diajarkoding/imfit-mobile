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
import javax.inject.Inject

@AndroidEntryPoint
class RestTimerAlarmReceiver : BroadcastReceiver() {
    @Inject lateinit var coordinator: RestTimerCoordinator
    @Inject lateinit var recovery: RestTimerRecoveryScheduler

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WorkoutActions.EXPIRE) return
        val timerId = intent.getStringExtra("timer_id")?.takeIf { it.isNotBlank() } ?: return
        val generation = intent.getLongExtra("generation", -1)
        if (generation < 0) return
        if (intent.data?.toString() != "imfit-rest://alarm/$timerId/$generation") return
        RestTimerTrace.event("receiver_received", detail = "receivedTimer=$timerId receivedGeneration=$generation")
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            try {
                RestTimerAlarmDelivery(coordinator, recovery, trace = { event, detail ->
                    RestTimerTrace.event(event, coordinator.state.value, detail)
                }).receive(timerId, generation)
            } finally { pending.finish() }
        }
    }
}

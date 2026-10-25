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
        RestTimerTrace.event("receiver_enter", detail = "source=alarm")
        if (intent.action != WorkoutActions.EXPIRE) {
            RestTimerTrace.event("receiver_rejected", detail = "source=alarm reason=ACTION_MISMATCH")
            return
        }
        val timerId = intent.getStringExtra("timer_id")?.takeIf { it.isNotBlank() } ?: run {
            RestTimerTrace.event("receiver_rejected", detail = "source=alarm reason=MISSING_ID")
            return
        }
        val generation = intent.getLongExtra("generation", -1)
        if (generation < 0 || intent.data?.toString() != "imfit-rest://alarm/$timerId/$generation") {
            RestTimerTrace.event("receiver_rejected", detail = "source=alarm reason=IDENTITY_MISMATCH")
            return
        }
        RestTimerTrace.event("receiver_received", detail = "source=alarm restInstanceId=${RestTimerTrace.instanceId(timerId)} " +
            "generation=$generation receivedDeadlineElapsedMs=${intent.getLongExtra("deadline_elapsed_ms", -1)}")
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

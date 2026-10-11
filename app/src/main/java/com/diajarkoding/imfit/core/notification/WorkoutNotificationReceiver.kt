package com.diajarkoding.imfit.core.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.diajarkoding.imfit.core.service.WorkoutService
import com.diajarkoding.imfit.core.timer.RestTimerCoordinator
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

@AndroidEntryPoint
class WorkoutNotificationReceiver : BroadcastReceiver() {
    @Inject lateinit var timer: RestTimerCoordinator

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in listOf(WorkoutActions.ACKNOWLEDGE, WorkoutActions.SKIP, WorkoutActions.EXTEND)) {
            val result = goAsync()
            CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
                try {
                    when (intent.action) {
                        WorkoutActions.ACKNOWLEDGE -> intent.getStringExtra("timer_id")?.let {
                            // Mark dismissal before acknowledging so no reconcile reposts the card.
                            timer.noteDismissed(it)
                            timer.acknowledge(it, intent.getLongExtra("generation", -1))
                        }
                        WorkoutActions.SKIP -> intent.getStringExtra("timer_id")?.let { timer.skip(it) }
                        WorkoutActions.EXTEND -> intent.getStringExtra("timer_id")?.let { timer.extend(it, intent.getLongExtra("generation", -1)) }
                    }
                } finally { result.finish() }
            }
        } else if (intent.action in listOf(WorkoutActions.PAUSE, WorkoutActions.RESUME, WorkoutActions.STOP)) {
            try {
                context.startService(Intent(context, WorkoutService::class.java).apply { action = intent.action })
            } catch (_: RuntimeException) { /* Expiry recovery retains the persisted timer. */ }
        }
    }

}

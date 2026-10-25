package com.diajarkoding.imfit.core.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import com.diajarkoding.imfit.core.model.WorkoutTimerUpdate
import com.diajarkoding.imfit.core.notification.NotificationChannels
import com.diajarkoding.imfit.core.notification.WorkoutActions
import com.diajarkoding.imfit.core.notification.WorkoutNotificationManager
import com.diajarkoding.imfit.core.timer.*
import com.diajarkoding.imfit.domain.repository.WorkoutRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/** User-started workout session. The AlarmManager deadline survives this process. */
@AndroidEntryPoint
class WorkoutService : Service() {
    @Inject lateinit var notificationManager: WorkoutNotificationManager
    @Inject lateinit var timer: RestTimerCoordinator
    @Inject lateinit var runtime: RestTimerAndroidRuntime
    @Inject lateinit var workouts: WorkoutRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val running = MutableStateFlow(false)
    val isRunning = running.asStateFlow()
    private var update = WorkoutTimerUpdate("Workout", 0, 0, 0, 0f, false)
    private var templateId: String? = null
    private var ticker: Job? = null
    private val sessionRetry = RestTimerRetry()

    inner class WorkoutBinder : Binder() { fun getService() = this@WorkoutService }
    override fun onBind(intent: Intent?): IBinder = WorkoutBinder()

    override fun onCreate() {
        super.onCreate()
        RestTimerTrace.event("service_created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val playback = intent?.takeIf { it.action == WorkoutActions.EXPIRE }?.let {
            val id = it.getStringExtra("timer_id") ?: return@let null
            PlaybackToken(id, it.getLongExtra("generation", -1), it.getLongExtra("revision", -1))
        }
        RestTimerTrace.event("service_start", timer.state.value, "action=${intent?.action} revision=${playback?.revision}")
        if (!running.value) {
            update = update.copy(workoutName = intent?.getStringExtra(EXTRA_WORKOUT_NAME) ?: "Workout")
            templateId = intent?.getStringExtra(EXTRA_TEMPLATE_ID)
            try {
                val notification = notificationManager.buildWorkoutNotification(update.workoutName, "00:00:00", 0, 0, 0f, templateId = templateId)
                ServiceCompat.startForeground(this, NotificationChannels.WORKOUT_NOTIFICATION_ID, notification,
                    if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
                running.value = true
                runtime.foregroundServiceActive = true
                runtime.resetWorkoutRendering()
                RestTimerTrace.event("fgs_promoted", timer.state.value, "type=${if (Build.VERSION.SDK_INT >= 34) "specialUse" else "legacy"}")
            } catch (e: RuntimeException) {
                playback?.let { runtime.reportServiceResult(it, false) }
                RestTimerTrace.event("fgs_promotion_failed", timer.state.value, "exception=${e.javaClass.simpleName}")
                stopSelf()
                return START_NOT_STICKY
            }
        }
        ensureTicker()
        when (intent?.action) {
            ACTION_UPDATE_WORKOUT -> updateWorkoutNotification(WorkoutTimerUpdate(
                intent.getStringExtra(EXTRA_WORKOUT_NAME) ?: update.workoutName,
                intent.getLongExtra(EXTRA_ELAPSED_SECONDS, 0),
                intent.getIntExtra(EXTRA_COMPLETED_SETS, 0),
                intent.getIntExtra(EXTRA_TOTAL_SETS, 0),
                intent.getFloatExtra(EXTRA_TOTAL_VOLUME, 0f),
                intent.getBooleanExtra(EXTRA_IS_PAUSED, false)
            ))
            ACTION_SKIP_REST -> scope.launch { timer.skip() }
            ACTION_PAUSE_WORKOUT -> scope.launch { setPaused(true) }
            ACTION_RESUME_WORKOUT -> scope.launch { setPaused(false) }
            ACTION_STOP_WORKOUT -> scope.launch { workouts.cancelWorkout(); stopWorkout() }
            WorkoutActions.EXPIRE -> playback?.let { runtime.reportServiceResult(it, true) }
        }
        return START_STICKY
    }

    /** Main-thread starts are serialized; an active ticker is never replaced. */
    private fun ensureTicker() {
        if (ticker?.isActive == true || !running.value) return
        val previous = ticker
        ticker = scope.launch {
            RestTimerTrace.event("ticker_started", timer.state.value, "source=fgs_ticker")
            try {
                previous?.join() // A cancelled predecessor must finish before the new loop starts.
                var exactAvailable = runtime.exactAlarmAvailable()
                var lastHeartbeat = 0L
                var lastIdentity: List<Any?>? = null
                var lastElapsed = SystemClock.elapsedRealtime()
                var lastUptime = SystemClock.uptimeMillis()
                var dueIdentity: Pair<String, Long>? = null
                val iteration = RestTimerTickerIteration(
                    readSession = {
                        RestTimerTrace.measured("ticker_session_read", timer.state.value) {
                            workouts.getActiveSessionForTimer()
                        }
                    },
                    reconcile = { timer.reconcile(source = "fgs_ticker") },
                    render = { timer.renderWorkout(it) },
                    absent = { timer.cancel("SESSION_ABSENT"); stopWorkout() },
                    now = { SystemClock.elapsedRealtime() },
                    retry = sessionRetry,
                    failure = { stage, error, retryMs ->
                        if (stage == "expiry_reconcile") {
                            RestTimerTrace.event("ticker_reconcile_failed", timer.state.value,
                                "source=fgs_ticker stage=$stage exception=${error.javaClass.simpleName}")
                        } else {
                            recordReadFailure(stage, error, retryMs)
                        }
                    },
                    recovered = { RestTimerTrace.event("session_read_recovered", timer.state.value, "source=fgs_ticker") }
                )
                while (isActive && running.value) {
                    val now = SystemClock.elapsedRealtime()
                    val uptime = SystemClock.uptimeMillis()
                    val rest = timer.state.value
                    val identity = listOf(rest?.timerId, rest?.generation, rest?.status)
                    if (now - lastElapsed > 5000) RestTimerTrace.event("ticker_gap", rest,
                        "source=fgs_ticker gapElapsedMs=${now - lastElapsed} gapUptimeMs=${uptime - lastUptime}")
                    if (identity != lastIdentity || now - lastHeartbeat >= 30_000) {
                        RestTimerTrace.event("ticker_heartbeat", rest,
                            "source=fgs_ticker running=${running.value} readBackoff=${!sessionRetry.available(now)}")
                        lastHeartbeat = now
                        lastIdentity = identity
                    }
                    lastElapsed = now
                    lastUptime = uptime
                    if (rest?.status == TimerStatus.RUNNING && now >= rest.deadlineElapsedMs &&
                        dueIdentity != (rest.timerId to rest.generation)) {
                        dueIdentity = rest.timerId to rest.generation
                        RestTimerTrace.event("ticker_due", rest, "source=fgs_ticker")
                    }
                try {
                    runtime.monitorPlayback()
                    val available = runtime.exactAlarmAvailable()
                    if (available != exactAvailable) {
                        timer.capabilitiesChanged(source = "fgs_capability")
                        exactAvailable = available
                    }
                    if (!iteration.tick()) return@launch
                    if (!sessionRetry.available(SystemClock.elapsedRealtime()) &&
                        rest?.status == TimerStatus.RUNNING && now >= rest.deadlineElapsedMs) {
                        RestTimerTrace.event("ticker_backoff", rest, "source=fgs_ticker")
                    }
                } catch (e: TimeoutCancellationException) {
                    recordReadFailure("ticker_outer", e, sessionRetry.failed(SystemClock.elapsedRealtime()))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    recordReadFailure("ticker_outer", e, sessionRetry.failed(SystemClock.elapsedRealtime()))
                }
                delay(1000)
                }
            } catch (e: CancellationException) {
                RestTimerTrace.event("ticker_cancelled", timer.state.value,
                    "source=fgs_ticker exception=${e.javaClass.simpleName}")
                throw e
            } finally {
                RestTimerTrace.event("ticker_completed", timer.state.value, "source=fgs_ticker running=${running.value}")
            }
        }
    }

    private fun recordReadFailure(stage: String, error: Exception, retryMs: Long) {
        RestTimerTrace.event("ticker_recovery_failed", timer.state.value,
            "source=fgs_ticker stage=$stage attempt=${sessionRetry.failures} retryMs=$retryMs exception=${error.javaClass.simpleName}")
    }

    private suspend fun setPaused(paused: Boolean) {
        if (paused) {
            timer.whenRestInactive { persistPause(true) }
        } else persistPause(false)
    }
    private suspend fun persistPause(paused: Boolean) {
        val session = workouts.getActiveSessionForTimer() ?: return
        if (session.isPaused == paused) return
        val now = System.currentTimeMillis()
        workouts.mutateActiveSession(session.id) { current -> if (current.isPaused == paused) current else current.copy(
            isPaused = paused,
            lastPauseTime = if (paused) now else null,
            totalPausedTimeMs = current.totalPausedTimeMs + if (!paused) (now - (current.lastPauseTime ?: now)).coerceAtLeast(0) else 0
        ) }
        update = update.copy(isPaused = paused)
    }

    fun updateWorkoutNotification(next: WorkoutTimerUpdate) { update = next }
    fun stopWorkout() {
        running.value = false
        ticker?.cancel()
        runtime.serviceStopped()
        notificationManager.dismissNotification()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onDestroy() {
        scope.cancel()
        running.value = false
        runtime.serviceStopped() // Never cancel the persisted RUNNING timer's system alarm here.
        RestTimerTrace.event("service_destroyed", timer.state.value)
        super.onDestroy()
    }

    companion object {
        const val ACTION_START_WORKOUT = WorkoutActions.START
        const val ACTION_UPDATE_WORKOUT = WorkoutActions.UPDATE
        const val ACTION_START_REST = WorkoutActions.START_REST
        const val ACTION_SKIP_REST = WorkoutActions.SKIP
        const val ACTION_PAUSE_WORKOUT = WorkoutActions.PAUSE
        const val ACTION_RESUME_WORKOUT = WorkoutActions.RESUME
        const val ACTION_STOP_WORKOUT = WorkoutActions.STOP
        const val EXTRA_WORKOUT_NAME = "extra_workout_name"
        const val EXTRA_TEMPLATE_ID = "extra_template_id"
        const val EXTRA_START_TIME = "extra_start_time"
        const val EXTRA_TOTAL_PAUSED_TIME = "extra_total_paused_time"
        const val EXTRA_ELAPSED_SECONDS = "extra_elapsed_seconds"
        const val EXTRA_COMPLETED_SETS = "extra_completed_sets"
        const val EXTRA_TOTAL_SETS = "extra_total_sets"
        const val EXTRA_TOTAL_VOLUME = "extra_total_volume"
        const val EXTRA_IS_PAUSED = "extra_is_paused"
    }
}

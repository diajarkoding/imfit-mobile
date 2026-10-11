package com.diajarkoding.imfit.core.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
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
    private var lastNotification: Pair<WorkoutTimerUpdate, Int?>? = null

    inner class WorkoutBinder : Binder() { fun getService() = this@WorkoutService }
    override fun onBind(intent: Intent?): IBinder = WorkoutBinder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running.value) {
            update = update.copy(workoutName = intent?.getStringExtra(EXTRA_WORKOUT_NAME) ?: "Workout")
            templateId = intent?.getStringExtra(EXTRA_TEMPLATE_ID)
            val notification = notificationManager.buildWorkoutNotification(update.workoutName, "00:00:00", 0, 0, 0f, templateId = templateId)
            try {
                ServiceCompat.startForeground(this, NotificationChannels.WORKOUT_NOTIFICATION_ID, notification,
                    if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
                running.value = true
                runtime.foregroundServiceActive = true
                ticker = scope.launch {
                    timer.reconcile()
                    var exactAvailable = runtime.exactAlarmAvailable()
                    while (isActive) {
                        runtime.monitorPlayback()
                        val available = runtime.exactAlarmAvailable()
                        if (available != exactAvailable) {
                            exactAvailable = available
                            timer.capabilitiesChanged()
                        }
                        timer.reconcile()
                        renderNotification()
                        delay(1000)
                    }
                }
            } catch (_: RuntimeException) { stopSelf(); return START_NOT_STICKY }
        }
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
            WorkoutActions.EXPIRE -> scope.launch {
                timer.expiry(intent.getStringExtra("timer_id") ?: "", intent.getLongExtra("generation", -1))
                val state = timer.state.value
                if (state == null || state.status == TimerStatus.CANCELLED) stopWorkout()
                else templateId = state.templateId
            }
        }
        return START_STICKY
    }

    private suspend fun setPaused(paused: Boolean) {
        if (paused) {
            timer.whenRestInactive { persistPause(true) }
        } else persistPause(false)
    }
    private suspend fun persistPause(paused: Boolean) {
        val session = workouts.getActiveSession() ?: return
        if (session.isPaused == paused) return
        val now = System.currentTimeMillis()
        workouts.updateActiveSession(session.copy(
            isPaused = paused,
            lastPauseTime = if (paused) now else null,
            totalPausedTimeMs = session.totalPausedTimeMs + if (!paused) (now - (session.lastPauseTime ?: now)).coerceAtLeast(0) else 0
        ))
        update = update.copy(isPaused = paused)
    }

    private suspend fun renderNotification() {
        val session = workouts.getActiveSession() ?: run { stopWorkout(); return }
        // Same session-derived elapsed source as the ViewModel; ticks never accumulate time.
        update = update.copy(workoutName = session.templateName, elapsedSeconds = session.actualElapsedMs.coerceAtLeast(0) / 1000,
            completedSets = session.totalCompletedSets, totalSets = session.totalSets, totalVolume = session.totalVolume, isPaused = session.isPaused)
        templateId = session.templateId
        val rest = timer.state.value?.takeIf { it.status == TimerStatus.RUNNING && it.workoutId == session.id }
        val remaining = rest?.remainingSeconds(android.os.SystemClock.elapsedRealtime())
        val identity = update to remaining
        if (lastNotification == identity) return
        lastNotification = identity
        if (remaining != null) notificationManager.showRestTimerNotification(remaining, rest.exerciseName, rest.timerId, rest.generation)
        else notificationManager.showWorkoutNotification(update.workoutName, format(update.elapsedSeconds), update.completedSets,
            update.totalSets, update.totalVolume, update.isPaused, templateId)
    }

    fun updateWorkoutNotification(next: WorkoutTimerUpdate) { update = next }
    fun stopWorkout() {
        running.value = false
        ticker?.cancel()
        timer.releasePlayback()
        runtime.foregroundServiceActive = false
        notificationManager.dismissNotification()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onDestroy() {
        scope.cancel()
        running.value = false
        runtime.foregroundServiceActive = false
        timer.releasePlayback() // Never cancel the persisted RUNNING timer's system alarm here.
        super.onDestroy()
    }
    private fun format(seconds: Long) = "%02d:%02d:%02d".format(seconds / 3600, seconds % 3600 / 60, seconds % 60)

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

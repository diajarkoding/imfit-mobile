package com.diajarkoding.imfit.core.timer

import android.app.Activity
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.core.notification.NotificationChannels
import com.diajarkoding.imfit.core.notification.WorkoutNotificationManager
import com.diajarkoding.imfit.domain.repository.AuthRepository
import com.diajarkoding.imfit.domain.repository.WorkoutRepository
import com.diajarkoding.imfit.domain.model.WorkoutSession
import com.diajarkoding.imfit.core.service.WorkoutService
import com.diajarkoding.imfit.core.notification.WorkoutActions
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.*

/** Android ports; repositories are lazy to keep cleanup independent of their construction. */
@Singleton
class RestTimerAndroidRuntime @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auth: Provider<AuthRepository>,
    private val workouts: Provider<WorkoutRepository>,
    private val notifications: WorkoutNotificationManager,
    private val coordinator: Provider<RestTimerCoordinator>
) : RestTimerEffects, Application.ActivityLifecycleCallbacks {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val vibrator = context.getSystemService(android.os.Vibrator::class.java)
    private var vibrating = false
    private val prefs = context.getSharedPreferences("rest_timer_effects", Context.MODE_PRIVATE)
    private var player: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null
    private var resumedActivities = 0
    var foregroundServiceActive = false
    private var posted = false
    private var shownTimerId: String? = null
    private var shownGeneration: Long? = null
    private var cardPostElapsedMs: Long? = null
    private val userDismissed = mutableSetOf<String>()
    private var legacyListener: AudioManager.OnAudioFocusChangeListener? = null
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    private var session: WorkoutSession? = null
    private var lastRender: List<Any?>? = null
    private val renderRetry = RestTimerRetry()
    private val finishedRetry = RestTimerRetry()
    private val workoutRefreshRetry = RestTimerRetry()
    private var workoutRefreshJob: Job? = null
    private var readinessJob: Job? = null
    private var readinessToken: PlaybackToken? = null
    private var readinessStartedMs = 0L
    private var startupRequested = false
    private var startupFailed = false
    private var alarmStartupAllowed = false
    private var alarmReceived = false
    private var playbackWasReady = false
    private var audioToken: PlaybackToken? = null

    fun register(application: Application) = application.registerActivityLifecycleCallbacks(this)

    override suspend fun validSession(state: RestTimerState): Boolean = RestTimerSessionValidator(
        localUserId = { auth.get().getLocalUserId() },
        peekUserId = { auth.get().peekLocalUserId() },
        authStatus = { auth.get().localAuthStatusName() },
        onStatus = { status -> RestTimerTrace.event("auth_status_snapshot", state, "authStatus=$status") },
        sessionExists = { userId, workoutId -> workouts.get().isActiveSessionForTimer(userId, workoutId) },
        measure = { stage, block -> RestTimerTrace.measured(stage, state, block) }
    ).valid(state)

    override fun trace(event: String, state: RestTimerState?, detail: String) = RestTimerTrace.event(event, state, detail)

    override suspend fun workoutSessionIsCurrent(session: WorkoutSession): Boolean =
        RestTimerTrace.measured("workout_render_validation") {
            withTimeout(2500) {
                val latest = workouts.get().getActiveSessionForTimer()
                this@RestTimerAndroidRuntime.session = latest
                latest?.id == session.id
            }
        }

    /** Called only through the coordinator lock. No UI/ticker snapshot may post directly. */
    override fun renderWorkout(state: RestTimerState?, session: WorkoutSession?) {
        if (session != null) this.session = session
        val currentSession = this.session ?: run { refreshWorkoutRendering(state); return }
        if (state != null && state.workoutId != currentSession.id &&
            (state.status == TimerStatus.RUNNING || state.status == TimerStatus.EXPIRED)) {
            refreshWorkoutRendering(state)
            return
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (!renderRetry.available(now)) return
        val rest = state?.takeIf { it.status == TimerStatus.RUNNING && it.deadlineElapsedMs > now }
        val elapsed = currentSession.actualElapsedMs.coerceAtLeast(0) / 1000
        // Include clock conversion so wall-clock changes rebuild the chronometer's calendar base.
        val epochOffsetSeconds = (System.currentTimeMillis() - now) / 1000
        val identity = listOf(currentSession, elapsed, state?.timerId, state?.generation, state?.status,
            rest?.deadlineElapsedMs, epochOffsetSeconds)
        if (lastRender == identity) return
        try {
            val posted = if (rest != null) notifications.showRestTimerNotification(rest, currentSession.templateId)
            else notifications.showWorkoutNotification(currentSession.templateName, format(elapsed), currentSession.totalCompletedSets,
                currentSession.totalSets, currentSession.totalVolume, currentSession.isPaused, currentSession.templateId)
            if (!posted) throw IllegalStateException("Workout notification post failed")
            lastRender = identity
            renderRetry.reset()
            trace("workout_rendered", state, "notificationId=1001 countdown=${rest != null}")
        } catch (e: RuntimeException) {
            val retry = renderRetry.failed(now)
            trace("render_failed", state, "attempt=${renderRetry.failures} retryMs=$retry exception=${e.javaClass.simpleName}")
        }
    }

    /** Full JSON recovery is for rendering, never a prerequisite for expiry/notification 1002. */
    private fun refreshWorkoutRendering(state: RestTimerState?) {
        if (state == null || state.status !in listOf(TimerStatus.RUNNING, TimerStatus.EXPIRED)) return
        if (workoutRefreshJob?.isActive == true || !workoutRefreshRetry.available(android.os.SystemClock.elapsedRealtime())) return
        workoutRefreshJob = scope.launch {
            try {
                val recovered = RestTimerTrace.measured("workout_refresh", state) {
                    withTimeout(2500) { workouts.get().getActiveSessionForTimer() }
                }
                // Re-enter the coordinator; logout/replacement while reading must win.
                if (recovered != null) coordinator.get().renderWorkout(recovered)
                workoutRefreshRetry.reset()
            } catch (e: TimeoutCancellationException) {
                workoutRefreshRetry.failed(android.os.SystemClock.elapsedRealtime())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                workoutRefreshRetry.failed(android.os.SystemClock.elapsedRealtime())
            }
        }
    }

    fun resetWorkoutRendering() {
        lastRender = null
        renderRetry.reset()
    }

    override fun dismissWorkout() {
        workoutRefreshJob?.cancel()
        workoutRefreshJob = null
        workoutRefreshRetry.reset()
        notifications.dismissNotification()
        session = null
        resetWorkoutRendering()
    }

    private fun format(seconds: Long) = "%02d:%02d:%02d".format(seconds / 3600, seconds % 3600 / 60, seconds % 60)

    override fun userStopped(state: RestTimerState): Boolean {
        if (prefs.getString("stopped_session", null) == state.workoutId &&
            prefs.getLong("stopped_at", 0) >= state.startedAtEpochMs) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val exit = context.getSystemService(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(context.packageName, 0, 8)
            .firstOrNull {
                it.reason == ApplicationExitInfo.REASON_USER_REQUESTED &&
                    it.timestamp >= state.startedAtEpochMs &&
                    it.timestamp > prefs.getLong("processed_exit", 0)
            } ?: return false
        prefs.edit().putString("stopped_session", state.workoutId)
            .putLong("stopped_at", exit.timestamp)
            .putLong("processed_exit", exit.timestamp).apply()
        return true
    }

    private fun pendingIntent(state: RestTimerState): PendingIntent {
        val intent = Intent(context, RestTimerAlarmReceiver::class.java).apply {
            action = com.diajarkoding.imfit.core.notification.WorkoutActions.EXPIRE
            data = Uri.parse("imfit-rest://alarm/${state.timerId}/${state.generation}")
            putExtra("timer_id", state.timerId)
            putExtra("generation", state.generation)
            putExtra("deadline_elapsed_ms", state.deadlineElapsedMs) // Diagnostics only; coordinator owns authority.
        }
        return PendingIntent.getBroadcast(context, 90, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    override fun schedule(state: RestTimerState): SchedulingMode {
        val pending = pendingIntent(state)
        // Track identity independently so a corrupt DataStore can still clean the old alarm.
        prefs.edit().putString("alarm_uri", "imfit-rest://alarm/${state.timerId}/${state.generation}").apply()
        return RestTimerAlarmScheduler(
            exactAllowed = { exactAlarmAvailable() },
            exact = { alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, it.deadlineElapsedMs, pending) },
            inexact = { alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, it.deadlineElapsedMs, pending) },
            now = { android.os.SystemClock.elapsedRealtime() },
            trace = { event, rest, detail -> trace(event, rest, detail) }
        ).schedule(state)
    }

    override fun cancelAlarm(state: RestTimerState) {
        alarms.cancel(pendingIntent(state))
        trace("alarm_cancelled", state)
        if (prefs.getString("alarm_uri", null) == "imfit-rest://alarm/${state.timerId}/${state.generation}") {
            prefs.edit().remove("alarm_uri").apply()
        }
    }

    fun cancelOrphanAlarm() {
        val uri = prefs.getString("alarm_uri", null) ?: return
        val intent = Intent(context, RestTimerAlarmReceiver::class.java).apply {
            action = com.diajarkoding.imfit.core.notification.WorkoutActions.EXPIRE
            data = Uri.parse(uri)
        }
        PendingIntent.getBroadcast(context, 90, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let {
            alarms.cancel(it)
            it.cancel()
        }
        prefs.edit().remove("alarm_uri").apply()
    }
    override fun clearOrphans() = cancelOrphanAlarm()

    fun notificationsAvailable(): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.getSystemService(NotificationManager::class.java)
                .getNotificationChannel(NotificationChannels.REST_FINISHED_CHANNEL_ID)?.let {
                    it.importance != NotificationManager.IMPORTANCE_NONE
                } == true
    }

    fun headsUpAvailable(): Boolean = notificationsAvailable() && (Build.VERSION.SDK_INT < 26 ||
        context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(NotificationChannels.REST_FINISHED_CHANNEL_ID)?.importance?.let {
                it >= NotificationManager.IMPORTANCE_HIGH
            } == true)

    enum class CapabilityIssue { NOTIFICATIONS, CHANNEL, EXACT_ALARM, FULL_SCREEN }
    fun capabilityIssue(): CapabilityIssue? = when {
        !NotificationManagerCompat.from(context).areNotificationsEnabled() -> CapabilityIssue.NOTIFICATIONS
        !headsUpAvailable() -> CapabilityIssue.CHANNEL
        !exactAlarmAvailable() -> CapabilityIssue.EXACT_ALARM
        !fullScreenIntentAvailable() -> CapabilityIssue.FULL_SCREEN
        else -> null
    }

    fun capabilitySettingsIntent(): Intent? = when (capabilityIssue()) {
        CapabilityIssue.NOTIFICATIONS -> Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"))
        CapabilityIssue.CHANNEL -> if (Build.VERSION.SDK_INT >= 26) Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, NotificationChannels.REST_FINISHED_CHANNEL_ID)
            else Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        CapabilityIssue.EXACT_ALARM -> if (Build.VERSION.SDK_INT >= 31) Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
            Uri.parse("package:${context.packageName}")) else null
        CapabilityIssue.FULL_SCREEN -> if (Build.VERSION.SDK_INT >= 34) Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
            Uri.parse("package:${context.packageName}")) else null
        null -> null
    }

    override fun showFinished(state: RestTimerState): FinishedPresentation {
        if (state.timerId in userDismissed) {
            // The user removed this card; never repost it, only ensure it stays gone.
            notifications.dismissRestFinished()
            posted = false
            if (shownTimerId == state.timerId) shownTimerId = null
            return FinishedPresentation.BLOCKED
        }
        if (shownTimerId == state.timerId && shownGeneration == state.generation && posted) {
            // Self-heal a system-removed card; user removal always carries a dismissal mark.
            if (!isCardPresent() && !controlsPending()) {
                posted = false
                shownTimerId = null
            } else return FinishedPresentation.POSTED
        }
        if (!notificationsAvailable()) {
            trace("notify_blocked", state, "notificationId=1002 notificationsEnabled=${NotificationManagerCompat.from(context).areNotificationsEnabled()}")
            return FinishedPresentation.BLOCKED
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (!finishedRetry.available(now)) return FinishedPresentation.RETRY
        // Do not reset card grace by repeatedly reposting before activeNotifications catches up.
        posted = notifications.showRestFinished(state, fullScreenIntentAvailable())
        if (posted) {
            shownTimerId = state.timerId
            shownGeneration = state.generation
            if (cardPostElapsedMs == null) cardPostElapsedMs = now
            finishedRetry.reset()
        } else {
            val retry = finishedRetry.failed(now)
            trace("notify_failed", state, "notificationId=1002 attempt=${finishedRetry.failures} retryMs=$retry")
        }
        return if (posted) FinishedPresentation.POSTED else FinishedPresentation.RETRY
    }
    private fun isCardPresent(): Boolean = context.getSystemService(NotificationManager::class.java)
        .activeNotifications.any {
            it.id == NotificationChannels.REST_FINISHED_NOTIFICATION_ID &&
                it.notification.extras.getString(WorkoutNotificationManager.EXTRA_REST_TIMER_ID) == shownTimerId &&
                it.notification.extras.getLong(WorkoutNotificationManager.EXTRA_REST_GENERATION, -1) == shownGeneration
        }
    override fun noteUserDismissed(timerId: String) {
        userDismissed.add(timerId)
    }
    override fun clearDismissed() {
        userDismissed.clear()
        finishedRetry.reset()
    }
    override fun dismissFinished() {
        notifications.dismissRestFinished()
        posted = false
        shownTimerId = null
        shownGeneration = null
        cardPostElapsedMs = null
    }
    override fun controlsAvailable(): Boolean {
        val cardPresent = posted && notificationsAvailable() && isCardPresent()
        return cardPresent
    }
    override fun controlsPending(): Boolean = posted && notificationsAvailable() &&
        cardPostElapsedMs?.let { android.os.SystemClock.elapsedRealtime() - it < 1500 } == true

    override fun playbackReadiness(state: RestTimerState, token: PlaybackToken, alarmDelivery: Boolean): PlaybackReadiness {
        val now = android.os.SystemClock.elapsedRealtime()
        if (readinessToken != token) {
            cancelPlaybackChecks()
            readinessToken = token
            readinessStartedMs = now
            alarmStartupAllowed = alarmDelivery && (Build.VERSION.SDK_INT < 31 || state.schedulingMode == SchedulingMode.EXACT)
            alarmReceived = alarmDelivery
            readinessJob = scope.launch {
                while (isActive && readinessToken == token) {
                    delay(250)
                    // Sibling operation: invalidating this poller must not cancel its durable suppression/ACK write.
                    scope.launch { coordinator.get().checkPlayback(token) }.join()
                }
            }
        }
        if (alarmDelivery) {
            alarmReceived = true
            alarmStartupAllowed = Build.VERSION.SDK_INT < 31 || state.schedulingMode == SchedulingMode.EXACT
        }
        if (startupFailed || !notificationsAvailable() || state.timerId in userDismissed) return PlaybackReadiness.FAILED_PERMANENT
        if (!playbackWasReady && now - readinessStartedMs >= 5000) return PlaybackReadiness.FAILED_PERMANENT
        if (context.getSystemService(NotificationManager::class.java).currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL)
            return PlaybackReadiness.FAILED_PERMANENT
        if (!controlsAvailable()) return if (!playbackWasReady && controlsPending() && now - readinessStartedMs < 5000)
            PlaybackReadiness.WAITING else PlaybackReadiness.FAILED_PERMANENT
        // Alarm recovery prefers a promoted service, even if FSI briefly resumes an Activity.
        if (foregroundServiceActive || (resumedActivities > 0 && !alarmStartupAllowed)) {
            playbackWasReady = true
            return PlaybackReadiness.READY
        }
        if (playbackWasReady || now - readinessStartedMs >= 5000) return PlaybackReadiness.FAILED_PERMANENT
        if (!startupRequested) {
            // Inexact delivery is not the user-requested exact-alarm FGS exemption on Android 12+.
            if (!alarmStartupAllowed)
                return if (alarmReceived) PlaybackReadiness.FAILED_PERMANENT else PlaybackReadiness.WAITING
            startupRequested = true
            try {
                trace("fgs_start_requested", state, "revision=${token.revision}")
                ContextCompat.startForegroundService(context, Intent(context, WorkoutService::class.java).apply {
                    action = WorkoutActions.EXPIRE
                    putExtra("timer_id", token.timerId)
                    putExtra("generation", token.generation)
                    putExtra("revision", token.revision)
                })
            } catch (e: RuntimeException) {
                startupFailed = true
                trace("fgs_start_failed", state, "revision=${token.revision} exception=${e.javaClass.simpleName}")
                return PlaybackReadiness.FAILED_PERMANENT
            }
        }
        return PlaybackReadiness.WAITING
    }

    override fun cancelPlaybackChecks() {
        readinessJob?.cancel()
        readinessJob = null
        readinessToken = null
        startupRequested = false
        startupFailed = false
        alarmStartupAllowed = false
        alarmReceived = false
        playbackWasReady = false
    }

    fun reportServiceResult(token: PlaybackToken, ready: Boolean) {
        scope.launch { coordinator.get().playbackServiceResult(token, ready) }
    }

    override fun enqueuePlaybackCheck(token: PlaybackToken) {
        scope.launch { coordinator.get().checkPlayback(token) }
    }

    fun serviceStopped() {
        foregroundServiceActive = false
        val token = readinessToken
        if (token != null) startupFailed = true // A queued READY cannot revive a stopped owner.
        stopAudio()
        if (token != null) scope.launch { coordinator.get().interruptPlayback(token) }
        resetWorkoutRendering()
    }

    override fun startAudio(): Boolean {
        if (player != null || vibrating) return true
        if (!controlsAvailable() || (!foregroundServiceActive && resumedActivities == 0)) return false
        val token = readinessToken ?: return false
        audioToken = token
        // The app preference owns audio; a null channel sound is intentional, not a mute signal.
        if (!notificationsAvailable()) return false
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) return false
        val playSound = soundEnabled() && audio.getStreamVolume(AudioManager.STREAM_ALARM) > 0
        if (vibrationEnabled() && vibrator.hasVibrator()) {
            val pattern = longArrayOf(0, 180, 100, 180, 3540)
            if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(android.os.VibrationEffect.createWaveform(pattern, 0), attributes)
            else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, 0, attributes)
            }
            vibrating = true
            trace("vibration_started", coordinator.get().state.value, "revision=${token.revision}")
        }
        if (!playSound) return vibrating
        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            if (change != AudioManager.AUDIOFOCUS_GAIN) interruptPlayback(token)
        }
        legacyListener = listener
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes).setOnAudioFocusChangeListener(listener).build()
            audio.requestAudioFocus(focusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audio.requestAudioFocus(listener, AudioManager.STREAM_ALARM, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        }
        trace("audio_focus_result", coordinator.get().state.value, "revision=${token.revision} result=$granted")
        if (granted != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { stopAudio(); return false }
        return try {
            val next = MediaPlayer()
            player = next
            next.setAudioAttributes(attributes)
            context.resources.openRawResourceFd(R.raw.imfit_rest_done).use { descriptor ->
                next.setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length)
            }
            next.isLooping = true
            next.setOnErrorListener { _, _, _ -> interruptPlayback(token); true }
            next.prepare()
            next.start()
            trace("audio_started", coordinator.get().state.value, "revision=${token.revision}")
            true
        } catch (_: Exception) { stopAudio(); false }
    }

    override fun stopAudio() {
        if (player != null || vibrating) trace("audio_stopped", coordinator.get().state.value, "revision=${audioToken?.revision} vibration=$vibrating")
        player?.release()
        player = null
        audioToken = null
        if (vibrating) vibrator.cancel()
        vibrating = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) focusRequest?.let(audio::abandonAudioFocusRequest)
        else legacyListener?.let {
            @Suppress("DEPRECATION")
            audio.abandonAudioFocus(it)
        }
        legacyListener = null
        focusRequest = null
    }

    fun monitorPlayback() {
        if ((player != null || vibrating) && (!controlsAvailable() || !notificationsAvailable() ||
                (!foregroundServiceActive && resumedActivities == 0) ||
                context.getSystemService(NotificationManager::class.java).currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL)) {
            interruptPlayback()
        }
    }
    private fun interruptPlayback(token: PlaybackToken? = audioToken) {
        if (token == null || token != readinessToken) return
        stopAudio()
        scope.launch { coordinator.get().interruptPlayback(token) }
    }
    fun exactAlarmAvailable() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
    fun fullScreenIntentAvailable() = Build.VERSION.SDK_INT < 34 ||
        context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
    fun soundEnabled() = prefs.getBoolean("sound_enabled", true)
    fun vibrationEnabled() = prefs.getBoolean("vibration_enabled", false)
    fun setVibrationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("vibration_enabled", enabled).apply()
        if (!enabled && vibrating) { vibrator.cancel(); vibrating = false }
    }
    fun isAlertPlaying() = player?.isPlaying == true
    fun setSoundEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("sound_enabled", enabled).apply()
        if (!enabled) interruptPlayback()
    }
    override fun onActivityResumed(activity: Activity) {
        resumedActivities++
        trace("activity_resumed", coordinator.get().state.value, "activity=${activity.javaClass.simpleName}")
    }
    override fun onActivityPaused(activity: Activity) {
        resumedActivities = (resumedActivities - 1).coerceAtLeast(0)
        trace("activity_paused", coordinator.get().state.value, "activity=${activity.javaClass.simpleName}")
        monitorPlayback()
    }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}

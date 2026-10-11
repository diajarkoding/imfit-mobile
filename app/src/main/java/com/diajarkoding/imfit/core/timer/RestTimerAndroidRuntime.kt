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
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.core.notification.NotificationChannels
import com.diajarkoding.imfit.core.notification.WorkoutNotificationManager
import com.diajarkoding.imfit.domain.repository.AuthRepository
import com.diajarkoding.imfit.domain.repository.WorkoutRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.launch

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
    private var cardPostElapsedMs: Long? = null
    private val userDismissed = mutableSetOf<String>()
    private var legacyListener: AudioManager.OnAudioFocusChangeListener? = null
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()

    fun register(application: Application) = application.registerActivityLifecycleCallbacks(this)

    override suspend fun validSession(state: RestTimerState): Boolean =
        auth.get().getLocalUserId() == state.userId && workouts.get().getActiveSession()?.id == state.workoutId

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
        }
        return PendingIntent.getBroadcast(context, 90, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    override fun schedule(state: RestTimerState): SchedulingMode {
        val pending = pendingIntent(state)
        // Track identity independently so a corrupt DataStore can still clean the old alarm.
        prefs.edit().putString("alarm_uri", "imfit-rest://alarm/${state.timerId}/${state.generation}").apply()
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, state.deadlineElapsedMs, pending)
                SchedulingMode.EXACT
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, state.deadlineElapsedMs, pending)
                SchedulingMode.BEST_EFFORT
            }
        } catch (_: SecurityException) {
            try {
                alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, state.deadlineElapsedMs, pending)
                SchedulingMode.BEST_EFFORT
            } catch (_: RuntimeException) { SchedulingMode.UNAVAILABLE }
        } catch (_: RuntimeException) { SchedulingMode.UNAVAILABLE }
    }

    override fun cancelAlarm(state: RestTimerState) {
        alarms.cancel(pendingIntent(state))
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

    override fun showFinished(state: RestTimerState) {
        if (state.timerId in userDismissed) {
            // The user removed this card; never repost it, only ensure it stays gone.
            notifications.dismissRestFinished()
            posted = false
            if (shownTimerId == state.timerId) shownTimerId = null
            return
        }
        if (shownTimerId == state.timerId && posted) {
            // Self-heal a system-removed card; user removal always carries a dismissal mark.
            if (!isCardPresent()) {
                posted = false
                shownTimerId = null
                cardPostElapsedMs = null
            } else return
        }
        posted = notificationsAvailable() && notifications.showRestFinished(state)
        if (posted) {
            shownTimerId = state.timerId
            cardPostElapsedMs = android.os.SystemClock.elapsedRealtime()
        }
    }
    private fun isCardPresent(): Boolean = context.getSystemService(NotificationManager::class.java)
        .activeNotifications.any { it.id == NotificationChannels.REST_FINISHED_NOTIFICATION_ID }
    override fun noteUserDismissed(timerId: String) {
        userDismissed.add(timerId)
    }
    override fun clearDismissed() {
        userDismissed.clear()
    }
    override fun dismissFinished() {
        notifications.dismissRestFinished()
        posted = false
        shownTimerId = null
        cardPostElapsedMs = null
    }
    override fun controlsAvailable(): Boolean {
        val cardPresent = posted && notificationsAvailable() && context.getSystemService(NotificationManager::class.java)
            .activeNotifications.any { it.id == NotificationChannels.REST_FINISHED_NOTIFICATION_ID }
        if (cardPresent) cardPostElapsedMs = null
        return cardPresent
    }
    override fun controlsPending(): Boolean = posted && notificationsAvailable() &&
        cardPostElapsedMs?.let { android.os.SystemClock.elapsedRealtime() - it < 1500 } == true

    override fun startAudio(): Boolean {
        if (player != null || vibrating) return true
        if (!controlsAvailable() || (!foregroundServiceActive && resumedActivities == 0)) return false
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
        }
        if (!playSound) return vibrating
        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            if (change != AudioManager.AUDIOFOCUS_GAIN) interruptPlayback()
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
        if (granted != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { stopAudio(); return false }
        return try {
            val next = MediaPlayer()
            player = next
            next.setAudioAttributes(attributes)
            context.resources.openRawResourceFd(R.raw.imfit_rest_done).use { descriptor ->
                next.setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length)
            }
            next.isLooping = true
            next.setOnErrorListener { _, _, _ -> interruptPlayback(); true }
            next.prepare()
            next.start()
            if (com.diajarkoding.imfit.BuildConfig.DEBUG) android.util.Log.d("IMFITRest", "audio started now=${android.os.SystemClock.elapsedRealtime()}")
            true
        } catch (_: Exception) { stopAudio(); false }
    }

    override fun stopAudio() {
        if ((player != null || vibrating) && com.diajarkoding.imfit.BuildConfig.DEBUG) android.util.Log.d("IMFITRest", "audio/vibration stopped now=${android.os.SystemClock.elapsedRealtime()}")
        player?.release()
        player = null
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
                context.getSystemService(NotificationManager::class.java).currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL)) {
            interruptPlayback()
        }
    }
    private fun interruptPlayback() {
        stopAudio()
        val timerId = coordinator.get().state.value?.timerId
        scope.launch { if (timerId != null) coordinator.get().silence(timerId) }
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
    override fun onActivityResumed(activity: Activity) { resumedActivities++ }
    override fun onActivityPaused(activity: Activity) {
        resumedActivities = (resumedActivities - 1).coerceAtLeast(0)
        monitorPlayback()
    }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}

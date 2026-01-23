 package com.diajarkoding.imfit.core.service
 
 import android.app.Service
 import android.content.Intent
 import android.content.pm.ServiceInfo
 import android.os.Binder
 import android.os.Build
 import android.os.IBinder
import android.os.PowerManager
import com.diajarkoding.imfit.core.constants.WorkoutConstants
 import android.util.Log
 import androidx.core.app.ServiceCompat
 import com.diajarkoding.imfit.core.model.WorkoutTimerUpdate
 import com.diajarkoding.imfit.core.notification.NotificationChannels
 import com.diajarkoding.imfit.core.notification.WorkoutNotificationManager
 import dagger.hilt.android.AndroidEntryPoint
 import kotlinx.coroutines.CoroutineScope
 import kotlinx.coroutines.Dispatchers
 import kotlinx.coroutines.SupervisorJob
 import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
 import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
 import kotlinx.coroutines.flow.StateFlow
 import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
 import javax.inject.Inject
 
 @AndroidEntryPoint
 class WorkoutService : Service() {
     
     @Inject
     lateinit var notificationManager: WorkoutNotificationManager
     
     private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
     private val binder = WorkoutBinder()
     
     private val _isRunning = MutableStateFlow(false)
     val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
     
    private val _restTimerFinished = MutableSharedFlow<Unit>()
    val restTimerFinished: SharedFlow<Unit> = _restTimerFinished.asSharedFlow()
    
    private var wakeLock: PowerManager.WakeLock? = null
    private var workoutTimerJob: Job? = null
    private var restTimerJob: Job? = null
    
     private var currentWorkoutName: String = ""
    private var workoutStartTime: Long = 0
    private var totalPausedTimeMs: Long = 0
    private var lastPauseTime: Long? = null
     private var currentCompletedSets: Int = 0
     private var currentTotalSets: Int = 0
     private var currentTotalVolume: Float = 0f
     private var currentIsPaused: Boolean = false
    private var currentTemplateId: String? = null
    
    private var isRestTimerActive: Boolean = false
    private var restTimerRemainingSeconds: Int = 0
    private var restTimerExerciseName: String? = null
     
     inner class WorkoutBinder : Binder() {
         fun getService(): WorkoutService = this@WorkoutService
     }
     
     override fun onBind(intent: Intent?): IBinder = binder
     
     override fun onCreate() {
         super.onCreate()
         Log.d(TAG, "WorkoutService created")
        acquireWakeLock()
     }
     
     override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
         Log.d(TAG, "onStartCommand: ${intent?.action}")
         
         when (intent?.action) {
             ACTION_START_WORKOUT -> {
                // Don't restart if already running - just update metadata
                if (_isRunning.value) {
                    Log.d(TAG, "Service already running, skipping start")
                    return START_STICKY
                }
                
                 val workoutName = intent.getStringExtra(EXTRA_WORKOUT_NAME) ?: "Workout"
                val templateId = intent.getStringExtra(EXTRA_TEMPLATE_ID)
                val startTime = intent.getLongExtra(EXTRA_START_TIME, System.currentTimeMillis())
                val pausedTime = intent.getLongExtra(EXTRA_TOTAL_PAUSED_TIME, 0L)
                currentTemplateId = templateId
                workoutStartTime = startTime
                totalPausedTimeMs = pausedTime
                startForegroundWorkout(workoutName, templateId, startTime)
             }
             ACTION_UPDATE_WORKOUT -> {
                 val update = WorkoutTimerUpdate(
                     workoutName = intent.getStringExtra(EXTRA_WORKOUT_NAME) ?: currentWorkoutName,
                     elapsedSeconds = intent.getLongExtra(EXTRA_ELAPSED_SECONDS, 0),
                     completedSets = intent.getIntExtra(EXTRA_COMPLETED_SETS, 0),
                     totalSets = intent.getIntExtra(EXTRA_TOTAL_SETS, 0),
                     totalVolume = intent.getFloatExtra(EXTRA_TOTAL_VOLUME, 0f),
                     isPaused = intent.getBooleanExtra(EXTRA_IS_PAUSED, false)
                 )
                 updateWorkoutNotification(update)
             }
            ACTION_START_REST -> {
                 val remainingSeconds = intent.getIntExtra(EXTRA_REMAINING_SECONDS, 0)
                 val exerciseName = intent.getStringExtra(EXTRA_EXERCISE_NAME)
                startRestTimer(remainingSeconds, exerciseName)
            }
            ACTION_SKIP_REST -> {
                skipRestTimer()
            }
            ACTION_PAUSE_WORKOUT -> {
                pauseWorkoutTimer()
            }
            ACTION_RESUME_WORKOUT -> {
                resumeWorkoutTimer()
             }
             ACTION_STOP_WORKOUT -> {
                 stopWorkout()
             }
         }
         
         return START_STICKY
     }
     
    private fun startForegroundWorkout(workoutName: String, templateId: String?, startTime: Long) {
         currentWorkoutName = workoutName
        currentTemplateId = templateId
        workoutStartTime = startTime
         _isRunning.value = true
         
        startWorkoutTimer()
        
         val notification = notificationManager.buildWorkoutNotification(
             workoutName = workoutName,
             elapsedTime = "00:00:00",
             completedSets = 0,
             totalSets = 0,
             totalVolume = 0f,
            isPaused = false,
            templateId = templateId
         )
         
         try {
             if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                 ServiceCompat.startForeground(
                     this,
                     NotificationChannels.WORKOUT_NOTIFICATION_ID,
                     notification,
                     ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                 )
             } else {
                 startForeground(NotificationChannels.WORKOUT_NOTIFICATION_ID, notification)
             }
             Log.d(TAG, "Started foreground service for workout: $workoutName")
         } catch (e: Exception) {
             Log.e(TAG, "Failed to start foreground: ${e.message}", e)
         }
     }
     
    private fun startWorkoutTimer() {
        workoutTimerJob?.cancel()
        workoutTimerJob = serviceScope.launch {
            while (_isRunning.value) {
                if (!currentIsPaused && !isRestTimerActive) {
                    val elapsed = calculateElapsedSeconds()
                    notificationManager.showWorkoutNotification(
                        workoutName = currentWorkoutName,
                        elapsedTime = formatElapsedTime(elapsed),
                        completedSets = currentCompletedSets,
                        totalSets = currentTotalSets,
                        totalVolume = currentTotalVolume,
                        isPaused = currentIsPaused,
                        templateId = currentTemplateId
                    )
                }
                delay(1000)
            }
        }
    }
    
    private fun calculateElapsedSeconds(): Long {
        val totalTime = System.currentTimeMillis() - workoutStartTime
        val currentPauseDuration = if (currentIsPaused && lastPauseTime != null) {
            System.currentTimeMillis() - lastPauseTime!!
        } else {
            0L
        }
        return (totalTime - totalPausedTimeMs - currentPauseDuration) / 1000
    }
    
    private fun pauseWorkoutTimer() {
        if (!currentIsPaused) {
            currentIsPaused = true
            lastPauseTime = System.currentTimeMillis()
            notificationManager.showWorkoutNotification(
                workoutName = currentWorkoutName,
                elapsedTime = formatElapsedTime(calculateElapsedSeconds()),
                completedSets = currentCompletedSets,
                totalSets = currentTotalSets,
                totalVolume = currentTotalVolume,
                isPaused = true,
                templateId = currentTemplateId
            )
        }
    }
    
    private fun resumeWorkoutTimer() {
        if (currentIsPaused) {
            lastPauseTime?.let { pauseStart ->
                totalPausedTimeMs += System.currentTimeMillis() - pauseStart
            }
            currentIsPaused = false
            lastPauseTime = null
        }
    }
    
    private fun startRestTimer(seconds: Int, exerciseName: String?) {
        restTimerJob?.cancel()
        isRestTimerActive = true
        restTimerRemainingSeconds = seconds
        restTimerExerciseName = exerciseName
        
        notificationManager.showRestTimerNotification(seconds, exerciseName)
        
        restTimerJob = serviceScope.launch {
            var remaining = seconds
            while (remaining > 0 && isRestTimerActive) {
                delay(1000)
                remaining--
                restTimerRemainingSeconds = remaining
                if (isRestTimerActive) {
                    notificationManager.showRestTimerNotification(remaining, exerciseName)
                }
            }
            
            if (isRestTimerActive) {
                isRestTimerActive = false
                restTimerRemainingSeconds = 0
                _restTimerFinished.emit(Unit)
                
                notificationManager.showWorkoutNotification(
                    workoutName = currentWorkoutName,
                    elapsedTime = formatElapsedTime(calculateElapsedSeconds()),
                    completedSets = currentCompletedSets,
                    totalSets = currentTotalSets,
                    totalVolume = currentTotalVolume,
                    isPaused = currentIsPaused,
                    templateId = currentTemplateId
                )
            }
        }
    }
    
    private fun skipRestTimer() {
        restTimerJob?.cancel()
        isRestTimerActive = false
        restTimerRemainingSeconds = 0
        
        notificationManager.showWorkoutNotification(
            workoutName = currentWorkoutName,
            elapsedTime = formatElapsedTime(calculateElapsedSeconds()),
            completedSets = currentCompletedSets,
            totalSets = currentTotalSets,
            totalVolume = currentTotalVolume,
            isPaused = currentIsPaused,
            templateId = currentTemplateId
        )
    }
    
    fun getRestTimerState(): RestTimerState {
        return RestTimerState(
            isActive = isRestTimerActive,
            remainingSeconds = restTimerRemainingSeconds,
            exerciseName = restTimerExerciseName
        )
    }
    
     fun updateWorkoutNotification(update: WorkoutTimerUpdate) {
         if (!_isRunning.value) return
         
         currentWorkoutName = update.workoutName
         currentCompletedSets = update.completedSets
         currentTotalSets = update.totalSets
         currentTotalVolume = update.totalVolume
         currentIsPaused = update.isPaused
     }
     
     fun stopWorkout() {
         Log.d(TAG, "Stopping workout service")
         _isRunning.value = false
        workoutTimerJob?.cancel()
        restTimerJob?.cancel()
        releaseWakeLock()
         notificationManager.dismissNotification()
         stopForeground(STOP_FOREGROUND_REMOVE)
         stopSelf()
     }
     
     override fun onDestroy() {
         super.onDestroy()
         serviceScope.cancel()
         _isRunning.value = false
        releaseWakeLock()
         Log.d(TAG, "WorkoutService destroyed")
     }
     
    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "IMFIT::WorkoutWakeLock"
            ).apply {
                acquire(WorkoutConstants.MAX_WAKELOCK_DURATION_MS) // 4 hours max
            }
            Log.d(TAG, "WakeLock acquired")
        }
    }
    

    /**
     * Called when the task is removed from recent apps.
     * Ensures WakeLock is released to prevent battery drain.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "Task removed, releasing resources")
        releaseWakeLock()
        super.onTaskRemoved(rootIntent)
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "WakeLock released")
            }
        }
        wakeLock = null
    }
    
     private fun formatElapsedTime(seconds: Long): String {
         val hh = seconds / 3600
         val mm = (seconds % 3600) / 60
         val ss = seconds % 60
         return String.format(WorkoutConstants.TIME_FORMAT_PATTERN, hh, mm, ss)
     }
     
     companion object {
         private const val TAG = "WorkoutService"
         
         const val ACTION_START_WORKOUT = "com.diajarkoding.imfit.action.START_WORKOUT"
         const val ACTION_UPDATE_WORKOUT = "com.diajarkoding.imfit.action.UPDATE_WORKOUT"
        const val ACTION_START_REST = "com.diajarkoding.imfit.action.START_REST"
        const val ACTION_SKIP_REST = "com.diajarkoding.imfit.action.SKIP_REST"
        const val ACTION_PAUSE_WORKOUT = "com.diajarkoding.imfit.action.PAUSE_WORKOUT"
        const val ACTION_RESUME_WORKOUT = "com.diajarkoding.imfit.action.RESUME_WORKOUT"
         const val ACTION_STOP_WORKOUT = "com.diajarkoding.imfit.action.STOP_WORKOUT"
         
         const val EXTRA_WORKOUT_NAME = "extra_workout_name"
        const val EXTRA_TEMPLATE_ID = "extra_template_id"
        const val EXTRA_START_TIME = "extra_start_time"
        const val EXTRA_TOTAL_PAUSED_TIME = "extra_total_paused_time"
         const val EXTRA_ELAPSED_SECONDS = "extra_elapsed_seconds"
         const val EXTRA_COMPLETED_SETS = "extra_completed_sets"
         const val EXTRA_TOTAL_SETS = "extra_total_sets"
         const val EXTRA_TOTAL_VOLUME = "extra_total_volume"
         const val EXTRA_IS_PAUSED = "extra_is_paused"
         const val EXTRA_REMAINING_SECONDS = "extra_remaining_seconds"
         const val EXTRA_EXERCISE_NAME = "extra_exercise_name"
     }
 }

data class RestTimerState(
    val isActive: Boolean,
    val remainingSeconds: Int,
    val exerciseName: String?
)

 package com.diajarkoding.imfit.core.service
 
 import android.app.Service
 import android.content.Intent
 import android.content.pm.ServiceInfo
 import android.os.Binder
 import android.os.Build
 import android.os.IBinder
 import android.util.Log
 import androidx.core.app.ServiceCompat
 import com.diajarkoding.imfit.core.model.RestTimerUpdate
 import com.diajarkoding.imfit.core.model.WorkoutTimerUpdate
 import com.diajarkoding.imfit.core.notification.NotificationChannels
 import com.diajarkoding.imfit.core.notification.WorkoutNotificationManager
 import dagger.hilt.android.AndroidEntryPoint
 import kotlinx.coroutines.CoroutineScope
 import kotlinx.coroutines.Dispatchers
 import kotlinx.coroutines.SupervisorJob
 import kotlinx.coroutines.cancel
 import kotlinx.coroutines.flow.MutableStateFlow
 import kotlinx.coroutines.flow.StateFlow
 import kotlinx.coroutines.flow.asStateFlow
 import javax.inject.Inject
 
 @AndroidEntryPoint
 class WorkoutService : Service() {
     
     @Inject
     lateinit var notificationManager: WorkoutNotificationManager
     
     private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
     private val binder = WorkoutBinder()
     
     private val _isRunning = MutableStateFlow(false)
     val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
     
     private var currentWorkoutName: String = ""
     private var currentElapsedSeconds: Long = 0
     private var currentCompletedSets: Int = 0
     private var currentTotalSets: Int = 0
     private var currentTotalVolume: Float = 0f
     private var currentIsPaused: Boolean = false
    private var currentTemplateId: String? = null
    
    private var isRestTimerActive: Boolean = false
     
     inner class WorkoutBinder : Binder() {
         fun getService(): WorkoutService = this@WorkoutService
     }
     
     override fun onBind(intent: Intent?): IBinder = binder
     
     override fun onCreate() {
         super.onCreate()
         Log.d(TAG, "WorkoutService created")
     }
     
     override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
         Log.d(TAG, "onStartCommand: ${intent?.action}")
         
         when (intent?.action) {
             ACTION_START_WORKOUT -> {
                 val workoutName = intent.getStringExtra(EXTRA_WORKOUT_NAME) ?: "Workout"
                val templateId = intent.getStringExtra(EXTRA_TEMPLATE_ID)
                currentTemplateId = templateId
                startForegroundWorkout(workoutName, templateId)
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
             ACTION_UPDATE_REST -> {
                 val remainingSeconds = intent.getIntExtra(EXTRA_REMAINING_SECONDS, 0)
                 val exerciseName = intent.getStringExtra(EXTRA_EXERCISE_NAME)
                 val isActive = intent.getBooleanExtra(EXTRA_IS_REST_ACTIVE, false)
                 updateRestTimerNotification(RestTimerUpdate(remainingSeconds, isActive, exerciseName))
             }
             ACTION_STOP_WORKOUT -> {
                 stopWorkout()
             }
         }
         
         return START_STICKY
     }
     
    private fun startForegroundWorkout(workoutName: String, templateId: String?) {
         currentWorkoutName = workoutName
        currentTemplateId = templateId
         _isRunning.value = true
         
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
     
     fun updateWorkoutNotification(update: WorkoutTimerUpdate) {
         if (!_isRunning.value) return
         
        // Don't update workout notification while rest timer is active
        // This prevents flickering between workout and rest notifications
        if (isRestTimerActive) return
        
         currentWorkoutName = update.workoutName
         currentElapsedSeconds = update.elapsedSeconds
         currentCompletedSets = update.completedSets
         currentTotalSets = update.totalSets
         currentTotalVolume = update.totalVolume
         currentIsPaused = update.isPaused
         
         val elapsedTime = formatElapsedTime(update.elapsedSeconds)
         
         notificationManager.showWorkoutNotification(
             workoutName = update.workoutName,
             elapsedTime = elapsedTime,
             completedSets = update.completedSets,
             totalSets = update.totalSets,
             totalVolume = update.totalVolume,
            isPaused = update.isPaused,
            templateId = currentTemplateId
         )
     }
     
     fun updateRestTimerNotification(update: RestTimerUpdate) {
         if (!_isRunning.value) return
         
        isRestTimerActive = update.isRestActive
        
         if (update.isRestActive) {
             notificationManager.showRestTimerNotification(
                 remainingSeconds = update.remainingSeconds,
                 exerciseName = update.currentExerciseName
             )
         } else {
             // Rest ended, show workout notification again
             notificationManager.showWorkoutNotification(
                 workoutName = currentWorkoutName,
                 elapsedTime = formatElapsedTime(currentElapsedSeconds),
                 completedSets = currentCompletedSets,
                 totalSets = currentTotalSets,
                 totalVolume = currentTotalVolume,
                isPaused = currentIsPaused,
                templateId = currentTemplateId
             )
         }
     }
     
     fun stopWorkout() {
         Log.d(TAG, "Stopping workout service")
         _isRunning.value = false
         notificationManager.dismissNotification()
         stopForeground(STOP_FOREGROUND_REMOVE)
         stopSelf()
     }
     
     override fun onDestroy() {
         super.onDestroy()
         serviceScope.cancel()
         _isRunning.value = false
         Log.d(TAG, "WorkoutService destroyed")
     }
     
     private fun formatElapsedTime(seconds: Long): String {
         val hh = seconds / 3600
         val mm = (seconds % 3600) / 60
         val ss = seconds % 60
         return String.format("%02d:%02d:%02d", hh, mm, ss)
     }
     
     companion object {
         private const val TAG = "WorkoutService"
         
         const val ACTION_START_WORKOUT = "com.diajarkoding.imfit.action.START_WORKOUT"
         const val ACTION_UPDATE_WORKOUT = "com.diajarkoding.imfit.action.UPDATE_WORKOUT"
         const val ACTION_UPDATE_REST = "com.diajarkoding.imfit.action.UPDATE_REST"
         const val ACTION_STOP_WORKOUT = "com.diajarkoding.imfit.action.STOP_WORKOUT"
         
         const val EXTRA_WORKOUT_NAME = "extra_workout_name"
        const val EXTRA_TEMPLATE_ID = "extra_template_id"
         const val EXTRA_ELAPSED_SECONDS = "extra_elapsed_seconds"
         const val EXTRA_COMPLETED_SETS = "extra_completed_sets"
         const val EXTRA_TOTAL_SETS = "extra_total_sets"
         const val EXTRA_TOTAL_VOLUME = "extra_total_volume"
         const val EXTRA_IS_PAUSED = "extra_is_paused"
         const val EXTRA_REMAINING_SECONDS = "extra_remaining_seconds"
         const val EXTRA_EXERCISE_NAME = "extra_exercise_name"
         const val EXTRA_IS_REST_ACTIVE = "extra_is_rest_active"
     }
 }

 package com.diajarkoding.imfit.core.notification
 
 import android.app.Notification
 import android.app.PendingIntent
 import android.content.Context
 import android.content.Intent
 import androidx.core.app.NotificationCompat
 import androidx.core.app.NotificationManagerCompat
 import com.diajarkoding.imfit.MainActivity
 import com.diajarkoding.imfit.R
 import javax.inject.Inject
 import javax.inject.Singleton
 
 @Singleton
 class WorkoutNotificationManager @Inject constructor(
     private val context: Context
 ) {
     private val notificationManager = NotificationManagerCompat.from(context)
    
    // Store current template ID for navigation
    private var currentTemplateId: String? = null
     
    private fun createContentIntent(templateId: String? = null): PendingIntent {
         val intent = Intent(context, MainActivity::class.java).apply {
             flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
             putExtra(EXTRA_OPEN_WORKOUT, true)
            templateId?.let { putExtra(EXTRA_TEMPLATE_ID, it) }
                ?: currentTemplateId?.let { putExtra(EXTRA_TEMPLATE_ID, it) }
         }
         return PendingIntent.getActivity(
             context,
             0,
             intent,
             PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
         )
     }
     
     private fun createStopIntent(): PendingIntent {
         val intent = Intent(context, WorkoutNotificationReceiver::class.java).apply {
             action = ACTION_STOP_WORKOUT
         }
         return PendingIntent.getBroadcast(
             context,
             1,
             intent,
             PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
         )
     }
     
     private fun createPauseIntent(): PendingIntent {
         val intent = Intent(context, WorkoutNotificationReceiver::class.java).apply {
             action = ACTION_PAUSE_WORKOUT
         }
         return PendingIntent.getBroadcast(
             context,
             2,
             intent,
             PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
         )
     }
     
     private fun createResumeIntent(): PendingIntent {
         val intent = Intent(context, WorkoutNotificationReceiver::class.java).apply {
             action = ACTION_RESUME_WORKOUT
         }
         return PendingIntent.getBroadcast(
             context,
             3,
             intent,
             PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
         )
     }
     
     private fun createSkipRestIntent(): PendingIntent {
         val intent = Intent(context, WorkoutNotificationReceiver::class.java).apply {
             action = ACTION_SKIP_REST
         }
         return PendingIntent.getBroadcast(
             context,
             4,
             intent,
             PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
         )
     }
     
     fun buildWorkoutNotification(
         workoutName: String,
         elapsedTime: String,
         completedSets: Int,
         totalSets: Int,
         totalVolume: Float,
        isPaused: Boolean = false,
        templateId: String? = null
     ): Notification {
        templateId?.let { currentTemplateId = it }
        
         val title = if (isPaused) {
             context.getString(R.string.notification_workout_paused)
         } else {
             workoutName
         }
         
         val contentText = context.getString(
             R.string.notification_workout_content,
             elapsedTime,
             completedSets,
             totalSets,
             totalVolume
         )
         
        return NotificationCompat.Builder(context, NotificationChannels.WORKOUT_ACTIVE_CHANNEL_ID)
             .setSmallIcon(R.drawable.ic_fitness)
             .setContentTitle(title)
             .setContentText(contentText)
             .setContentIntent(createContentIntent())
             .setOngoing(true)
             .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
             .setCategory(NotificationCompat.CATEGORY_SERVICE)
             .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                R.drawable.ic_stop,
                context.getString(R.string.notification_stop),
                createStopIntent()
            )
            .addAction(
                if (isPaused) R.drawable.ic_play else R.drawable.ic_pause,
                context.getString(if (isPaused) R.string.notification_resume else R.string.notification_pause),
                if (isPaused) createResumeIntent() else createPauseIntent()
            )
            .build()
     }
     
     fun buildRestTimerNotification(
         remainingSeconds: Int,
         exerciseName: String?
     ): Notification {
         val minutes = remainingSeconds / 60
         val seconds = remainingSeconds % 60
         val timeText = String.format("%02d:%02d", minutes, seconds)
         
         val title = context.getString(R.string.notification_rest_timer_title, timeText)
         val contentText = exerciseName?.let {
             context.getString(R.string.notification_resting_after, it)
         } ?: context.getString(R.string.notification_rest_timer)
         
        // Use same icon as workout notification to prevent flickering
        return NotificationCompat.Builder(context, NotificationChannels.WORKOUT_ACTIVE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_fitness)
             .setContentTitle(title)
             .setContentText(contentText)
             .setContentIntent(createContentIntent())
             .setOngoing(true)
             .setOnlyAlertOnce(true)
             .setPriority(NotificationCompat.PRIORITY_HIGH)
             .setCategory(NotificationCompat.CATEGORY_ALARM)
             .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
             .addAction(
                 R.drawable.ic_skip,
                 context.getString(R.string.notification_skip_rest),
                 createSkipRestIntent()
             )
             .addAction(
                 R.drawable.ic_stop,
                 context.getString(R.string.notification_stop),
                 createStopIntent()
             )
             .build()
     }
     
     fun showWorkoutNotification(
         workoutName: String,
         elapsedTime: String,
         completedSets: Int,
         totalSets: Int,
         totalVolume: Float,
        isPaused: Boolean = false,
        templateId: String? = null
     ) {
         try {
             val notification = buildWorkoutNotification(
                workoutName, elapsedTime, completedSets, totalSets, totalVolume, isPaused, templateId
             )
             notificationManager.notify(NotificationChannels.WORKOUT_NOTIFICATION_ID, notification)
         } catch (e: SecurityException) {
             android.util.Log.w("WorkoutNotification", "Notification permission not granted")
         }
     }
     
     fun showRestTimerNotification(remainingSeconds: Int, exerciseName: String?) {
         try {
             val notification = buildRestTimerNotification(remainingSeconds, exerciseName)
             notificationManager.notify(NotificationChannels.WORKOUT_NOTIFICATION_ID, notification)
         } catch (e: SecurityException) {
             android.util.Log.w("WorkoutNotification", "Notification permission not granted")
         }
     }
     
     fun dismissNotification() {
         notificationManager.cancel(NotificationChannels.WORKOUT_NOTIFICATION_ID)
     }
     
     companion object {
         const val EXTRA_OPEN_WORKOUT = "extra_open_workout"
        const val EXTRA_TEMPLATE_ID = "extra_template_id"
         const val ACTION_STOP_WORKOUT = "com.diajarkoding.imfit.ACTION_STOP_WORKOUT"
         const val ACTION_PAUSE_WORKOUT = "com.diajarkoding.imfit.ACTION_PAUSE_WORKOUT"
         const val ACTION_RESUME_WORKOUT = "com.diajarkoding.imfit.ACTION_RESUME_WORKOUT"
         const val ACTION_SKIP_REST = "com.diajarkoding.imfit.ACTION_SKIP_REST"
     }
 }

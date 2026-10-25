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
import android.app.NotificationManager
import android.os.Build
import android.os.SystemClock
import android.os.Bundle
import com.diajarkoding.imfit.core.timer.RestTimerState
import com.diajarkoding.imfit.core.timer.RestTimerTrace
 
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
     
      private fun createSkipRestIntent(timerId: String?): PendingIntent {
          val intent = Intent(context, WorkoutNotificationReceiver::class.java).apply {
              action = ACTION_SKIP_REST
              data = android.net.Uri.parse("imfit-rest://skip/$timerId")
              putExtra("timer_id", timerId)
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
     
      private fun createExtendIntent(timerId: String?, generation: Long): PendingIntent {
          val intent = Intent(context, WorkoutNotificationReceiver::class.java).apply {
              action = WorkoutActions.EXTEND
              data = android.net.Uri.parse("imfit-rest://extend/$timerId/$generation")
              putExtra("timer_id", timerId)
              putExtra("generation", generation)
          }
          return PendingIntent.getBroadcast(context, 5, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
      }

       fun buildRestTimerNotification(
           state: RestTimerState,
           templateId: String? = null
      ): Notification {
          templateId?.let { currentTemplateId = it }
          val title = context.getString(R.string.notification_rest_timer)
          val contentText = context.getString(R.string.notification_resting_after, state.exerciseName)
          val whenMs = System.currentTimeMillis() + (state.deadlineElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0)
         
        // Use same icon as workout notification to prevent flickering
          return NotificationCompat.Builder(context, NotificationChannels.WORKOUT_ACTIVE_CHANNEL_ID)
             .setSmallIcon(R.drawable.ic_fitness)
              .setContentTitle(title)
              .setContentText(contentText)
              .setContentIntent(createContentIntent())
              .setOngoing(true)
              .setOnlyAlertOnce(true)
              .setPriority(NotificationCompat.PRIORITY_HIGH)
               .addExtras(Bundle().apply {
                   putString(EXTRA_REST_TIMER_ID, state.timerId)
                   putLong(EXTRA_REST_GENERATION, state.generation)
               })
               .setWhen(whenMs)
              .setUsesChronometer(true)
              .setChronometerCountDown(true)
             .setCategory(NotificationCompat.CATEGORY_ALARM)
             .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
             .addAction(
                 R.drawable.ic_skip,
                 context.getString(R.string.notification_skip_rest),
                   createSkipRestIntent(state.timerId)
              )
               .addAction(R.drawable.ic_timer, context.getString(R.string.rest_extend), createExtendIntent(state.timerId, state.generation))
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
      ): Boolean {
         try {
             val notification = buildWorkoutNotification(
                workoutName, elapsedTime, completedSets, totalSets, totalVolume, isPaused, templateId
             )
              notificationManager.notify(NotificationChannels.WORKOUT_NOTIFICATION_ID, notification)
              return true
          } catch (e: SecurityException) {
              android.util.Log.w("WorkoutNotification", "Notification permission not granted")
              return false
          }
     }
     
       fun showRestTimerNotification(state: RestTimerState, templateId: String? = null): Boolean {
         try {
               val notification = buildRestTimerNotification(state, templateId)
              notificationManager.notify(NotificationChannels.WORKOUT_NOTIFICATION_ID, notification)
              return true
          } catch (e: SecurityException) {
              android.util.Log.w("WorkoutNotification", "Notification permission not granted")
              return false
         }
     }
     
      fun dismissNotification() {
          notificationManager.cancel(NotificationChannels.WORKOUT_NOTIFICATION_ID)
      }

       fun showRestFinished(state: RestTimerState, fullScreenAllowed: Boolean): Boolean {
          fun acknowledgeIntent(kind: String, requestCode: Int): PendingIntent {
              val intent = Intent(context, WorkoutNotificationReceiver::class.java).apply {
                  action = WorkoutActions.ACKNOWLEDGE
                  data = android.net.Uri.parse("imfit-rest://$kind/${state.timerId}/${state.generation}")
                  putExtra("timer_id", state.timerId)
                  putExtra("generation", state.generation)
              }
              return PendingIntent.getBroadcast(context, requestCode, intent,
                  PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
          }
          val stop = acknowledgeIntent("stop", 80)
          val dismiss = acknowledgeIntent("dismiss", 81)
          val fullScreen = PendingIntent.getActivity(context, 82,
              Intent(context, com.diajarkoding.imfit.core.timer.RestAlarmActivity::class.java).apply {
                  data = android.net.Uri.parse("imfit-rest://full-screen/${state.timerId}/${state.generation}")
                  putExtra("timer_id", state.timerId)
                  putExtra("generation", state.generation)
              }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
          val content = PendingIntent.getActivity(context, 83,
              Intent(context, MainActivity::class.java).apply {
                  flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                  data = android.net.Uri.parse("imfit-rest://open-workout/${state.timerId}/${state.generation}")
                  putExtra(EXTRA_OPEN_WORKOUT, true)
                  putExtra(EXTRA_TEMPLATE_ID, state.templateId)
                  putExtra(EXTRA_REST_TIMER_ID, state.timerId)
                  putExtra(EXTRA_REST_GENERATION, state.generation)
              },
              PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
          val notification = NotificationCompat.Builder(context, NotificationChannels.REST_FINISHED_CHANNEL_ID)
              .setSmallIcon(R.drawable.ic_timer)
              .setContentTitle(context.getString(R.string.rest_finished))
              .setContentText(state.exerciseName)
               .setContentIntent(content)
               .addExtras(Bundle().apply {
                   putString(EXTRA_REST_TIMER_ID, state.timerId)
                   putLong(EXTRA_REST_GENERATION, state.generation)
               })
               .apply { if (fullScreenAllowed) setFullScreenIntent(fullScreen, true) }
               .setUsesChronometer(false)
              .setOngoing(false).setAutoCancel(false).setOnlyAlertOnce(true)
              .setDeleteIntent(dismiss).setCategory(NotificationCompat.CATEGORY_ALARM)
              .setPriority(NotificationCompat.PRIORITY_HIGH)
              .addAction(R.drawable.ic_stop, context.getString(R.string.rest_stop_alert), stop)
              .build()
           return try {
               val importance = if (Build.VERSION.SDK_INT >= 26) context.getSystemService(NotificationManager::class.java)
                   .getNotificationChannel(NotificationChannels.REST_FINISHED_CHANNEL_ID)?.importance else null
               RestTimerTrace.event("notify_attempt", state, "notificationId=1002 channel=${NotificationChannels.REST_FINISHED_CHANNEL_ID} importance=$importance fsi=$fullScreenAllowed")
               notificationManager.notify(NotificationChannels.REST_FINISHED_NOTIFICATION_ID, notification)
               RestTimerTrace.event("notify_result", state, "notificationId=1002 success=true")
               true
            } catch (e: SecurityException) {
                RestTimerTrace.event("notify_result", state, "notificationId=1002 success=false exception=${e.javaClass.simpleName}")
                false
            } catch (e: RuntimeException) {
               RestTimerTrace.event("notify_result", state, "notificationId=1002 success=false exception=${e.javaClass.simpleName}")
               false
           }
      }

      fun dismissRestFinished() = notificationManager.cancel(NotificationChannels.REST_FINISHED_NOTIFICATION_ID)
     
      companion object {
          const val EXTRA_REST_TIMER_ID = "rest_timer_id"
          const val EXTRA_REST_GENERATION = "rest_generation"
         const val EXTRA_OPEN_WORKOUT = "extra_open_workout"
        const val EXTRA_TEMPLATE_ID = "extra_template_id"
          const val ACTION_STOP_WORKOUT = WorkoutActions.STOP
          const val ACTION_PAUSE_WORKOUT = WorkoutActions.PAUSE
          const val ACTION_RESUME_WORKOUT = WorkoutActions.RESUME
          const val ACTION_SKIP_REST = WorkoutActions.SKIP
     }
 }

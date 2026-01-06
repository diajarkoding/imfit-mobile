 package com.diajarkoding.imfit.core.notification
 
 import android.app.NotificationChannel
 import android.app.NotificationManager
 import android.content.Context
 import android.os.Build
 import androidx.core.app.NotificationManagerCompat
 
 object NotificationChannels {
     const val WORKOUT_ACTIVE_CHANNEL_ID = "workout_active_channel"
     const val WORKOUT_REST_TIMER_CHANNEL_ID = "workout_rest_timer_channel"
     
     const val WORKOUT_NOTIFICATION_ID = 1001
     
     fun createNotificationChannels(context: Context) {
         if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
             val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
             
             // Workout Active Channel
             val workoutActiveChannel = NotificationChannel(
                 WORKOUT_ACTIVE_CHANNEL_ID,
                 context.getString(com.diajarkoding.imfit.R.string.channel_workout_active),
                 NotificationManager.IMPORTANCE_LOW
             ).apply {
                 description = context.getString(com.diajarkoding.imfit.R.string.channel_workout_desc)
                 setShowBadge(false)
             }
             
             // Rest Timer Channel - Higher importance for countdown
             val restTimerChannel = NotificationChannel(
                 WORKOUT_REST_TIMER_CHANNEL_ID,
                 context.getString(com.diajarkoding.imfit.R.string.channel_workout_rest),
                 NotificationManager.IMPORTANCE_HIGH
             ).apply {
                 description = context.getString(com.diajarkoding.imfit.R.string.channel_workout_desc)
                 setShowBadge(false)
                 enableVibration(false)
                 setSound(null, null)
             }
             
             notificationManager.createNotificationChannel(workoutActiveChannel)
             notificationManager.createNotificationChannel(restTimerChannel)
         }
     }
 }

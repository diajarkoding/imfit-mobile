 package com.diajarkoding.imfit.core.notification
 
 import android.content.BroadcastReceiver
 import android.content.Context
 import android.content.Intent
 import android.util.Log
 
 class WorkoutNotificationReceiver : BroadcastReceiver() {
     
     override fun onReceive(context: Context, intent: Intent) {
         Log.d("WorkoutReceiver", "Received action: ${intent.action}")
         
         when (intent.action) {
             WorkoutNotificationManager.ACTION_STOP_WORKOUT -> {
                 // Broadcast to stop workout
                 val stopIntent = Intent(ACTION_WORKOUT_COMMAND).apply {
                     putExtra(EXTRA_COMMAND, COMMAND_STOP)
                     setPackage(context.packageName)
                 }
                 context.sendBroadcast(stopIntent)
             }
             WorkoutNotificationManager.ACTION_PAUSE_WORKOUT -> {
                 val pauseIntent = Intent(ACTION_WORKOUT_COMMAND).apply {
                     putExtra(EXTRA_COMMAND, COMMAND_PAUSE)
                     setPackage(context.packageName)
                 }
                 context.sendBroadcast(pauseIntent)
             }
             WorkoutNotificationManager.ACTION_RESUME_WORKOUT -> {
                 val resumeIntent = Intent(ACTION_WORKOUT_COMMAND).apply {
                     putExtra(EXTRA_COMMAND, COMMAND_RESUME)
                     setPackage(context.packageName)
                 }
                 context.sendBroadcast(resumeIntent)
             }
             WorkoutNotificationManager.ACTION_SKIP_REST -> {
                 val skipIntent = Intent(ACTION_WORKOUT_COMMAND).apply {
                     putExtra(EXTRA_COMMAND, COMMAND_SKIP_REST)
                     setPackage(context.packageName)
                 }
                 context.sendBroadcast(skipIntent)
             }
         }
     }
     
     companion object {
         const val ACTION_WORKOUT_COMMAND = "com.diajarkoding.imfit.ACTION_WORKOUT_COMMAND"
         const val EXTRA_COMMAND = "extra_command"
         const val COMMAND_STOP = "stop"
         const val COMMAND_PAUSE = "pause"
         const val COMMAND_RESUME = "resume"
         const val COMMAND_SKIP_REST = "skip_rest"
     }
 }

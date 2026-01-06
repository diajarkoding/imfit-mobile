 package com.diajarkoding.imfit.core.model
 
 data class RestTimerUpdate(
     val remainingSeconds: Int,
     val isRestActive: Boolean,
     val currentExerciseName: String? = null
 )
 
 data class WorkoutTimerUpdate(
     val workoutName: String,
     val elapsedSeconds: Long,
     val completedSets: Int,
     val totalSets: Int,
     val totalVolume: Float,
     val isPaused: Boolean = false
 )

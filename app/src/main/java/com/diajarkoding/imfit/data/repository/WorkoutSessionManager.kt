 package com.diajarkoding.imfit.data.repository
 
 import android.util.Log
 import com.diajarkoding.imfit.core.constants.WorkoutConstants
 import com.diajarkoding.imfit.core.data.WorkoutPreferences
 import com.diajarkoding.imfit.data.local.dao.ActiveSessionDao
 import com.diajarkoding.imfit.data.local.entity.ActiveSessionEntity
 import com.diajarkoding.imfit.domain.model.Exercise
 import com.diajarkoding.imfit.domain.model.ExerciseLog
 import com.diajarkoding.imfit.domain.model.MuscleCategory
 import com.diajarkoding.imfit.domain.model.WorkoutSession
 import com.diajarkoding.imfit.domain.model.WorkoutSet
 import com.diajarkoding.imfit.domain.model.WorkoutTemplate
 import io.github.jan.supabase.SupabaseClient
 import io.github.jan.supabase.auth.auth
 import kotlinx.serialization.Serializable
 import kotlinx.serialization.encodeToString
 import kotlinx.serialization.json.Json
 import java.util.UUID
 import javax.inject.Inject
 import javax.inject.Singleton
 
 /**
  * Manages active workout sessions.
  * Handles session persistence, pause/resume, and state recovery.
  */
 @Singleton
 class WorkoutSessionManager @Inject constructor(
     private val supabaseClient: SupabaseClient,
     private val activeSessionDao: ActiveSessionDao,
     private val workoutPreferences: WorkoutPreferences
 ) {
     
     companion object {
         private const val TAG = "WorkoutSessionManager"
     }
     
     // In-memory cache for fast access
     private var activeSession: WorkoutSession? = null
     
     private val json = Json { 
         ignoreUnknownKeys = true 
         encodeDefaults = true
     }
     
     // ==================== SESSION LIFECYCLE ====================
     
     /**
      * Starts a new workout session from a template.
      * Creates exercise logs with default values and persists to database.
      */
     suspend fun startWorkout(template: WorkoutTemplate): WorkoutSession {
         require(template.exercises.isNotEmpty()) { "Template must have at least one exercise" }
         
         val defaultRestSeconds = workoutPreferences.getDefaultRestTimerSeconds()
         
         val exerciseLogs = template.exercises.map { templateExercise ->
             ExerciseLog(
                 exercise = templateExercise.exercise,
                 sets = (1..templateExercise.sets).map { setNumber ->
                     WorkoutSet(
                         setNumber = setNumber,
                         weight = 0f,
                         reps = templateExercise.reps,
                         isCompleted = false
                     )
                 },
                 restSeconds = defaultRestSeconds ?: templateExercise.restSeconds
             )
         }
         
         val session = WorkoutSession(
             id = UUID.randomUUID().toString(),
             templateId = template.id,
             templateName = template.name,
             startTime = System.currentTimeMillis(),
             exerciseLogs = exerciseLogs
         )
         
         activeSession = session
         persistSession(session)
         
         return session
     }
     
     /**
      * Gets the current active session.
      * Restores from database if not in memory.
      */
     suspend fun getActiveSession(): WorkoutSession? {
         // Return cached session if available
         activeSession?.let { return it }
         
         // Try to restore from database
         return try {
             val userId = getCurrentUserId()
             val entity = activeSessionDao.getActiveSession(userId) ?: return null
             
             val sessionData = json.decodeFromString<SerializableSession>(entity.sessionDataJson)
             activeSession = sessionData.toDomain()
             Log.d(TAG, "Restored active session from Room: ${activeSession?.id}")
             activeSession
         } catch (e: Exception) {
             Log.e(TAG, "Failed to restore session from Room: ${e.message}", e)
             null
         }
     }
     
     /**
      * Updates the active session state.
      * Persists changes to database.
      */
     suspend fun updateActiveSession(session: WorkoutSession) {
         activeSession = session
         persistSession(session)
     }
     
     /**
      * Cancels the active workout without saving.
      * Clears both in-memory and database state.
      */
     suspend fun cancelWorkout() {
         try {
             val userId = getCurrentUserId()
             activeSessionDao.deleteSession(userId)
             Log.d(TAG, "Cancelled and deleted active session from database")
         } catch (e: Exception) {
             Log.e(TAG, "Failed to delete session from database: ${e.message}", e)
         }
         activeSession = null
     }
     
     /**
      * Clears the active session after successful completion.
      * Called by WorkoutLogRepository after saving the workout log.
      */
     suspend fun clearActiveSession(sessionId: String) {
         try {
             activeSessionDao.deleteSessionById(sessionId)
             Log.d(TAG, "Deleted active session from database: $sessionId")
         } catch (e: Exception) {
             Log.e(TAG, "Failed to delete session: ${e.message}", e)
         }
         activeSession = null
     }
     
     /**
      * Gets the current active session (non-suspending, from memory only).
      */
     fun getCurrentSession(): WorkoutSession? = activeSession
     
     // ==================== REST TIMER ====================
     
     /**
      * Sets a session-wide rest time override.
      */
     suspend fun updateSessionRestOverride(seconds: Int) {
         require(seconds >= 0) { "Rest seconds cannot be negative" }
         
         try {
             val userId = getCurrentUserId()
             val entity = activeSessionDao.getActiveSession(userId) ?: return
             val updatedEntity = entity.copy(
                 sessionRestOverride = seconds,
                 updatedAt = System.currentTimeMillis()
             )
             activeSessionDao.updateSession(updatedEntity)
             Log.d(TAG, "Updated session rest override: $seconds")
         } catch (e: Exception) {
             Log.e(TAG, "Failed to update session rest override: ${e.message}", e)
         }
     }
     
     /**
      * Gets the current session rest override.
      */
     suspend fun getSessionRestOverride(): Int? {
         return try {
             val userId = getCurrentUserId()
             activeSessionDao.getActiveSession(userId)?.sessionRestOverride
         } catch (e: Exception) {
             Log.e(TAG, "Failed to get session rest override: ${e.message}", e)
             null
         }
     }
     
     // ==================== PREFERENCES ====================
     
     /**
      * Sets the default rest timer for all workouts.
      */
     suspend fun setDefaultRestTimer(seconds: Int) {
         require(seconds >= 0) { "Rest seconds cannot be negative" }
         workoutPreferences.setDefaultRestTimerSeconds(seconds)
         Log.d(TAG, "Saved default rest timer: $seconds seconds")
     }
     
     /**
      * Gets the default rest timer.
      */
     suspend fun getDefaultRestTimer(): Int? {
         return workoutPreferences.getDefaultRestTimerSeconds()
     }
     
     /**
      * Clears the default rest timer.
      */
     suspend fun clearDefaultRestTimer() {
         workoutPreferences.clearDefaultRestTimer()
         Log.d(TAG, "Cleared default rest timer")
     }
     
     // ==================== PRIVATE HELPERS ====================
     
     private suspend fun persistSession(session: WorkoutSession) {
         try {
             val userId = getCurrentUserId()
             val sessionDataJson = json.encodeToString(session.toSerializable())
             
             val entity = ActiveSessionEntity(
                 id = session.id,
                 userId = userId,
                 templateId = session.templateId,
                 templateName = session.templateName,
                 startTime = session.startTime,
                 currentExerciseIndex = session.currentExerciseIndex,
                 sessionDataJson = sessionDataJson,
                 isPaused = session.isPaused,
                 totalPausedTimeMs = session.totalPausedTimeMs,
                 lastPauseTime = session.lastPauseTime
             )
             activeSessionDao.insertSession(entity)
         } catch (e: Exception) {
             Log.e(TAG, "Failed to persist session to Room: ${e.message}", e)
         }
     }
     
     private fun getCurrentUserId(): String {
         return supabaseClient.auth.currentUserOrNull()?.id ?: WorkoutConstants.LOCAL_USER_ID
     }
 }
 
 // ==================== SERIALIZABLE DTOs ====================
 
 @Serializable
 internal data class SerializableSession(
     val id: String,
     val templateId: String,
     val templateName: String,
     val startTime: Long,
     val exerciseLogs: List<SerializableExerciseLog>,
     val currentExerciseIndex: Int = 0,
     val isPaused: Boolean = false,
     val totalPausedTimeMs: Long = 0,
     val lastPauseTime: Long? = null
 ) {
     fun toDomain(): WorkoutSession = WorkoutSession(
         id = id,
         templateId = templateId,
         templateName = templateName,
         startTime = startTime,
         exerciseLogs = exerciseLogs.map { it.toDomain() },
         currentExerciseIndex = currentExerciseIndex,
         isPaused = isPaused,
         totalPausedTimeMs = totalPausedTimeMs,
         lastPauseTime = lastPauseTime
     )
 }
 
 @Serializable
 internal data class SerializableExerciseLog(
     val exercise: SerializableExercise,
     val sets: List<SerializableWorkoutSet>,
     val restSeconds: Int = WorkoutConstants.DEFAULT_REST_SECONDS
 ) {
     fun toDomain(): ExerciseLog = ExerciseLog(
         exercise = exercise.toDomain(),
         sets = sets.map { it.toDomain() },
         restSeconds = restSeconds
     )
 }
 
 @Serializable
 internal data class SerializableExercise(
     val id: String,
     val name: String,
     val muscleCategory: String,
     val description: String,
     val imageUrl: String? = null
 ) {
     fun toDomain(): Exercise = Exercise(
         id = id,
         name = name,
         muscleCategory = try { 
             MuscleCategory.valueOf(muscleCategory) 
         } catch (e: Exception) { 
             MuscleCategory.CHEST 
         },
         description = description,
         imageUrl = imageUrl
     )
 }
 
 @Serializable
 internal data class SerializableWorkoutSet(
     val setNumber: Int,
     val weight: Float = 0f,
     val reps: Int = 0,
     val isCompleted: Boolean = false
 ) {
     fun toDomain(): WorkoutSet = WorkoutSet(
         setNumber = setNumber,
         weight = weight,
         reps = reps,
         isCompleted = isCompleted
     )
 }
 
 // Extension functions for serialization
 internal fun WorkoutSession.toSerializable() = SerializableSession(
     id = id,
     templateId = templateId,
     templateName = templateName,
     startTime = startTime,
     exerciseLogs = exerciseLogs.map { it.toSerializable() },
     currentExerciseIndex = currentExerciseIndex,
     isPaused = isPaused,
     totalPausedTimeMs = totalPausedTimeMs,
     lastPauseTime = lastPauseTime
 )
 
 internal fun ExerciseLog.toSerializable() = SerializableExerciseLog(
     exercise = exercise.toSerializable(),
     sets = sets.map { it.toSerializable() },
     restSeconds = restSeconds
 )
 
 internal fun Exercise.toSerializable() = SerializableExercise(
     id = id,
     name = name,
     muscleCategory = muscleCategory.name,
     description = description,
     imageUrl = imageUrl
 )
 
 internal fun WorkoutSet.toSerializable() = SerializableWorkoutSet(
     setNumber = setNumber,
     weight = weight,
     reps = reps,
     isCompleted = isCompleted
 )

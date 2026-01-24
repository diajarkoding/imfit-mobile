 package com.diajarkoding.imfit.data.repository
 
 import android.util.Log
 import androidx.room.withTransaction
 import com.diajarkoding.imfit.core.constants.WorkoutConstants
 import com.diajarkoding.imfit.core.network.NetworkOperationException
import com.diajarkoding.imfit.core.network.RetryUtils
 import com.diajarkoding.imfit.data.local.dao.ExerciseDao
 import com.diajarkoding.imfit.data.local.dao.ExerciseLogDao
 import com.diajarkoding.imfit.data.local.dao.WorkoutLogDao
 import com.diajarkoding.imfit.data.local.dao.WorkoutSetDao
 import com.diajarkoding.imfit.data.local.database.IMFITDatabase
 import com.diajarkoding.imfit.data.local.entity.ExerciseLogEntity
 import com.diajarkoding.imfit.data.local.entity.WorkoutLogEntity
 import com.diajarkoding.imfit.data.local.entity.WorkoutSetEntity
 import com.diajarkoding.imfit.data.remote.dto.ExerciseLogDto
 import com.diajarkoding.imfit.data.remote.dto.WorkoutLogDto
 import com.diajarkoding.imfit.data.remote.dto.WorkoutSetDto
 import com.diajarkoding.imfit.data.remote.dto.toDomain
 import com.diajarkoding.imfit.domain.model.Exercise
 import com.diajarkoding.imfit.domain.model.ExerciseLog
 import com.diajarkoding.imfit.domain.model.MuscleCategory
 import com.diajarkoding.imfit.domain.model.WorkoutLog
 import com.diajarkoding.imfit.domain.model.WorkoutSession
 import com.diajarkoding.imfit.domain.model.WorkoutSet
 import io.github.jan.supabase.SupabaseClient
 import io.github.jan.supabase.auth.auth
 import io.github.jan.supabase.postgrest.postgrest
 import io.github.jan.supabase.postgrest.query.Columns
 import io.github.jan.supabase.postgrest.query.Order
 import kotlinx.serialization.SerialName
 import kotlinx.serialization.Serializable
 import java.time.OffsetDateTime
 import java.time.ZoneOffset
 import java.time.format.DateTimeFormatter
 import java.util.UUID
 import javax.inject.Inject
 import javax.inject.Singleton
 
 /**
  * Repository for managing workout logs (completed workouts).
  * Implements online-first strategy with transaction-safe local persistence.
  */
 @Singleton
 class WorkoutLogRepository @Inject constructor(
     private val supabaseClient: SupabaseClient,
     private val database: IMFITDatabase,
     private val workoutLogDao: WorkoutLogDao,
     private val exerciseLogDao: ExerciseLogDao,
     private val workoutSetDao: WorkoutSetDao,
     private val exerciseDao: ExerciseDao
 ) {
     
     companion object {
         private const val TAG = "WorkoutLogRepository"
         
         private val UUID_REGEX = Regex(
             "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
         )
         
         private fun isValidUUID(uuid: String): Boolean = UUID_REGEX.matches(uuid)
     }
     
     // ==================== READ OPERATIONS ====================
     
     /**
      * Gets all workout logs for a user.
      * Tries Supabase first, falls back to local cache on failure.
      */
     suspend fun getWorkoutLogs(userId: String): List<WorkoutLog> {
         require(userId.isNotBlank()) { "User ID cannot be blank" }
         
         return try {
             val remoteLogs = fetchWorkoutLogsFromSupabase(userId)
             cacheWorkoutLogs(remoteLogs, userId)
             Log.d(TAG, "Fetched ${remoteLogs.size} workout logs from Supabase")
             remoteLogs
         } catch (e: Exception) {
             Log.w(TAG, "Failed to fetch workout logs from Supabase, using cache: ${e.message}")
             getWorkoutLogsFromCache(userId)
         }
     }
     
     /**
      * Gets a single workout log by ID.
      */
     suspend fun getWorkoutLogById(logId: String): WorkoutLog? {
         require(logId.isNotBlank()) { "Log ID cannot be blank" }
         
         return try {
             val response = supabaseClient.postgrest
                 .from("workout_logs")
                 .select(Columns.raw("*, exercise_logs(*, exercises(*), workout_sets(*))")) {
                     filter { eq("id", logId) }
                 }
                 .decodeSingleOrNull<WorkoutLogDto>()
             
             response?.toDomain()
         } catch (e: Exception) {
             Log.w(TAG, "Failed to fetch workout log from Supabase, using cache: ${e.message}")
             val logEntity = workoutLogDao.getWorkoutLogById(logId) ?: return null
             mapWorkoutLogEntityToDomain(logEntity)
         }
     }
     
     /**
      * Gets the most recent workout log for a user.
      */
     suspend fun getLastWorkoutLog(userId: String): WorkoutLog? {
         require(userId.isNotBlank()) { "User ID cannot be blank" }
         
         return try {
             val response = supabaseClient.postgrest
                 .from("workout_logs")
                 .select(Columns.raw("*, exercise_logs(*, exercises(*), workout_sets(*))")) {
                     filter { eq("user_id", userId) }
                     order("date", Order.DESCENDING)
                 }
                 .decodeList<WorkoutLogDto>()
             
             response.filter { it.deletedAt == null }.firstOrNull()?.toDomain()
         } catch (e: Exception) {
             Log.w(TAG, "Failed to fetch last workout log from Supabase, using cache: ${e.message}")
             val logEntity = workoutLogDao.getLastWorkoutLog(userId) ?: return null
             mapWorkoutLogEntityToDomain(logEntity)
         }
     }
     
     /**
      * Gets the last exercise log for a specific exercise.
      */
     suspend fun getLastExerciseLog(exerciseId: String): ExerciseLog? {
         require(exerciseId.isNotBlank()) { "Exercise ID cannot be blank" }
         
         return try {
             val exerciseLogs = supabaseClient.postgrest.from("exercise_logs")
                 .select(Columns.raw("*, exercises(*), workout_sets(*)")) {
                     filter { eq("exercise_id", exerciseId) }
                     order("id", Order.DESCENDING)
                     limit(1)
                 }
                 .decodeList<ExerciseLogDto>()
             
             exerciseLogs.firstOrNull()?.toDomain()
         } catch (e: Exception) {
             Log.e(TAG, "Error getting last exercise log from Supabase: ${e.message}", e)
             getLastExerciseLogFromCache(exerciseId)
         }
     }
     
     /**
      * Gets a map of set_number to weight from the last completed workout for an exercise.
      */
     suspend fun getLastWeightsForExercise(exerciseId: String, userId: String): Map<Int, Float> {
         require(exerciseId.isNotBlank()) { "Exercise ID cannot be blank" }
         require(userId.isNotBlank()) { "User ID cannot be blank" }
         
         return try {
             val lastSets = workoutSetDao.getLastWorkoutSetsForExercise(exerciseId, userId)
             lastSets.associate { it.setNumber to it.weight }
         } catch (e: Exception) {
             Log.e(TAG, "Error getting last weights for exercise: ${e.message}", e)
             emptyMap()
         }
     }
     
     // ==================== WRITE OPERATIONS ====================
     
     /**
      * Finishes a workout session and saves the log.
      * Uses database transaction to ensure atomicity.
      * Uploads to Supabase after successful local save.
      * 
      * @param session The workout session to finish
      * @return The created workout log
      * @throws NetworkOperationException if user is not authenticated
      */
     suspend fun finishWorkout(session: WorkoutSession): WorkoutLog {
         val endTime = System.currentTimeMillis()
         
         val userId = supabaseClient.auth.currentUserOrNull()?.id
         if (userId == null || !isValidUUID(userId)) {
             Log.e(TAG, "Invalid or missing user ID, cannot finish workout")
             throw NetworkOperationException("User not authenticated. Please log in again.")
         }
         
         val workoutLogId = UUID.randomUUID().toString()
         val exerciseLogIds = session.exerciseLogs.map { UUID.randomUUID().toString() }
         
         // Save to local database with transaction
         saveWorkoutLogLocally(
             workoutLogId = workoutLogId,
             userId = userId,
             session = session,
             endTime = endTime,
             exerciseLogIds = exerciseLogIds
         )
         
         // Try to upload to Supabase (non-blocking)
         uploadWorkoutLogToSupabase(
             workoutLogId = workoutLogId,
             userId = userId,
             session = session,
             endTime = endTime,
             exerciseLogIds = exerciseLogIds
         )
         
         return WorkoutLog(
             id = workoutLogId,
             userId = userId,
             templateName = session.templateName,
             date = session.startTime,
             startTime = session.startTime,
             endTime = endTime,
             totalVolume = session.totalVolume,
             exerciseLogs = session.exerciseLogs
         )
     }
     
     /**
      * Saves workout log to local database within a transaction.
      * Ensures all-or-nothing persistence.
      */
     private suspend fun saveWorkoutLogLocally(
         workoutLogId: String,
         userId: String,
         session: WorkoutSession,
         endTime: Long,
         exerciseLogIds: List<String>
     ) {
         database.withTransaction {
             // Insert workout log
             val workoutLogEntity = WorkoutLogEntity(
                 id = workoutLogId,
                 userId = userId,
                 templateId = session.templateId,
                 templateName = session.templateName,
                 date = session.startTime,
                 startTime = session.startTime,
                 endTime = endTime,
                 totalVolume = session.totalVolume,
                 totalSets = session.totalCompletedSets,
                 totalReps = session.exerciseLogs.sumOf { log -> 
                     log.sets.filter { it.isCompleted }.sumOf { it.reps } 
                 }
             )
             workoutLogDao.insertWorkoutLog(workoutLogEntity)
             
             // Insert exercise logs and sets
             session.exerciseLogs.forEachIndexed { index, exerciseLog ->
                 val exerciseLogId = exerciseLogIds[index]
                 
                 val exerciseLogEntity = ExerciseLogEntity(
                     id = exerciseLogId,
                     workoutLogId = workoutLogId,
                     exerciseId = exerciseLog.exercise.id,
                     exerciseName = exerciseLog.exercise.name,
                     muscleCategory = exerciseLog.exercise.muscleCategory.name,
                     orderIndex = index,
                     totalVolume = exerciseLog.totalVolume,
                     totalSets = exerciseLog.sets.count { it.isCompleted },
                     totalReps = exerciseLog.sets.filter { it.isCompleted }.sumOf { it.reps }
                 )
                 exerciseLogDao.insertExerciseLog(exerciseLogEntity)
                 
                 exerciseLog.sets.forEach { set ->
                     val setEntity = WorkoutSetEntity(
                         id = UUID.randomUUID().toString(),
                         exerciseLogId = exerciseLogId,
                         workoutLogId = workoutLogId,
                         exerciseId = exerciseLog.exercise.id,
                         setNumber = set.setNumber,
                         weight = set.weight,
                         reps = set.reps,
                         isCompleted = set.isCompleted
                     )
                     workoutSetDao.insertWorkoutSet(setEntity)
                 }
             }
         }
         Log.d(TAG, "Saved workout log to local database (transaction): $workoutLogId")
     }
     
     /**
      * Uploads workout log to Supabase.
      * Non-blocking - local data is already saved.
      */
     private suspend fun uploadWorkoutLogToSupabase(
         workoutLogId: String,
         userId: String,
         session: WorkoutSession,
         endTime: Long,
         exerciseLogIds: List<String>
     ) {
         try {
             val startDateTime = OffsetDateTime.ofInstant(
                 java.time.Instant.ofEpochMilli(session.startTime),
                 ZoneOffset.UTC
             )
             val endDateTime = OffsetDateTime.ofInstant(
                 java.time.Instant.ofEpochMilli(endTime),
                 ZoneOffset.UTC
             )
             
             val workoutLogDto = CreateWorkoutLogDto(
                 id = workoutLogId,
                 userId = userId,
                 templateId = session.templateId,
                 templateName = session.templateName,
                 date = startDateTime.toLocalDate().toString(),
                 startTime = startDateTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                 endTime = endDateTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                 totalVolume = session.totalVolume.toDouble(),
                 totalSets = session.totalCompletedSets,
                 totalReps = session.exerciseLogs.sumOf { log -> 
                     log.sets.filter { it.isCompleted }.sumOf { it.reps } 
                 }
             )
             
             supabaseClient.postgrest.from("workout_logs").insert(workoutLogDto)
             
             session.exerciseLogs.forEachIndexed { index, exerciseLog ->
                 val exerciseLogId = exerciseLogIds[index]
                 val exerciseLogDto = CreateExerciseLogDto(
                     id = exerciseLogId,
                     workoutLogId = workoutLogId,
                     exerciseId = exerciseLog.exercise.id,
                     exerciseName = exerciseLog.exercise.name,
                     muscleCategory = exerciseLog.exercise.muscleCategory.name,
                     orderIndex = index,
                     totalVolume = exerciseLog.totalVolume.toDouble()
                 )
                 
                 supabaseClient.postgrest.from("exercise_logs").insert(exerciseLogDto)
                 
                 exerciseLog.sets.forEach { set ->
                     val setDto = WorkoutSetDto(
                         exerciseLogId = exerciseLogId,
                         setNumber = set.setNumber,
                         weight = set.weight.toDouble(),
                         reps = set.reps,
                         isCompleted = set.isCompleted
                     )
                     supabaseClient.postgrest.from("workout_sets").insert(setDto)
                 }
             }
             Log.d(TAG, "Uploaded workout log to Supabase: $workoutLogId")
         } catch (e: Exception) {
             Log.w(TAG, "Failed to upload workout to Supabase (saved locally): ${e.message}")
             // Data is already saved locally, so we don't throw
         }
     }
     
     // ==================== PRIVATE HELPERS ====================
     
     private suspend fun fetchWorkoutLogsFromSupabase(userId: String): List<WorkoutLog> {
         val response = supabaseClient.postgrest
             .from("workout_logs")
             .select(Columns.raw("*, exercise_logs(*, exercises(*), workout_sets(*))")) {
                 filter { eq("user_id", userId) }
                 order("date", Order.DESCENDING)
             }
             .decodeList<WorkoutLogDto>()
         
         return response.filter { it.deletedAt == null }.map { it.toDomain() }
     }
     
     private suspend fun cacheWorkoutLogs(logs: List<WorkoutLog>, userId: String) {
         logs.forEach { log ->
             val logEntity = WorkoutLogEntity(
                 id = log.id,
                 userId = log.userId,
                 templateId = null,
                 templateName = log.templateName,
                 date = log.date,
                 startTime = log.startTime,
                 endTime = log.endTime,
                 totalVolume = log.totalVolume,
                 totalSets = log.exerciseLogs.sumOf { it.sets.count { s -> s.isCompleted } },
                 totalReps = log.exerciseLogs.sumOf { el -> 
                     el.sets.filter { s -> s.isCompleted }.sumOf { s -> s.reps } 
                 }
             )
             workoutLogDao.insertWorkoutLog(logEntity)
             
             // Delete and re-insert exercise logs and sets for this log
             workoutSetDao.deleteSetsByWorkoutLog(log.id)
             exerciseLogDao.deleteExerciseLogsByWorkout(log.id)
             
             log.exerciseLogs.forEachIndexed { index, exerciseLog ->
                 val exerciseLogId = UUID.randomUUID().toString()
                 val exerciseLogEntity = ExerciseLogEntity(
                     id = exerciseLogId,
                     workoutLogId = log.id,
                     exerciseId = exerciseLog.exercise.id,
                     exerciseName = exerciseLog.exercise.name,
                     muscleCategory = exerciseLog.exercise.muscleCategory.name,
                     orderIndex = index,
                     totalVolume = exerciseLog.totalVolume,
                     totalSets = exerciseLog.sets.count { it.isCompleted },
                     totalReps = exerciseLog.sets.filter { it.isCompleted }.sumOf { it.reps }
                 )
                 exerciseLogDao.insertExerciseLog(exerciseLogEntity)
                 
                 exerciseLog.sets.forEach { set ->
                     val setEntity = WorkoutSetEntity(
                         id = UUID.randomUUID().toString(),
                         exerciseLogId = exerciseLogId,
                         workoutLogId = log.id,
                         exerciseId = exerciseLog.exercise.id,
                         setNumber = set.setNumber,
                         weight = set.weight,
                         reps = set.reps,
                         isCompleted = set.isCompleted
                     )
                     workoutSetDao.insertWorkoutSet(setEntity)
                 }
             }
         }
     }
     
     private suspend fun getWorkoutLogsFromCache(userId: String): List<WorkoutLog> {
         val localLogs = workoutLogDao.getWorkoutLogsByUserList(userId)
         return localLogs.map { logEntity -> mapWorkoutLogEntityToDomain(logEntity) }
     }
     
     private suspend fun getLastExerciseLogFromCache(exerciseId: String): ExerciseLog? {
         try {
             val logEntity = exerciseLogDao.getLastExerciseLog(exerciseId) ?: return null
             val exerciseEntity = exerciseDao.getExerciseById(exerciseId) ?: return null
             val setEntities = workoutSetDao.getSetsForExercise(logEntity.workoutLogId, exerciseId)
             
             val muscleCategory = MuscleCategory.entries.getOrNull(
                 exerciseEntity.muscleCategoryId - WorkoutConstants.MUSCLE_CATEGORY_ID_OFFSET
             ) ?: MuscleCategory.CHEST
             
             val exercise = Exercise(
                 id = exerciseEntity.id,
                 name = exerciseEntity.name,
                 muscleCategory = muscleCategory,
                 description = exerciseEntity.description,
                 imageUrl = exerciseEntity.imageUrl
             )
             
             val sets = setEntities.map { entity ->
                 WorkoutSet(
                     setNumber = entity.setNumber,
                     weight = entity.weight,
                     reps = entity.reps,
                     isCompleted = entity.isCompleted
                 )
             }
             
             return ExerciseLog(
                 exercise = exercise,
                 sets = sets,
                 restSeconds = WorkoutConstants.DEFAULT_REST_SECONDS
             )
         } catch (e: Exception) {
             Log.e(TAG, "Error getting last exercise log from local: ${e.message}", e)
             return null
         }
     }
     
     private suspend fun mapWorkoutLogEntityToDomain(
         logEntity: WorkoutLogEntity
     ): WorkoutLog {
         val exerciseLogEntities = exerciseLogDao.getExerciseLogsByWorkoutLogId(logEntity.id)
         
         val exerciseLogs = exerciseLogEntities.map { exerciseLogEntity ->
             val exerciseEntity = exerciseDao.getExerciseById(exerciseLogEntity.exerciseId)
             val setEntities = workoutSetDao.getSetsForExercise(
                 logEntity.id, 
                 exerciseLogEntity.exerciseId
             )
             
             val muscleCategory = exerciseEntity?.let {
                 MuscleCategory.entries.getOrNull(
                     it.muscleCategoryId - WorkoutConstants.MUSCLE_CATEGORY_ID_OFFSET
                 )
             } ?: MuscleCategory.CHEST
             
             val exercise = Exercise(
                 id = exerciseLogEntity.exerciseId,
                 name = exerciseLogEntity.exerciseName,
                 muscleCategory = muscleCategory,
                 description = exerciseEntity?.description ?: "",
                 imageUrl = exerciseEntity?.imageUrl
             )
             
             val sets = setEntities.map { setEntity ->
                 WorkoutSet(
                     setNumber = setEntity.setNumber,
                     weight = setEntity.weight,
                     reps = setEntity.reps,
                     isCompleted = setEntity.isCompleted
                 )
             }
             
             ExerciseLog(
                 exercise = exercise,
                 sets = sets,
                 restSeconds = WorkoutConstants.DEFAULT_REST_SECONDS
             )
         }
         
         return WorkoutLog(
             id = logEntity.id,
             userId = logEntity.userId,
             templateName = logEntity.templateName,
             date = logEntity.date,
             startTime = logEntity.startTime,
             endTime = logEntity.endTime,
             totalVolume = logEntity.totalVolume,
             exerciseLogs = exerciseLogs
         )
     }
 }
 
 @Serializable
 private data class CreateWorkoutLogDto(
     val id: String,
     @SerialName("user_id")
     val userId: String,
     @SerialName("template_id")
     val templateId: String?,
     @SerialName("template_name")
     val templateName: String,
     val date: String,
     @SerialName("start_time")
     val startTime: String,
     @SerialName("end_time")
     val endTime: String,
     @SerialName("total_volume")
     val totalVolume: Double,
     @SerialName("total_sets")
     val totalSets: Int,
     @SerialName("total_reps")
     val totalReps: Int
 )
 
 @Serializable
 private data class CreateExerciseLogDto(
     val id: String,
     @SerialName("workout_log_id")
     val workoutLogId: String,
     @SerialName("exercise_id")
     val exerciseId: String,
     @SerialName("exercise_name")
     val exerciseName: String,
     @SerialName("muscle_category")
     val muscleCategory: String,
     @SerialName("order_index")
     val orderIndex: Int,
     @SerialName("total_volume")
     val totalVolume: Double
 )

package com.diajarkoding.imfit.data.repository

import android.util.Log
import com.diajarkoding.imfit.data.local.FakeWorkoutDataSource
import com.diajarkoding.imfit.data.local.dao.ActiveSessionDao
import com.diajarkoding.imfit.data.local.entity.ActiveSessionEntity
import com.diajarkoding.imfit.data.remote.dto.ExerciseDto
import com.diajarkoding.imfit.data.remote.dto.ExerciseLogDto
import com.diajarkoding.imfit.data.remote.dto.TemplateExerciseDto
import com.diajarkoding.imfit.data.remote.dto.WorkoutLogDto
import com.diajarkoding.imfit.data.remote.dto.WorkoutSetDto
import com.diajarkoding.imfit.data.remote.dto.WorkoutTemplateDto
import com.diajarkoding.imfit.data.remote.dto.toDomain
import com.diajarkoding.imfit.domain.model.Exercise
import com.diajarkoding.imfit.domain.model.ExerciseLog
import com.diajarkoding.imfit.domain.model.MuscleCategory
import com.diajarkoding.imfit.domain.model.TemplateExercise
import com.diajarkoding.imfit.domain.model.WorkoutLog
import com.diajarkoding.imfit.domain.model.WorkoutSession
import com.diajarkoding.imfit.domain.model.WorkoutSet
import com.diajarkoding.imfit.domain.model.WorkoutTemplate
import com.diajarkoding.imfit.domain.repository.WorkoutRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkoutRepositoryImpl @Inject constructor(
    private val supabaseClient: SupabaseClient,
    private val exerciseLogDao: com.diajarkoding.imfit.data.local.dao.ExerciseLogDao,
    private val workoutSetDao: com.diajarkoding.imfit.data.local.dao.WorkoutSetDao,
    private val exerciseDao: com.diajarkoding.imfit.data.local.dao.ExerciseDao,
    private val workoutLogDao: com.diajarkoding.imfit.data.local.dao.WorkoutLogDao,
    private val workoutTemplateDao: com.diajarkoding.imfit.data.local.dao.WorkoutTemplateDao,
    private val templateExerciseDao: com.diajarkoding.imfit.data.local.dao.TemplateExerciseDao,
    private val activeSessionDao: ActiveSessionDao
) : WorkoutRepository {

    private var activeSession: WorkoutSession? = null
    
    private val json = Json { 
        ignoreUnknownKeys = true 
        encodeDefaults = true
    }

    override suspend fun getTemplates(userId: String): List<WorkoutTemplate> {
        return try {
            val localTemplates = workoutTemplateDao.getTemplatesByUserList(userId)

            localTemplates.map { templateEntity ->
                val templateExercises = templateExerciseDao.getExercisesForTemplateList(templateEntity.id)
                val exercises = templateExercises.mapNotNull { te ->
                    val exerciseEntity = exerciseDao.getExerciseById(te.exerciseId)
                    exerciseEntity?.let {
                        val muscleCategory = MuscleCategory.entries.getOrNull(it.muscleCategoryId - 1) 
                            ?: MuscleCategory.CHEST
                        TemplateExercise(
                            exercise = Exercise(
                                id = it.id,
                                name = it.name,
                                muscleCategory = muscleCategory,
                                description = it.description,
                                imageUrl = it.imageUrl
                            ),
                            sets = te.sets,
                            reps = te.reps,
                            restSeconds = te.restSeconds
                        )
                    }
                }
                
                WorkoutTemplate(
                    id = templateEntity.id,
                    userId = templateEntity.userId,
                    name = templateEntity.name,
                    exercises = exercises
                )
            }
        } catch (e: Exception) {
            Log.e("WorkoutRepository", "Error fetching templates from local: ${e.message}", e)
            emptyList()
        }
    }

    override suspend fun getTemplateById(templateId: String): WorkoutTemplate? {
        return try {
            val templateEntity = workoutTemplateDao.getTemplateById(templateId) ?: return null
            
            val templateExercises = templateExerciseDao.getExercisesForTemplateList(templateEntity.id)
            val exercises = templateExercises.mapNotNull { te ->
                val exerciseEntity = exerciseDao.getExerciseById(te.exerciseId)
                exerciseEntity?.let {
                    val muscleCategory = MuscleCategory.entries.getOrNull(it.muscleCategoryId - 1) 
                        ?: MuscleCategory.CHEST
                    TemplateExercise(
                        exercise = Exercise(
                            id = it.id,
                            name = it.name,
                            muscleCategory = muscleCategory,
                            description = it.description,
                            imageUrl = it.imageUrl
                        ),
                        sets = te.sets,
                        reps = te.reps,
                        restSeconds = te.restSeconds
                    )
                }
            }
            
            WorkoutTemplate(
                id = templateEntity.id,
                userId = templateEntity.userId,
                name = templateEntity.name,
                exercises = exercises
            )
        } catch (e: Exception) {
            Log.e("WorkoutRepository", "Error fetching template from local: ${e.message}", e)
            null
        }
    }

    override suspend fun createTemplate(userId: String, name: String, exercises: List<TemplateExercise>): WorkoutTemplate {
        val templateId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        // Save to Room database first
        val templateEntity = com.diajarkoding.imfit.data.local.entity.WorkoutTemplateEntity(
            id = templateId,
            userId = userId,
            name = name,
            isDeleted = false,
            createdAt = now,
            updatedAt = now,
            syncStatus = com.diajarkoding.imfit.data.local.sync.SyncStatus.PENDING_SYNC.name,
            pendingOperation = "CREATE"
        )
        workoutTemplateDao.insertTemplate(templateEntity)

        // Save template exercises to local database
        exercises.forEachIndexed { index, exercise ->
            val templateExerciseEntity = com.diajarkoding.imfit.data.local.entity.TemplateExerciseEntity(
                templateId = templateId,
                exerciseId = exercise.exercise.id,
                orderIndex = index,
                sets = exercise.sets,
                reps = exercise.reps,
                restSeconds = exercise.restSeconds,
                syncStatus = com.diajarkoding.imfit.data.local.sync.SyncStatus.PENDING_SYNC.name,
                pendingOperation = "CREATE"
            )
            templateExerciseDao.insertTemplateExercise(templateExerciseEntity)
        }
  
        Log.d("WorkoutRepository", "Created template locally: $templateId with ${exercises.size} exercises")

        // Try to sync to Supabase (non-blocking)
        if (isValidUUID(userId)) {
            try {
                val templateDto = CreateTemplateDto(
                    id = templateId,
                    userId = userId,
                    name = name
                )
                supabaseClient.postgrest.from("workout_templates").insert(templateDto)
                
                exercises.forEachIndexed { index, exercise ->
                    val exerciseDto = TemplateExerciseDto(
                        templateId = templateId,
                        exerciseId = exercise.exercise.id,
                        orderIndex = index,
                        sets = exercise.sets,
                        reps = exercise.reps,
                        restSeconds = exercise.restSeconds
                    )
                    supabaseClient.postgrest.from("template_exercises").insert(exerciseDto)
                }
                
                workoutTemplateDao.markAsSynced(templateId, com.diajarkoding.imfit.data.local.sync.SyncStatus.SYNCED.name)
                Log.d("WorkoutRepository", "Synced template to Supabase: $templateId")
            } catch (e: Exception) {
                Log.w("WorkoutRepository", "Failed to sync template to Supabase: ${e.message}")
            }
        }
        
        return WorkoutTemplate(
            id = templateId,
            userId = userId,
            name = name,
            exercises = exercises
        )
    }

    override suspend fun updateTemplate(templateId: String, name: String, exercises: List<TemplateExercise>): WorkoutTemplate? {
        val existingTemplate = workoutTemplateDao.getTemplateById(templateId) ?: return null
        val now = System.currentTimeMillis()

        // Update local database first
        val updatedEntity = existingTemplate.copy(
            name = name,
            updatedAt = now,
            syncStatus = com.diajarkoding.imfit.data.local.sync.SyncStatus.PENDING_SYNC.name,
            pendingOperation = "UPDATE"
        )
        workoutTemplateDao.updateTemplate(updatedEntity)

        // Delete existing template exercises and replace with new ones
        templateExerciseDao.deleteExercisesByTemplate(templateId)
        exercises.forEachIndexed { index, exercise ->
            val templateExerciseEntity = com.diajarkoding.imfit.data.local.entity.TemplateExerciseEntity(
                templateId = templateId,
                exerciseId = exercise.exercise.id,
                orderIndex = index,
                sets = exercise.sets,
                reps = exercise.reps,
                restSeconds = exercise.restSeconds,
                syncStatus = com.diajarkoding.imfit.data.local.sync.SyncStatus.PENDING_SYNC.name,
                pendingOperation = "CREATE"
            )
            templateExerciseDao.insertTemplateExercise(templateExerciseEntity)
        }
        
        Log.d("WorkoutRepository", "Updated template locally: $templateId with ${exercises.size} exercises")

        try {
            Log.d("WorkoutRepository", "Starting Supabase sync for template: $templateId")

            // Update template
            supabaseClient.postgrest.from("workout_templates")
                .update({
                    set("name", name)
                    set("updated_at", OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                }) {
                    filter { eq("id", templateId) }
                }

            // Delete existing template exercises
            supabaseClient.postgrest.from("template_exercises")
                .delete {
                    filter { eq("template_id", templateId) }
                }

            // Insert new template exercises one by one with error handling
            exercises.forEachIndexed { index, exercise ->
                try {
                    val exerciseDto = TemplateExerciseDto(
                        templateId = templateId,
                        exerciseId = exercise.exercise.id,
                        orderIndex = index,
                        sets = exercise.sets,
                        reps = exercise.reps,
                        restSeconds = exercise.restSeconds
                    )
                    supabaseClient.postgrest.from("template_exercises").insert(exerciseDto)
                } catch (insertError: Exception) {
                    Log.e("WorkoutRepository", "Failed to insert exercise ${exercise.exercise.id}: ${insertError.message}", insertError)
                    throw insertError
                }
            }

            workoutTemplateDao.markAsSynced(templateId, com.diajarkoding.imfit.data.local.sync.SyncStatus.SYNCED.name)
            Log.d("WorkoutRepository", "Synced template update to Supabase: $templateId with ${exercises.size} exercises")
        } catch (e: Exception) {
            Log.e("WorkoutRepository", "Failed to sync template update to Supabase: ${e.message}", e)
        }

        return getTemplateById(templateId)
    }

    override suspend fun updateTemplateExercises(templateId: String, exercises: List<TemplateExercise>): WorkoutTemplate? {
        val template = getTemplateById(templateId) ?: return null
        return updateTemplate(templateId, template.name, exercises)
    }

    override suspend fun updateTemplateExercise(templateId: String, exerciseId: String, sets: Int, reps: Int, restSeconds: Int): WorkoutTemplate? {
        // Update local database first
        val existingExercise = templateExerciseDao.getTemplateExercise(templateId, exerciseId)
        if (existingExercise != null) {
            val updatedExercise = existingExercise.copy(
                sets = sets,
                reps = reps,
                restSeconds = restSeconds,
                syncStatus = com.diajarkoding.imfit.data.local.sync.SyncStatus.PENDING_SYNC.name,
                pendingOperation = "UPDATE",
                updatedAt = System.currentTimeMillis()
            )
            templateExerciseDao.updateTemplateExercise(updatedExercise)
            Log.d("WorkoutRepository", "Updated exercise locally: $exerciseId in template $templateId")
        }

        // Try to sync to Supabase
        try {
            supabaseClient.postgrest.from("template_exercises")
                .update({
                    set("sets", sets)
                    set("reps", reps)
                    set("rest_seconds", restSeconds)
                }) {
                    filter {
                        eq("template_id", templateId)
                        eq("exercise_id", exerciseId)
                    }
                }
            Log.d("WorkoutRepository", "Synced exercise update to Supabase")
        } catch (e: Exception) {
            Log.e("WorkoutRepository", "Failed to sync exercise update: ${e.message}", e)
        }

        return getTemplateById(templateId)
    }

    override suspend fun deleteTemplate(templateId: String): Boolean {
        // Mark as deleted in local first
        val existingTemplate = workoutTemplateDao.getTemplateById(templateId)
        if (existingTemplate != null) {
            val updatedTemplate = existingTemplate.copy(
                isDeleted = true,
                syncStatus = com.diajarkoding.imfit.data.local.sync.SyncStatus.PENDING_SYNC.name,
                pendingOperation = "DELETE",
                updatedAt = System.currentTimeMillis()
            )
            workoutTemplateDao.updateTemplate(updatedTemplate)
            Log.d("WorkoutRepository", "Marked template as deleted locally: $templateId")
        }

        // Try to sync to Supabase
        return try {
            supabaseClient.postgrest.from("workout_templates")
                .update({
                    set("is_deleted", true)
                }) {
                    filter { eq("id", templateId) }
                }
            Log.d("WorkoutRepository", "Synced template deletion to Supabase")
            true
        } catch (e: Exception) {
            Log.e("WorkoutRepository", "Failed to sync template deletion: ${e.message}", e)
            true // Return true since local delete succeeded
        }
    }

    override suspend fun startWorkout(template: WorkoutTemplate): WorkoutSession {
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
                restSeconds = templateExercise.restSeconds
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

        // Persist session to database
        try {
            val userId = supabaseClient.auth.currentUserOrNull()?.id ?: "local_user"
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
            Log.d("WorkoutRepository", "Saved active session to Room: ${session.id}")
        } catch (e: Exception) {
            Log.e("WorkoutRepository", "Failed to save session to Room: ${e.message}", e)
        }
        
        return session
    }

    override suspend fun getActiveSession(): WorkoutSession? {
        // First check in-memory cache
        if (activeSession != null) {
            return activeSession
        }

        // Try to restore from database
        return try {
            val userId = supabaseClient.auth.currentUserOrNull()?.id ?: "local_user"
            val entity = activeSessionDao.getActiveSession(userId)
            
            if (entity != null) {
                val sessionData = json.decodeFromString<SerializableSession>(entity.sessionDataJson)
                activeSession = sessionData.toDomain()
                Log.d("WorkoutRepository", "Restored active session from Room: ${activeSession?.id}")
                activeSession
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("WorkoutRepository", "Failed to restore session from Room: ${e.message}", e)
            null
        }
    }

    override suspend fun updateActiveSession(session: WorkoutSession) {
        activeSession = session

        // Update in database
        try {
            val userId = supabaseClient.auth.currentUserOrNull()?.id ?: "local_user"
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
            activeSessionDao.updateSession(entity)
        } catch (e: Exception) {
            Log.e("WorkoutRepository", "Failed to update session in Room: ${e.message}", e)
        }
    }

    override suspend fun finishWorkout(): WorkoutLog? {
        val session = activeSession ?: return null
        val endTime = System.currentTimeMillis()
        
        // Store session ID before clearing
        val sessionId = session.id
        
        return try {
            val userId = supabaseClient.auth.currentUserOrNull()?.id
                ?: return FakeWorkoutDataSource.finishWorkout()

            // Use fake data if user ID is not valid UUID
            if (!isValidUUID(userId)) {
                Log.w("WorkoutRepository", "Invalid user ID format: $userId, using fake data")
                return FakeWorkoutDataSource.finishWorkout()
            }
            
            val workoutLogId = UUID.randomUUID().toString()
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
                totalReps = session.exerciseLogs.sumOf { log -> log.sets.filter { it.isCompleted }.sumOf { it.reps } }
            )
            
            supabaseClient.postgrest.from("workout_logs")
                .insert(workoutLogDto)
            
            session.exerciseLogs.forEachIndexed { index, exerciseLog ->
                val exerciseLogId = UUID.randomUUID().toString()
                val exerciseLogDto = CreateExerciseLogDto(
                    id = exerciseLogId,
                    workoutLogId = workoutLogId,
                    exerciseId = exerciseLog.exercise.id,
                    exerciseName = exerciseLog.exercise.name,
                    muscleCategory = exerciseLog.exercise.muscleCategory.name,
                    orderIndex = index,
                    totalVolume = exerciseLog.totalVolume.toDouble()
                )
                
                supabaseClient.postgrest.from("exercise_logs")
                    .insert(exerciseLogDto)
                
                exerciseLog.sets.forEach { set ->
                    val setDto = WorkoutSetDto(
                        exerciseLogId = exerciseLogId,
                        setNumber = set.setNumber,
                        weight = set.weight.toDouble(),
                        reps = set.reps,
                        isCompleted = set.isCompleted
                    )
                    supabaseClient.postgrest.from("workout_sets")
                        .insert(setDto)
                }
            }
            
            // Save to local database for offline-first and Last Known Weight feature
            val workoutLogEntity = com.diajarkoding.imfit.data.local.entity.WorkoutLogEntity(
                id = workoutLogId,
                userId = userId,
                templateId = session.templateId,
                templateName = session.templateName,
                date = session.startTime,
                startTime = session.startTime,
                endTime = endTime,
                totalVolume = session.totalVolume,
                totalSets = session.totalCompletedSets,
                totalReps = session.exerciseLogs.sumOf { log -> log.sets.filter { it.isCompleted }.sumOf { it.reps } },
                syncStatus = com.diajarkoding.imfit.data.local.sync.SyncStatus.SYNCED.name
            )
            workoutLogDao.insertWorkoutLog(workoutLogEntity)
            Log.d("WorkoutRepository", "Saved workout log to local database: $workoutLogId")

            // Insert exercise logs and sets to local database
            session.exerciseLogs.forEachIndexed { index, exerciseLog ->
                val exerciseLogId = java.util.UUID.randomUUID().toString()
                val exerciseLogEntity = com.diajarkoding.imfit.data.local.entity.ExerciseLogEntity(
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

                // Insert each set to local database
                exerciseLog.sets.forEach { set ->
                    val setEntity = com.diajarkoding.imfit.data.local.entity.WorkoutSetEntity(
                        id = java.util.UUID.randomUUID().toString(),
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
            Log.d("WorkoutRepository", "Saved all exercise logs and sets to local database")
            
            WorkoutLog(
                id = workoutLogId,
                userId = userId,
                templateName = session.templateName,
                date = session.startTime,
                startTime = session.startTime,
                endTime = endTime,
                totalVolume = session.totalVolume,
                exerciseLogs = session.exerciseLogs
            )
        } catch (e: Exception) {
            Log.e("WorkoutRepository", "Error finishing workout: ${e.message}", e)
            FakeWorkoutDataSource.finishWorkout()
        } finally {
            // Always delete active session from database, regardless of success/failure
            try {
                activeSessionDao.deleteSessionById(sessionId)
                Log.d("WorkoutRepository", "Deleted active session from database: $sessionId")
            } catch (e: Exception) {
                Log.e("WorkoutRepository", "Failed to delete session: ${e.message}", e)
            }
            activeSession = null
        }
    }

    override suspend fun cancelWorkout() {
        // Delete session from database
        try {
            val userId = supabaseClient.auth.currentUserOrNull()?.id ?: "local_user"
            activeSessionDao.deleteSession(userId)
            Log.d("WorkoutRepository", "Cancelled and deleted active session from database")
        } catch (e: Exception) {
            Log.e("WorkoutRepository", "Failed to delete session from database: ${e.message}", e)
        }
        activeSession = null
    }

    override suspend fun updateSessionRestOverride(seconds: Int) {
        try {
            val userId = supabaseClient.auth.currentUserOrNull()?.id ?: "local_user"
            val entity = activeSessionDao.getActiveSession(userId) ?: return
            val updatedEntity = entity.copy(
                sessionRestOverride = seconds,
                updatedAt = System.currentTimeMillis()
            )
            activeSessionDao.updateSession(updatedEntity)
            Log.d("WorkoutRepository", "Updated session rest override: $seconds")
        } catch (e: Exception) {
            Log.e("WorkoutRepository", "Failed to update session rest override: ${e.message}", e)
        }
    }
    
    override suspend fun getSessionRestOverride(): Int? {
        return try {
            val userId = supabaseClient.auth.currentUserOrNull()?.id ?: "local_user"
            activeSessionDao.getActiveSession(userId)?.sessionRestOverride
        } catch (e: Exception) {
            Log.e("WorkoutRepository", "Failed to get session rest override: ${e.message}", e)
            null
        }
    }

    override suspend fun getWorkoutLogs(userId: String): List<WorkoutLog> {
        return try {
            val localLogs = workoutLogDao.getWorkoutLogsByUserList(userId)

            localLogs.map { logEntity ->
                mapWorkoutLogEntityToDomain(logEntity)
            }
        } catch (e: Exception) {
            Log.e("WorkoutRepository", "Error fetching workout logs from local: ${e.message}", e)
            emptyList()
        }
    }

    override suspend fun getWorkoutLogById(logId: String): WorkoutLog? {
        return try {
            val logEntity = workoutLogDao.getWorkoutLogById(logId) ?: return null
            mapWorkoutLogEntityToDomain(logEntity)
        } catch (e: Exception) {
            Log.e("WorkoutRepository", "Error fetching workout log from local: ${e.message}", e)
            null
        }
    }

    override suspend fun getLastWorkoutLog(userId: String): WorkoutLog? {
        return try {
            val logEntity = workoutLogDao.getLastWorkoutLog(userId) ?: return null
            mapWorkoutLogEntityToDomain(logEntity)
        } catch (e: Exception) {
            Log.e("WorkoutRepository", "Error fetching last workout log from local: ${e.message}", e)
            null
        }
    }

    /**
     * Helper function to map WorkoutLogEntity to WorkoutLog domain model.
     * Fetches associated exercise logs and sets from local Room database.
     */
    private suspend fun mapWorkoutLogEntityToDomain(logEntity: com.diajarkoding.imfit.data.local.entity.WorkoutLogEntity): WorkoutLog {
        val exerciseLogEntities = exerciseLogDao.getExerciseLogsByWorkoutLogId(logEntity.id)
        
        val exerciseLogs = exerciseLogEntities.map { exerciseLogEntity ->
            val exerciseEntity = exerciseDao.getExerciseById(exerciseLogEntity.exerciseId)
            val setEntities = workoutSetDao.getSetsForExercise(logEntity.id, exerciseLogEntity.exerciseId)
            
            val muscleCategory = exerciseEntity?.let {
                MuscleCategory.entries.getOrNull(it.muscleCategoryId - 1)
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
                restSeconds = 60
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

    override suspend fun getLastExerciseLog(exerciseId: String): ExerciseLog? {
        return try {
            // Query Supabase for the last exercise log with this exercise ID
            val exerciseLogs = supabaseClient.postgrest.from("exercise_logs")
                .select(Columns.raw("*, exercises(*), workout_sets(*)")) {
                    filter { eq("exercise_id", exerciseId) }
                    order("id", io.github.jan.supabase.postgrest.query.Order.DESCENDING)
                    limit(1)
                }
                .decodeList<ExerciseLogDto>()
            
            val logDto = exerciseLogs.firstOrNull() ?: return null
            logDto.toDomain()
        } catch (e: Exception) {
            Log.e("WorkoutRepository", "Error getting last exercise log from Supabase: ${e.message}", e)
            // Fallback to local database
            try {
                val logEntity = exerciseLogDao.getLastExerciseLog(exerciseId) ?: return null
                val exerciseEntity = exerciseDao.getExerciseById(exerciseId) ?: return null
                val setEntities = workoutSetDao.getSetsForExercise(logEntity.workoutLogId, exerciseId)

                val muscleCategory = com.diajarkoding.imfit.domain.model.MuscleCategory.entries.getOrNull(exerciseEntity.muscleCategoryId - 1)
                    ?: com.diajarkoding.imfit.domain.model.MuscleCategory.CHEST

                val exercise = com.diajarkoding.imfit.domain.model.Exercise(
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

                ExerciseLog(
                    exercise = exercise,
                    sets = sets,
                    restSeconds = 60
                )
            } catch (localError: Exception) {
                Log.e("WorkoutRepository", "Error getting last exercise log from local: ${localError.message}", localError)
                null
            }
        }
    }

    /**
     * Gets a map of set_number to weight from the last completed workout for an exercise.
     * Uses local Room database for efficient per-set weight lookup.
     */
    override suspend fun getLastWeightsForExercise(exerciseId: String, userId: String): Map<Int, Float> {
        return try {
            val lastSets = workoutSetDao.getLastWorkoutSetsForExercise(exerciseId, userId)
            lastSets.associate { it.setNumber to it.weight }
        } catch (e: Exception) {
            Log.e("WorkoutRepository", "Error getting last weights for exercise: ${e.message}", e)
            emptyMap()
        }
    }

    companion object {
        /**
         * Validates if a string is a valid UUID format
         */
        private fun isValidUUID(uuid: String): Boolean {
            return try {
                // UUID regex pattern for validation
                val uuidRegex = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
                uuidRegex.matches(uuid)
            } catch (e: Exception) {
                false
            }
        }
    }
}

@Serializable
private data class CreateTemplateDto(
    val id: String,
    @SerialName("user_id")
    val userId: String,
    val name: String
)

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

// ======= SERIALIZABLE SESSION DTOs FOR PERSISTENCE =======

@Serializable
private data class SerializableSession(
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
private data class SerializableExerciseLog(
    val exercise: SerializableExercise,
    val sets: List<SerializableWorkoutSet>,
    val restSeconds: Int = 60
) {
    fun toDomain(): ExerciseLog = ExerciseLog(
        exercise = exercise.toDomain(),
        sets = sets.map { it.toDomain() },
        restSeconds = restSeconds
    )
}

@Serializable
private data class SerializableExercise(
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
private data class SerializableWorkoutSet(
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

private fun WorkoutSession.toSerializable() = SerializableSession(
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

private fun ExerciseLog.toSerializable() = SerializableExerciseLog(
    exercise = exercise.toSerializable(),
    sets = sets.map { it.toSerializable() },
    restSeconds = restSeconds
)

private fun Exercise.toSerializable() = SerializableExercise(
    id = id,
    name = name,
    muscleCategory = muscleCategory.name,
    description = description,
    imageUrl = imageUrl
)

private fun WorkoutSet.toSerializable() = SerializableWorkoutSet(
    setNumber = setNumber,
    weight = weight,
    reps = reps,
    isCompleted = isCompleted
)

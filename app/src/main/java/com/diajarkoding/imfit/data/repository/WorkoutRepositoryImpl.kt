package com.diajarkoding.imfit.data.repository

import android.util.Log
import com.diajarkoding.imfit.core.data.WorkoutPreferences
import com.diajarkoding.imfit.core.network.NetworkMonitor
import com.diajarkoding.imfit.core.network.NetworkOperationException
import com.diajarkoding.imfit.core.network.NoNetworkException
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
    private val activeSessionDao: ActiveSessionDao,
    private val workoutPreferences: WorkoutPreferences,
    private val networkMonitor: NetworkMonitor
) : WorkoutRepository {

    private var activeSession: WorkoutSession? = null
    
    private val json = Json { 
        ignoreUnknownKeys = true 
        encodeDefaults = true
    }

    // ==================== TEMPLATES (Online-First) ====================

    override suspend fun getTemplates(userId: String): List<WorkoutTemplate> {
        // Online-first: Try to fetch from Supabase first
        return try {
            val remoteTemplates = fetchTemplatesFromSupabase(userId)
            
            // Cache to local database
            cacheTemplates(remoteTemplates, userId)
            
            Log.d(TAG, "Fetched ${remoteTemplates.size} templates from Supabase")
            remoteTemplates
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch templates from Supabase, using cache: ${e.message}")
            // Fallback to local cache
            getTemplatesFromCache(userId)
        }
    }

    private suspend fun fetchTemplatesFromSupabase(userId: String): List<WorkoutTemplate> {
        val response = supabaseClient.postgrest
            .from("workout_templates")
            .select(Columns.raw("*, template_exercises(*, exercises(*))")) {
                filter {
                    eq("user_id", userId)
                    eq("is_deleted", false)
                }
            }
            .decodeList<WorkoutTemplateDto>()
        
        return response.map { it.toDomain() }
    }

    private suspend fun cacheTemplates(templates: List<WorkoutTemplate>, userId: String) {
        // Use REPLACE strategy to avoid data loss
        // First, collect all template IDs we're about to cache
        val templateIds = templates.map { it.id }.toSet()
        
        // Delete template exercises for templates we're updating
        templateIds.forEach { templateId ->
            templateExerciseDao.deleteExercisesByTemplate(templateId)
        }
        
        // Insert/update templates and their exercises
        templates.forEach { template ->
            val templateEntity = com.diajarkoding.imfit.data.local.entity.WorkoutTemplateEntity(
                id = template.id,
                userId = template.userId,
                name = template.name,
                isDeleted = false,
                createdAt = template.createdAt,
                updatedAt = System.currentTimeMillis()
            )
            workoutTemplateDao.insertTemplate(templateEntity)
            
            template.exercises.forEachIndexed { index, exercise ->
                val exerciseEntity = com.diajarkoding.imfit.data.local.entity.TemplateExerciseEntity(
                    templateId = template.id,
                    exerciseId = exercise.exercise.id,
                    orderIndex = index,
                    sets = exercise.sets,
                    reps = exercise.reps,
                    restSeconds = exercise.restSeconds
                )
                templateExerciseDao.insertTemplateExercise(exerciseEntity)
            }
        }
        
        // Clean up templates that exist locally but not in remote (soft deleted remotely)
        val localTemplates = workoutTemplateDao.getTemplatesByUserList(userId)
        localTemplates.forEach { local ->
            if (local.id !in templateIds) {
                // Mark as deleted locally (don't hard delete to preserve history)
                workoutTemplateDao.deleteTemplate(local.id)
            }
        }
    }

    private suspend fun getTemplatesFromCache(userId: String): List<WorkoutTemplate> {
        val localTemplates = workoutTemplateDao.getTemplatesByUserList(userId)
        
        return localTemplates.map { templateEntity ->
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
    }

    override suspend fun getTemplateById(templateId: String): WorkoutTemplate? {
        // Try remote first, fallback to cache
        return try {
            val response = supabaseClient.postgrest
                .from("workout_templates")
                .select(Columns.raw("*, template_exercises(*, exercises(*))")) {
                    filter {
                        eq("id", templateId)
                        eq("is_deleted", false)
                    }
                }
                .decodeSingleOrNull<WorkoutTemplateDto>()
            
            val template = response?.toDomain()
            
            // Cache the template if found
            if (template != null) {
                cacheTemplate(template)
            }
            
            template
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch template from Supabase, using cache: ${e.message}")
            getTemplateByIdFromCache(templateId)
        }
    }

    private suspend fun cacheTemplate(template: WorkoutTemplate) {
        val templateEntity = com.diajarkoding.imfit.data.local.entity.WorkoutTemplateEntity(
            id = template.id,
            userId = template.userId,
            name = template.name,
            isDeleted = false,
            createdAt = template.createdAt,
            updatedAt = System.currentTimeMillis()
        )
        workoutTemplateDao.insertTemplate(templateEntity)
        
        // Clear existing exercises for this template and insert fresh
        templateExerciseDao.deleteExercisesByTemplate(template.id)
        
        template.exercises.forEachIndexed { index, exercise ->
            val exerciseEntity = com.diajarkoding.imfit.data.local.entity.TemplateExerciseEntity(
                templateId = template.id,
                exerciseId = exercise.exercise.id,
                orderIndex = index,
                sets = exercise.sets,
                reps = exercise.reps,
                restSeconds = exercise.restSeconds
            )
            templateExerciseDao.insertTemplateExercise(exerciseEntity)
        }
    }

    private suspend fun getTemplateByIdFromCache(templateId: String): WorkoutTemplate? {
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
        
        return WorkoutTemplate(
            id = templateEntity.id,
            userId = templateEntity.userId,
            name = templateEntity.name,
            exercises = exercises
        )
    }

    override suspend fun createTemplate(userId: String, name: String, exercises: List<TemplateExercise>): WorkoutTemplate {
        val templateId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        // Check network connectivity first
        if (!networkMonitor.isOnline) {
            throw NoNetworkException("No internet connection. Please check your network and try again.")
        }

        // Online-first: Create on Supabase first
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
            
            Log.d(TAG, "Created template on Supabase: $templateId")
            
            // Cache locally after successful remote creation
            val templateEntity = com.diajarkoding.imfit.data.local.entity.WorkoutTemplateEntity(
                id = templateId,
                userId = userId,
                name = name,
                isDeleted = false,
                createdAt = now,
                updatedAt = now
            )
            workoutTemplateDao.insertTemplate(templateEntity)

            exercises.forEachIndexed { index, exercise ->
                val templateExerciseEntity = com.diajarkoding.imfit.data.local.entity.TemplateExerciseEntity(
                    templateId = templateId,
                    exerciseId = exercise.exercise.id,
                    orderIndex = index,
                    sets = exercise.sets,
                    reps = exercise.reps,
                    restSeconds = exercise.restSeconds
                )
                templateExerciseDao.insertTemplateExercise(templateExerciseEntity)
            }
            
            Log.d(TAG, "Cached template locally: $templateId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create template on Supabase: ${e.message}", e)
            throw NetworkOperationException("Failed to create template. Please check your connection.", e)
        }
        
        return WorkoutTemplate(
            id = templateId,
            userId = userId,
            name = name,
            exercises = exercises
        )
    }

    override suspend fun updateTemplate(templateId: String, name: String, exercises: List<TemplateExercise>): WorkoutTemplate? {
        val now = System.currentTimeMillis()

        // Check network connectivity first
        if (!networkMonitor.isOnline) {
            throw NoNetworkException("No internet connection. Please check your network and try again.")
        }

        // Online-first: Update on Supabase first
        try {
            supabaseClient.postgrest.from("workout_templates")
                .update({
                    set("name", name)
                    set("updated_at", OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                }) {
                    filter { eq("id", templateId) }
                }

            supabaseClient.postgrest.from("template_exercises")
                .delete {
                    filter { eq("template_id", templateId) }
                }

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

            Log.d(TAG, "Updated template on Supabase: $templateId")
            
            // Cache locally after successful remote update
            val existingTemplate = workoutTemplateDao.getTemplateById(templateId)
            if (existingTemplate != null) {
                val updatedEntity = existingTemplate.copy(name = name, updatedAt = now)
                workoutTemplateDao.updateTemplate(updatedEntity)
            }

            templateExerciseDao.deleteExercisesByTemplate(templateId)
            exercises.forEachIndexed { index, exercise ->
                val templateExerciseEntity = com.diajarkoding.imfit.data.local.entity.TemplateExerciseEntity(
                    templateId = templateId,
                    exerciseId = exercise.exercise.id,
                    orderIndex = index,
                    sets = exercise.sets,
                    reps = exercise.reps,
                    restSeconds = exercise.restSeconds
                )
                templateExerciseDao.insertTemplateExercise(templateExerciseEntity)
            }
            
            Log.d(TAG, "Cached template update locally: $templateId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update template on Supabase: ${e.message}", e)
            throw NetworkOperationException("Failed to update template. Please check your connection.", e)
        }

        return getTemplateByIdFromCache(templateId)
    }

    override suspend fun updateTemplateExercises(templateId: String, exercises: List<TemplateExercise>): WorkoutTemplate? {
        val template = getTemplateByIdFromCache(templateId) ?: return null
        return updateTemplate(templateId, template.name, exercises)
    }

    override suspend fun updateTemplateExercise(templateId: String, exerciseId: String, sets: Int, reps: Int, restSeconds: Int): WorkoutTemplate? {
        // Check network connectivity first
        if (!networkMonitor.isOnline) {
            throw NoNetworkException("No internet connection. Please check your network and try again.")
        }

        // Online-first: Update on Supabase first
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
            Log.d(TAG, "Updated exercise on Supabase: $exerciseId")

            // Cache locally
            val existingExercise = templateExerciseDao.getTemplateExercise(templateId, exerciseId)
            if (existingExercise != null) {
                val updatedExercise = existingExercise.copy(
                    sets = sets,
                    reps = reps,
                    restSeconds = restSeconds,
                    updatedAt = System.currentTimeMillis()
                )
                templateExerciseDao.updateTemplateExercise(updatedExercise)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update exercise on Supabase: ${e.message}", e)
            throw NetworkOperationException("Failed to update exercise. Please check your connection.", e)
        }

        return getTemplateByIdFromCache(templateId)
    }

    override suspend fun deleteTemplate(templateId: String): Boolean {
        // Check network connectivity first
        if (!networkMonitor.isOnline) {
            throw NoNetworkException("No internet connection. Please check your network and try again.")
        }

        // Online-first: Delete on Supabase first
        try {
            supabaseClient.postgrest.from("workout_templates")
                .update({
                    set("is_deleted", true)
                }) {
                    filter { eq("id", templateId) }
                }
            Log.d(TAG, "Deleted template on Supabase: $templateId")

            // Cache locally
            val existingTemplate = workoutTemplateDao.getTemplateById(templateId)
            if (existingTemplate != null) {
                val updatedTemplate = existingTemplate.copy(
                    isDeleted = true,
                    updatedAt = System.currentTimeMillis()
                )
                workoutTemplateDao.updateTemplate(updatedTemplate)
            }
            
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete template on Supabase: ${e.message}", e)
            throw NetworkOperationException("Failed to delete template. Please check your connection.", e)
        }
    }

    // ==================== ACTIVE WORKOUT SESSION ====================

    override suspend fun startWorkout(template: WorkoutTemplate): WorkoutSession {
        // Get default rest timer from preferences
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
        
        val userId = supabaseClient.auth.currentUserOrNull()?.id
        if (userId == null || !isValidUUID(userId)) {
            Log.e(TAG, "Invalid or missing user ID, cannot finish workout")
            throw NetworkOperationException("User not authenticated. Please log in again.")
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

        // Save to local database FIRST (ensures data is never lost)
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
            totalReps = session.exerciseLogs.sumOf { log -> log.sets.filter { it.isCompleted }.sumOf { it.reps } }
        )
        workoutLogDao.insertWorkoutLog(workoutLogEntity)
        
        // Generate exercise log IDs upfront for consistency between local and remote
        val exerciseLogIds = session.exerciseLogs.map { UUID.randomUUID().toString() }
        
        session.exerciseLogs.forEachIndexed { index, exerciseLog ->
            val exerciseLogId = exerciseLogIds[index]
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

            exerciseLog.sets.forEach { set ->
                val setEntity = com.diajarkoding.imfit.data.local.entity.WorkoutSetEntity(
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
        Log.d(TAG, "Saved workout log to local database: $workoutLogId")
        
        // Now try to upload to Supabase
        try {
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
            // Data is already saved locally, so we don't throw - just log the warning
        }
        
        // Clean up active session
        try {
            activeSessionDao.deleteSessionById(sessionId)
            Log.d(TAG, "Deleted active session from database: $sessionId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete session: ${e.message}", e)
        }
        activeSession = null
        
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

    // ==================== WORKOUT LOGS (Online-First) ====================

    override suspend fun getWorkoutLogs(userId: String): List<WorkoutLog> {
        // Online-first: Try to fetch from Supabase first
        return try {
            val remoteLogs = fetchWorkoutLogsFromSupabase(userId)
            
            // Cache to local database
            cacheWorkoutLogs(remoteLogs, userId)
            
            Log.d(TAG, "Fetched ${remoteLogs.size} workout logs from Supabase")
            remoteLogs
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch workout logs from Supabase, using cache: ${e.message}")
            // Fallback to local cache
            getWorkoutLogsFromCache(userId)
        }
    }

    private suspend fun fetchWorkoutLogsFromSupabase(userId: String): List<WorkoutLog> {
        val response = supabaseClient.postgrest
            .from("workout_logs")
            .select(Columns.raw("*, exercise_logs(*, exercises(*), workout_sets(*))")) {
                filter {
                    eq("user_id", userId)
                }
                order("date", io.github.jan.supabase.postgrest.query.Order.DESCENDING)
            }
            .decodeList<WorkoutLogDto>()
        
        // Filter out deleted logs (deleted_at is not null) on client side
        return response.filter { it.deletedAt == null }.map { it.toDomain() }
    }

    private suspend fun cacheWorkoutLogs(logs: List<WorkoutLog>, userId: String) {
        // Note: We DON'T delete existing local workout logs/sets to preserve:
        // 1. Last Known Weight data for exercises
        // 2. Any locally saved workouts that haven't synced yet
        // Instead, we use REPLACE strategy which will update existing and add new
        
        logs.forEach { log ->
            val logEntity = com.diajarkoding.imfit.data.local.entity.WorkoutLogEntity(
                id = log.id,
                userId = log.userId,
                templateId = null,
                templateName = log.templateName,
                date = log.date,
                startTime = log.startTime,
                endTime = log.endTime,
                totalVolume = log.totalVolume,
                totalSets = log.exerciseLogs.sumOf { it.sets.count { s -> s.isCompleted } },
                totalReps = log.exerciseLogs.sumOf { el -> el.sets.filter { s -> s.isCompleted }.sumOf { s -> s.reps } }
            )
            workoutLogDao.insertWorkoutLog(logEntity)
            
            // Delete existing exercise logs and sets for this specific workout log
            // to avoid duplicates, then insert fresh data
            workoutSetDao.deleteSetsByWorkoutLog(log.id)
            exerciseLogDao.deleteExerciseLogsByWorkout(log.id)
            
            log.exerciseLogs.forEachIndexed { index, exerciseLog ->
                val exerciseLogId = UUID.randomUUID().toString()
                val exerciseLogEntity = com.diajarkoding.imfit.data.local.entity.ExerciseLogEntity(
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
                    val setEntity = com.diajarkoding.imfit.data.local.entity.WorkoutSetEntity(
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

    override suspend fun getWorkoutLogById(logId: String): WorkoutLog? {
        // Try remote first, fallback to cache
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

    override suspend fun getLastWorkoutLog(userId: String): WorkoutLog? {
        // Try remote first, fallback to cache
        return try {
            val response = supabaseClient.postgrest
                .from("workout_logs")
                .select(Columns.raw("*, exercise_logs(*, exercises(*), workout_sets(*))")) {
                    filter {
                        eq("user_id", userId)
                    }
                    order("date", io.github.jan.supabase.postgrest.query.Order.DESCENDING)
                }
                .decodeList<WorkoutLogDto>()
            
            // Filter out deleted logs and get the first one
            response.filter { it.deletedAt == null }.firstOrNull()?.toDomain()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch last workout log from Supabase, using cache: ${e.message}")
            val logEntity = workoutLogDao.getLastWorkoutLog(userId) ?: return null
            mapWorkoutLogEntityToDomain(logEntity)
        }
    }

    // ==================== REST TIMER PREFERENCES ====================

    override suspend fun setDefaultRestTimer(seconds: Int) {
        workoutPreferences.setDefaultRestTimerSeconds(seconds)
        Log.d(TAG, "Saved default rest timer: $seconds seconds")
    }

    override suspend fun getDefaultRestTimer(): Int? {
        return workoutPreferences.getDefaultRestTimerSeconds()
    }

    override suspend fun clearDefaultRestTimer() {
        workoutPreferences.clearDefaultRestTimer()
        Log.d(TAG, "Cleared default rest timer")
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
            Log.e(TAG, "Error getting last weights for exercise: ${e.message}", e)
            emptyMap()
        }
    }

    companion object {
        private const val TAG = "WorkoutRepository"
        
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

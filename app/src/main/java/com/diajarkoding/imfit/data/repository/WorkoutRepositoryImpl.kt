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
    private val activeSessionDao: ActiveSessionDao
) : WorkoutRepository {

    private var activeSession: WorkoutSession? = null
    
    private val json = Json { 
        ignoreUnknownKeys = true 
        encodeDefaults = true
    }

    override suspend fun getTemplates(userId: String): List<WorkoutTemplate> {
        // Validate user ID format - if it's not a valid UUID, use fake data
        if (!isValidUUID(userId)) {
            Log.w("WorkoutRepository", "Invalid user ID format: $userId, using fake data")
            return FakeWorkoutDataSource.getTemplates(userId)
        }

        return try {
            supabaseClient.postgrest.from("workout_templates")
                .select(Columns.raw("*, template_exercises(*, exercises(*))")) {
                    filter {
                        eq("user_id", userId)
                        eq("is_deleted", false)
                    }
                }
                .decodeList<WorkoutTemplateDto>()
                .map { it.toDomain() }
        } catch (e: Exception) {
            Log.e("WorkoutRepository", "Error fetching templates: ${e.message}", e)
            FakeWorkoutDataSource.getTemplates(userId)
        }
    }

    override suspend fun getTemplateById(templateId: String): WorkoutTemplate? {
        return try {
            supabaseClient.postgrest.from("workout_templates")
                .select(Columns.raw("*, template_exercises(*, exercises(*))")) {
                    filter {
                        eq("id", templateId)
                        eq("is_deleted", false)
                    }
                }
                .decodeSingleOrNull<WorkoutTemplateDto>()
                ?.toDomain()
        } catch (e: Exception) {
            Log.e("WorkoutRepository", "Error fetching template: ${e.message}", e)
            FakeWorkoutDataSource.getTemplateById(templateId)
        }
    }

    override suspend fun createTemplate(userId: String, name: String, exercises: List<TemplateExercise>): WorkoutTemplate {
        // Validate user ID format - if it's not a valid UUID, use fake data
        if (!isValidUUID(userId)) {
            Log.w("WorkoutRepository", "Invalid user ID format: $userId, using fake data")
            return FakeWorkoutDataSource.createTemplate(userId, name, exercises)
        }

        return try {
            val templateId = UUID.randomUUID().toString()

            val templateDto = CreateTemplateDto(
                id = templateId,
                userId = userId,
                name = name
            )

            supabaseClient.postgrest.from("workout_templates")
                .insert(templateDto)

            exercises.forEachIndexed { index, exercise ->
                val exerciseDto = TemplateExerciseDto(
                    templateId = templateId,
                    exerciseId = exercise.exercise.id,
                    orderIndex = index,
                    sets = exercise.sets,
                    reps = exercise.reps,
                    restSeconds = exercise.restSeconds
                )
                supabaseClient.postgrest.from("template_exercises")
                    .insert(exerciseDto)
            }

            getTemplateById(templateId) ?: WorkoutTemplate(
                id = templateId,
                userId = userId,
                name = name,
                exercises = exercises
            )
        } catch (e: Exception) {
            Log.e("WorkoutRepository", "Error creating template: ${e.message}", e)
            FakeWorkoutDataSource.createTemplate(userId, name, exercises)
        }
    }

    override suspend fun updateTemplate(templateId: String, name: String, exercises: List<TemplateExercise>): WorkoutTemplate? {
        return try {
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
                supabaseClient.postgrest.from("template_exercises")
                    .insert(exerciseDto)
            }
            
            getTemplateById(templateId)
        } catch (e: Exception) {
            Log.e("WorkoutRepository", "Error updating template: ${e.message}", e)
            FakeWorkoutDataSource.updateTemplate(templateId, name, exercises)
        }
    }

    override suspend fun updateTemplateExercises(templateId: String, exercises: List<TemplateExercise>): WorkoutTemplate? {
        val template = getTemplateById(templateId) ?: return null
        return updateTemplate(templateId, template.name, exercises)
    }

    override suspend fun updateTemplateExercise(templateId: String, exerciseId: String, sets: Int, reps: Int, restSeconds: Int): WorkoutTemplate? {
        return try {
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
            getTemplateById(templateId)
        } catch (e: Exception) {
            Log.e("WorkoutRepository", "Error updating template exercise: ${e.message}", e)
            FakeWorkoutDataSource.updateTemplateExercise(templateId, exerciseId, sets, reps, restSeconds)
        }
    }

    override suspend fun deleteTemplate(templateId: String): Boolean {
        return try {
            supabaseClient.postgrest.from("workout_templates")
                .update({
                    set("is_deleted", true)
                }) {
                    filter { eq("id", templateId) }
                }
            true
        } catch (e: Exception) {
            Log.e("WorkoutRepository", "Error deleting template: ${e.message}", e)
            FakeWorkoutDataSource.deleteTemplate(templateId)
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
        
        // Persist session to Room database
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
                sessionDataJson = sessionDataJson
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
        
        // Try to restore from Room database
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
        
        // Update in Room database
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
                sessionDataJson = sessionDataJson
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

            // Validate user ID format - if it's not a valid UUID, use fake data
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
            
            // ======= SAVE TO LOCAL ROOM DATABASE =======
            // This enables offline-first and Last Known Weight feature
            
            // Insert workout log to local Room
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
            Log.d("WorkoutRepository", "Saved workout log to local Room: $workoutLogId")
            
            // Insert exercise logs and sets to local Room
            session.exerciseLogs.forEachIndexed { index, exerciseLog ->
                val exerciseLogEntity = com.diajarkoding.imfit.data.local.entity.ExerciseLogEntity(
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
                
                // Insert each set to local Room
                exerciseLog.sets.forEach { set ->
                    val setEntity = com.diajarkoding.imfit.data.local.entity.WorkoutSetEntity(
                        id = java.util.UUID.randomUUID().toString(),
                        exerciseLogId = "${workoutLogId}_${exerciseLog.exercise.id}",
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
            Log.d("WorkoutRepository", "Saved all exercise logs and sets to local Room")
            
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
            // ALWAYS delete active session from Room, regardless of success/failure
            try {
                activeSessionDao.deleteSessionById(sessionId)
                Log.d("WorkoutRepository", "Deleted active session from Room: $sessionId")
            } catch (e: Exception) {
                Log.e("WorkoutRepository", "Failed to delete session: ${e.message}", e)
            }
            activeSession = null
        }
    }

    override suspend fun cancelWorkout() {
        // Delete session from Room
        try {
            val userId = supabaseClient.auth.currentUserOrNull()?.id ?: "local_user"
            activeSessionDao.deleteSession(userId)
            Log.d("WorkoutRepository", "Cancelled and deleted active session from Room")
        } catch (e: Exception) {
            Log.e("WorkoutRepository", "Failed to delete session from Room: ${e.message}", e)
        }
        activeSession = null
    }

    override suspend fun getWorkoutLogs(userId: String): List<WorkoutLog> {
        // Validate user ID format - if it's not a valid UUID, use fake data
        if (!isValidUUID(userId)) {
            Log.w("WorkoutRepository", "Invalid user ID format: $userId, using fake data")
            return FakeWorkoutDataSource.getWorkoutLogs(userId)
        }

        return try {
            supabaseClient.postgrest.from("workout_logs")
                .select(Columns.raw("*, exercise_logs(*, exercises(*), workout_sets(*))")) {
                    filter { eq("user_id", userId) }
                    order("date", io.github.jan.supabase.postgrest.query.Order.DESCENDING)
                }
                .decodeList<WorkoutLogDto>()
                .map { it.toDomain() }
        } catch (e: Exception) {
            Log.e("WorkoutRepository", "Error fetching workout logs: ${e.message}", e)
            FakeWorkoutDataSource.getWorkoutLogs(userId)
        }
    }

    override suspend fun getWorkoutLogById(logId: String): WorkoutLog? {
        return try {
            supabaseClient.postgrest.from("workout_logs")
                .select(Columns.raw("*, exercise_logs(*, exercises(*), workout_sets(*))")) {
                    filter { eq("id", logId) }
                }
                .decodeSingleOrNull<WorkoutLogDto>()
                ?.toDomain()
        } catch (e: Exception) {
            Log.e("WorkoutRepository", "Error fetching workout log: ${e.message}", e)
            FakeWorkoutDataSource.getWorkoutLogById(logId)
        }
    }

    override suspend fun getLastWorkoutLog(userId: String): WorkoutLog? {
        // Validate user ID format - if it's not a valid UUID, use fake data
        if (!isValidUUID(userId)) {
            Log.w("WorkoutRepository", "Invalid user ID format: $userId, using fake data")
            return FakeWorkoutDataSource.getLastWorkoutLog(userId)
        }

        return try {
            supabaseClient.postgrest.from("workout_logs")
                .select(Columns.raw("*, exercise_logs(*, exercises(*), workout_sets(*))")) {
                    filter { eq("user_id", userId) }
                    order("date", io.github.jan.supabase.postgrest.query.Order.DESCENDING)
                    limit(1)
                }
                .decodeSingleOrNull<WorkoutLogDto>()
                ?.toDomain()
        } catch (e: Exception) {
            Log.e("WorkoutRepository", "Error fetching last workout log: ${e.message}", e)
            FakeWorkoutDataSource.getLastWorkoutLog(userId)
        }
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
    val currentExerciseIndex: Int = 0
) {
    fun toDomain(): WorkoutSession = WorkoutSession(
        id = id,
        templateId = templateId,
        templateName = templateName,
        startTime = startTime,
        exerciseLogs = exerciseLogs.map { it.toDomain() },
        currentExerciseIndex = currentExerciseIndex
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
    currentExerciseIndex = currentExerciseIndex
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

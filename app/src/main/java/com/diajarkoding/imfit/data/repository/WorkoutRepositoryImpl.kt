package com.diajarkoding.imfit.data.repository

import android.util.Log
import androidx.room.withTransaction
import com.diajarkoding.imfit.data.local.dao.ActiveSessionDao
import com.diajarkoding.imfit.data.local.database.IMFITDatabase
import com.diajarkoding.imfit.data.local.entity.ActiveSessionEntity
import com.diajarkoding.imfit.data.local.entity.ExerciseLogEntity
import com.diajarkoding.imfit.data.local.entity.TemplateExerciseEntity
import com.diajarkoding.imfit.data.local.entity.WorkoutLogEntity
import com.diajarkoding.imfit.data.local.entity.WorkoutSetEntity
import com.diajarkoding.imfit.data.local.entity.WorkoutTemplateEntity
import com.diajarkoding.imfit.data.local.sync.PendingOperation
import com.diajarkoding.imfit.data.local.sync.SyncStatus
import com.diajarkoding.imfit.data.sync.SyncScheduler
import com.diajarkoding.imfit.domain.model.Exercise
import com.diajarkoding.imfit.domain.model.ExerciseLog
import com.diajarkoding.imfit.domain.model.MuscleCategory
import com.diajarkoding.imfit.domain.model.TemplateExercise
import com.diajarkoding.imfit.domain.model.WorkoutLog
import com.diajarkoding.imfit.domain.model.WorkoutSession
import com.diajarkoding.imfit.domain.model.WorkoutSet
import com.diajarkoding.imfit.domain.model.WorkoutTemplate
import com.diajarkoding.imfit.domain.repository.AuthRepository
import com.diajarkoding.imfit.domain.repository.WorkoutRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CancellationException
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkoutRepositoryImpl @Inject constructor(
    private val authRepository: AuthRepository,
    private val exerciseLogDao: com.diajarkoding.imfit.data.local.dao.ExerciseLogDao,
    private val workoutSetDao: com.diajarkoding.imfit.data.local.dao.WorkoutSetDao,
    private val exerciseDao: com.diajarkoding.imfit.data.local.dao.ExerciseDao,
    private val workoutLogDao: com.diajarkoding.imfit.data.local.dao.WorkoutLogDao,
    private val workoutTemplateDao: com.diajarkoding.imfit.data.local.dao.WorkoutTemplateDao,
    private val templateExerciseDao: com.diajarkoding.imfit.data.local.dao.TemplateExerciseDao,
    private val activeSessionDao: ActiveSessionDao,
    private val database: IMFITDatabase,
    private val syncScheduler: SyncScheduler
) : WorkoutRepository {

    private var activeSession: WorkoutSession? = null
    private var activeSessionUserId: String? = null
    
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
        requireDistinctExercises(exercises)
        val templateId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        val templateEntity = WorkoutTemplateEntity(
            id = templateId,
            userId = userId,
            name = name,
            isDeleted = false,
            createdAt = now,
            updatedAt = now,
            syncStatus = SyncStatus.PENDING_SYNC.name,
            pendingOperation = PendingOperation.CREATE.name
        )

        val exerciseEntities = exercises.mapIndexed { index, exercise ->
            TemplateExerciseEntity(
                templateId = templateId,
                exerciseId = exercise.exercise.id,
                orderIndex = index,
                sets = exercise.sets,
                reps = exercise.reps,
                restSeconds = exercise.restSeconds,
                syncStatus = SyncStatus.PENDING_SYNC.name,
                pendingOperation = PendingOperation.CREATE.name,
                createdAt = now,
                updatedAt = now
            )
        }

        database.withTransaction {
            workoutTemplateDao.insertTemplate(templateEntity)
            if (exerciseEntities.isNotEmpty()) {
                templateExerciseDao.insertTemplateExercises(exerciseEntities)
            }
        }

        syncScheduler.enqueue(userId)
        return WorkoutTemplate(
            id = templateId,
            userId = userId,
            name = name,
            exercises = exercises
        )
    }

    override suspend fun updateTemplate(templateId: String, name: String, exercises: List<TemplateExercise>): WorkoutTemplate? {
        requireDistinctExercises(exercises)
        val result = database.withTransaction {
            val existingTemplate = workoutTemplateDao.getTemplateById(templateId)
                ?: return@withTransaction null
            val now = System.currentTimeMillis()
            val operation = pendingUpdateOperation(existingTemplate)

            workoutTemplateDao.updateTemplate(
                existingTemplate.copy(
                    name = name,
                    updatedAt = now,
                    syncStatus = SyncStatus.PENDING_SYNC.name,
                    pendingOperation = operation
                )
            )
            templateExerciseDao.deleteExercisesByTemplate(templateId)
            val exerciseEntities = exercises.mapIndexed { index, exercise ->
                TemplateExerciseEntity(
                    templateId = templateId,
                    exerciseId = exercise.exercise.id,
                    orderIndex = index,
                    sets = exercise.sets,
                    reps = exercise.reps,
                    restSeconds = exercise.restSeconds,
                    syncStatus = SyncStatus.PENDING_SYNC.name,
                    pendingOperation = PendingOperation.CREATE.name,
                    createdAt = now,
                    updatedAt = now
                )
            }
            if (exerciseEntities.isNotEmpty()) {
                templateExerciseDao.insertTemplateExercises(exerciseEntities)
            }
            WorkoutTemplate(templateId, existingTemplate.userId, name, exercises)
        }

        if (result != null) syncScheduler.enqueue(result.userId)
        return result
    }

    override suspend fun updateTemplateExercises(templateId: String, exercises: List<TemplateExercise>): WorkoutTemplate? {
        val template = getTemplateById(templateId) ?: return null
        return updateTemplate(templateId, template.name, exercises)
    }

    override suspend fun updateTemplateExercise(templateId: String, exerciseId: String, sets: Int, reps: Int, restSeconds: Int): WorkoutTemplate? {
        val userId = database.withTransaction {
            val template = workoutTemplateDao.getTemplateById(templateId)
                ?: return@withTransaction null
            val existingExercise = templateExerciseDao.getTemplateExercise(templateId, exerciseId)
                ?: return@withTransaction null
            val now = System.currentTimeMillis()

            workoutTemplateDao.updateTemplate(
                template.copy(
                    updatedAt = now,
                    syncStatus = SyncStatus.PENDING_SYNC.name,
                    pendingOperation = pendingUpdateOperation(template)
                )
            )
            templateExerciseDao.updateTemplateExercise(existingExercise.copy(
                sets = sets,
                reps = reps,
                restSeconds = restSeconds,
                syncStatus = SyncStatus.PENDING_SYNC.name,
                pendingOperation = PendingOperation.UPDATE.name,
                updatedAt = now
            ))
            template.userId
        }

        if (userId == null) return null
        syncScheduler.enqueue(userId)
        return getTemplateById(templateId)
    }

    override suspend fun deleteTemplate(templateId: String): Boolean {
        val userId = database.withTransaction {
            val existing = workoutTemplateDao.getTemplateById(templateId)
                ?: return@withTransaction null
            workoutTemplateDao.updateTemplate(
                existing.copy(
                    isDeleted = true,
                    syncStatus = SyncStatus.PENDING_SYNC.name,
                    pendingOperation = PendingOperation.DELETE.name,
                    updatedAt = System.currentTimeMillis()
                )
            )
            existing.userId
        }

        if (userId == null) return false
        syncScheduler.enqueue(userId)
        return true
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
        
        val userId = authRepository.getCurrentUser()?.id ?: template.userId
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
        database.withTransaction {
            activeSessionDao.deleteSession(userId)
            activeSessionDao.insertSession(entity)
        }
        activeSession = session
        activeSessionUserId = userId
        return session
    }

    override suspend fun getActiveSession(): WorkoutSession? {
        val userId = authRepository.getCurrentUser()?.id ?: return null
        if (activeSession != null && activeSessionUserId == userId) {
            return activeSession
        }

        // Try to restore from database
        return try {
            val entity = activeSessionDao.getActiveSession(userId)
            
            if (entity != null) {
                val sessionData = json.decodeFromString<SerializableSession>(entity.sessionDataJson)
                activeSession = sessionData.toDomain()
                activeSessionUserId = userId
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
        val sessionDataJson = json.encodeToString(session.toSerializable())
        val userId = authRepository.getCurrentUser()?.id ?: return
        database.withTransaction {
            val existing = activeSessionDao.getSessionById(session.id)
            val entity = existing?.copy(
                templateId = session.templateId,
                templateName = session.templateName,
                startTime = session.startTime,
                currentExerciseIndex = session.currentExerciseIndex,
                sessionDataJson = sessionDataJson,
                isPaused = session.isPaused,
                totalPausedTimeMs = session.totalPausedTimeMs,
                lastPauseTime = session.lastPauseTime,
                updatedAt = System.currentTimeMillis()
            ) ?: ActiveSessionEntity(
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
        }
        activeSession = session
        activeSessionUserId = userId
    }

    override suspend fun finishWorkout(): WorkoutLog? {
        val session = activeSession ?: getActiveSession() ?: return null
        val endTime = System.currentTimeMillis()

        return try {
            val result = database.withTransaction {
                val persistedSession = activeSessionDao.getSessionById(session.id)
                    ?: return@withTransaction null
                val workoutLogId = session.id
                val now = endTime
                val totalReps = session.exerciseLogs.sumOf { log ->
                    log.sets.filter { it.isCompleted }.sumOf { it.reps }
                }

                val workoutLogEntity = WorkoutLogEntity(
                    id = workoutLogId,
                    userId = persistedSession.userId,
                    templateId = session.templateId,
                    templateName = session.templateName,
                    date = session.startTime,
                    startTime = session.startTime,
                    endTime = endTime,
                    totalVolume = session.totalVolume,
                    totalSets = session.totalCompletedSets,
                    totalReps = totalReps,
                    syncStatus = SyncStatus.PENDING_SYNC.name,
                    pendingOperation = PendingOperation.CREATE.name,
                    createdAt = now,
                    updatedAt = now
                )
                val exerciseEntities = session.exerciseLogs.mapIndexed { index, exerciseLog ->
                    ExerciseLogEntity(
                        id = stableUuid("exercise-log", session.id, index, exerciseLog.exercise.id),
                        workoutLogId = workoutLogId,
                        exerciseId = exerciseLog.exercise.id,
                        exerciseName = exerciseLog.exercise.name,
                        muscleCategory = exerciseLog.exercise.muscleCategory.name,
                        orderIndex = index,
                        totalVolume = exerciseLog.totalVolume,
                        totalSets = exerciseLog.sets.count { it.isCompleted },
                        totalReps = exerciseLog.sets.filter { it.isCompleted }.sumOf { it.reps },
                        syncStatus = SyncStatus.PENDING_SYNC.name,
                        pendingOperation = PendingOperation.CREATE.name,
                        createdAt = now,
                        updatedAt = now
                    )
                }
                val setEntities = session.exerciseLogs.flatMapIndexed { exerciseIndex, exerciseLog ->
                    val exerciseLogId = exerciseEntities[exerciseIndex].id
                    exerciseLog.sets.mapIndexed { setIndex, set ->
                        WorkoutSetEntity(
                            id = stableUuid("workout-set", exerciseLogId, setIndex, set.setNumber),
                            exerciseLogId = exerciseLogId,
                            workoutLogId = workoutLogId,
                            exerciseId = exerciseLog.exercise.id,
                            setNumber = set.setNumber,
                            weight = set.weight,
                            reps = set.reps,
                            isCompleted = set.isCompleted,
                            syncStatus = SyncStatus.PENDING_SYNC.name,
                            pendingOperation = PendingOperation.CREATE.name,
                            createdAt = now,
                            updatedAt = now
                        )
                    }
                }

                workoutLogDao.insertWorkoutLog(workoutLogEntity)
                if (exerciseEntities.isNotEmpty()) exerciseLogDao.insertExerciseLogs(exerciseEntities)
                if (setEntities.isNotEmpty()) workoutSetDao.insertWorkoutSets(setEntities)
                check(activeSessionDao.deleteSessionById(session.id) == 1)

                WorkoutLog(
                    id = workoutLogId,
                    userId = persistedSession.userId,
                    templateName = session.templateName,
                    date = session.startTime,
                    startTime = session.startTime,
                    endTime = endTime,
                    totalVolume = session.totalVolume,
                    exerciseLogs = session.exerciseLogs
                )
            }

            if (result != null) {
                activeSession = null
                activeSessionUserId = null
                syncScheduler.enqueue(result.userId)
            }
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("WorkoutRepository", "Failed to persist finished workout", e)
            null
        }
    }

    override suspend fun cancelWorkout() {
        val session = activeSession ?: getActiveSession()
        if (session != null) activeSessionDao.deleteSessionById(session.id)
        activeSession = null
        activeSessionUserId = null
    }

    override suspend fun updateSessionRestOverride(seconds: Int) {
        try {
            val userId = authRepository.getCurrentUser()?.id ?: return
            val entity = activeSession?.takeIf { activeSessionUserId == userId }
                ?.let { activeSessionDao.getSessionById(it.id) }
                ?: activeSessionDao.getActiveSession(userId)
                ?: return
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
            val userId = authRepository.getCurrentUser()?.id ?: return null
            activeSession?.takeIf { activeSessionUserId == userId }
                ?.let { activeSessionDao.getSessionById(it.id) }?.sessionRestOverride
                ?: activeSessionDao.getActiveSession(userId)?.sessionRestOverride
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
        val userId = authRepository.getCurrentUser()?.id ?: return null
        val logEntity = exerciseLogDao.getLastExerciseLog(exerciseId, userId) ?: return null
        val exerciseEntity = exerciseDao.getExerciseById(exerciseId) ?: return null
        val setEntities = workoutSetDao.getSetsForExercise(logEntity.workoutLogId, exerciseId)
        val category = MuscleCategory.entries.getOrNull(exerciseEntity.muscleCategoryId - 1)
            ?: MuscleCategory.CHEST
        return ExerciseLog(
            exercise = Exercise(
                id = exerciseEntity.id,
                name = exerciseEntity.name,
                muscleCategory = category,
                description = exerciseEntity.description,
                imageUrl = exerciseEntity.imageUrl
            ),
            sets = setEntities.map { entity ->
                WorkoutSet(entity.setNumber, entity.weight, entity.reps, entity.isCompleted)
            },
            restSeconds = 60
        )
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
        private fun stableUuid(vararg parts: Any): String = UUID.nameUUIDFromBytes(
            parts.joinToString("\u001F").toByteArray(StandardCharsets.UTF_8)
        ).toString()

        private fun requireDistinctExercises(exercises: List<TemplateExercise>) {
            require(exercises.map { it.exercise.id }.distinct().size == exercises.size) {
                "A workout template cannot contain duplicate exercises"
            }
        }

        private fun pendingUpdateOperation(template: WorkoutTemplateEntity): String =
            if (template.pendingOperation == PendingOperation.CREATE.name) {
                PendingOperation.CREATE.name
            } else {
                PendingOperation.UPDATE.name
            }
    }
}

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

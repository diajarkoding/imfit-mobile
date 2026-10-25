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
import com.diajarkoding.imfit.domain.model.ExerciseInputDefaults
import com.diajarkoding.imfit.domain.model.inputDefaults
import com.diajarkoding.imfit.domain.model.MuscleCategory
import com.diajarkoding.imfit.domain.model.TemplateExercise
import com.diajarkoding.imfit.domain.model.WorkoutLog
import com.diajarkoding.imfit.domain.model.WorkoutSession
import com.diajarkoding.imfit.domain.model.WorkoutSet
import com.diajarkoding.imfit.domain.model.WorkoutTemplate
import com.diajarkoding.imfit.domain.model.validateActiveSessionEdit
import com.diajarkoding.imfit.domain.repository.AuthRepository
import com.diajarkoding.imfit.domain.repository.WorkoutRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkoutLocalStore @Inject constructor(
    private val authRepository: AuthRepository,
    private val exerciseLogDao: com.diajarkoding.imfit.data.local.dao.ExerciseLogDao,
    private val workoutSetDao: com.diajarkoding.imfit.data.local.dao.WorkoutSetDao,
    private val exerciseDao: com.diajarkoding.imfit.data.local.dao.ExerciseDao,
    private val workoutLogDao: com.diajarkoding.imfit.data.local.dao.WorkoutLogDao,
    private val workoutTemplateDao: com.diajarkoding.imfit.data.local.dao.WorkoutTemplateDao,
    private val templateExerciseDao: com.diajarkoding.imfit.data.local.dao.TemplateExerciseDao,
    private val activeSessionDao: ActiveSessionDao,
    private val database: IMFITDatabase,
    private val syncScheduler: SyncScheduler,
    private val timerCleanup: com.diajarkoding.imfit.core.timer.TimerSessionCleanup? = null,
) : WorkoutRepository {

    private var activeSession: WorkoutSession? = null
    private var activeSessionUserId: String? = null
    private val sessionMutex = Mutex()
    private val lifecycleMutex = Mutex()
    private val terminalSessions = ConcurrentHashMap.newKeySet<String>()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val accountAccess = AccountDataAccess(authRepository)
    
    private val json = Json {
        ignoreUnknownKeys = true 
        encodeDefaults = true
    }

    override fun observeTemplates(userId: String): Flow<List<WorkoutTemplate>> =
        accountAccess.observe(userId, emptyList()) {
            database.invalidationTracker.createFlow("workout_templates", "template_exercises", "exercises")
                .map { database.withTransaction { readTemplatesLocal(userId) } }.distinctUntilChanged().flowOn(Dispatchers.IO)
        }

    override fun observeTemplate(templateId: String): Flow<WorkoutTemplate?> =
        accountAccess.observe(empty = null as WorkoutTemplate?) {
            database.invalidationTracker.createFlow("workout_templates", "template_exercises", "exercises")
                .map { database.withTransaction { readTemplateLocal(templateId) } }.distinctUntilChanged().flowOn(Dispatchers.IO)
        }

    override fun observeWorkoutLogs(userId: String): Flow<List<WorkoutLog>> =
        accountAccess.observe(userId, emptyList()) {
            database.invalidationTracker.createFlow("workout_logs", "exercise_logs", "workout_sets", "exercises")
                .map { database.withTransaction {
                    workoutLogDao.getWorkoutLogsByUserList(userId).map { mapWorkoutLogEntityToDomain(it) }
                } }.distinctUntilChanged().flowOn(Dispatchers.IO)
        }

    override fun observeLastWorkoutLog(userId: String): Flow<WorkoutLog?> =
        accountAccess.observe(userId, null as WorkoutLog?) {
            database.invalidationTracker.createFlow("workout_logs", "exercise_logs", "workout_sets", "exercises")
                .map { database.withTransaction {
                    workoutLogDao.getLastWorkoutLog(userId)?.let { mapWorkoutLogEntityToDomain(it) }
                } }.distinctUntilChanged().flowOn(Dispatchers.IO)
        }

    override fun observeWorkoutLog(logId: String): Flow<WorkoutLog?> =
        accountAccess.observe(empty = null as WorkoutLog?) { owner ->
            database.invalidationTracker.createFlow("workout_logs", "exercise_logs", "workout_sets", "exercises")
                .map { database.withTransaction {
                    workoutLogDao.getWorkoutLogById(logId)?.takeIf { it.userId == owner && it.deletedAt == null }?.let { mapWorkoutLogEntityToDomain(it) }
                } }.distinctUntilChanged().flowOn(Dispatchers.IO)
        }

    override suspend fun getTemplates(userId: String): List<WorkoutTemplate> {
        return accountAccess.read(userId, emptyList()) {
            readTemplatesLocal(userId)
        }
    }

    private suspend fun readTemplatesLocal(userId: String): List<WorkoutTemplate> {
        if (authRepository.getLocalUserId() != userId) return emptyList()
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
                    exercises = exercises,
                    createdAt = templateEntity.createdAt
                )
            }
        } catch (e: Exception) {
            Log.e("WorkoutRepository", "Error fetching templates from local: ${e.message}", e)
            emptyList()
        }
    }

    override suspend fun getTemplateById(templateId: String): WorkoutTemplate? {
        return accountAccess.read(empty = null as WorkoutTemplate?) { owner ->
            readTemplateLocal(templateId)
        }
    }

    private suspend fun readTemplateLocal(templateId: String): WorkoutTemplate? {
        return try {
            val templateEntity = workoutTemplateDao.getTemplateById(templateId) ?: return null
            if (templateEntity.userId != authRepository.getLocalUserId()) return null
            
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
                exercises = exercises,
                createdAt = templateEntity.createdAt
            )
        } catch (e: Exception) {
            Log.e("WorkoutRepository", "Error fetching template from local: ${e.message}", e)
            null
        }
    }

    override suspend fun createTemplate(userId: String, name: String, exercises: List<TemplateExercise>): WorkoutTemplate {
        check(authRepository.getLocalUserId() == userId) { "Authenticated owner required" }
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
            if (existingTemplate.userId != authRepository.getLocalUserId()) return@withTransaction null
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
            if (template.userId != authRepository.getLocalUserId()) return@withTransaction null
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
            if (existing.userId != authRepository.getLocalUserId()) return@withTransaction null
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

    override suspend fun startWorkout(template: WorkoutTemplate): WorkoutSession = lifecycleMutex.withLock {
        val userId = authRepository.getLocalUserId() ?: error("Authenticated owner required")
        check(template.userId == userId) { "Template belongs to another account" }
        timerCleanup?.stop("SESSION_REPLACED")
        val exerciseLogs = template.exercises.map { templateExercise ->
            val defaults = templateExercise.inputDefaults()
            val previous = getPrefillForExercise(templateExercise.id, userId, defaults)
            ExerciseLog(
                exercise = templateExercise.exercise,
                sets = (1..templateExercise.sets).map { setNumber ->
                    WorkoutSet(
                        setNumber = setNumber,
                        weight = previous[setNumber]?.first ?: 0f,
                        reps = previous[setNumber]?.second ?: templateExercise.reps,
                        isCompleted = false
                    )
                },
                restSeconds = templateExercise.restSeconds,
                inputDefaults = defaults
            )
        }
        
        val session = WorkoutSession(
            id = UUID.randomUUID().toString(),
            templateId = template.id,
            templateName = template.name,
            startTime = System.currentTimeMillis(),
            exerciseLogs = exerciseLogs
        )
        
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
        withContext(Dispatchers.IO) { sessionMutex.withLock { database.withTransaction {
            check(authRepository.getLocalUserId() == userId) { "Authenticated owner changed" }
            activeSessionDao.deleteSession(userId)
            activeSessionDao.insertSession(entity)
        } } }
        activeSession = session
        activeSessionUserId = userId
        session
    }

    override suspend fun getActiveSession(): WorkoutSession? {
        return try {
            getActiveSessionForTimer()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("WorkoutRepository", "Failed to restore session from Room: ${e.message}", e)
            null
        }
    }

    override suspend fun isActiveSessionForTimer(userId: String, workoutId: String): Boolean =
        withContext(Dispatchers.IO) {
            // Preserve redisgn's immediate cancellation guard while Room cleanup is pending.
            authRepository.getLocalUserId() == userId && workoutId !in terminalSessions && activeSessionDao.getActiveSessionId(userId) == workoutId &&
                workoutId !in terminalSessions
        }

    override suspend fun getActiveSessionForTimer(): WorkoutSession? =
        withContext(Dispatchers.IO) {
            val userId = authRepository.getLocalUserId() ?: return@withContext null
            val entity = activeSessionDao.getActiveSession(userId)
            if (authRepository.getLocalUserId() != userId) return@withContext null
            
            if (entity != null && entity.id !in terminalSessions) {
                val sessionData = json.decodeFromString<SerializableSession>(entity.sessionDataJson)
                val recovered = sessionData.toDomain()
                if (activeSession?.id != recovered.id || activeSessionUserId != userId) {
                    Log.d("WorkoutRepository", "Restored active session from Room")
                }
                activeSession = recovered
                activeSessionUserId = userId
                recovered
            } else {
                null
            }
        }

    override suspend fun updateActiveSession(session: WorkoutSession) {
        mutateActiveSession(session.id) { session }
    }

    override suspend fun mutateActiveSession(sessionId: String, transform: (WorkoutSession) -> WorkoutSession): WorkoutSession? =
        withContext(Dispatchers.IO) { sessionMutex.withLock {
            if (sessionId in terminalSessions) return@withLock null
            val userId = authRepository.getLocalUserId() ?: return@withLock null
            database.withTransaction {
                val existing = activeSessionDao.getActiveSession(userId)?.takeIf { it.id == sessionId } ?: return@withTransaction null
                val current = json.decodeFromString<SerializableSession>(existing.sessionDataJson).toDomain()
                val next = transform(current)
                validateActiveSessionEdit(current, next)
                if (sessionId in terminalSessions) return@withTransaction null
                activeSessionDao.updateSession(existing.copy(
                    sessionDataJson = json.encodeToString(next.toSerializable()),
                    currentExerciseIndex = next.currentExerciseIndex,
                    isPaused = next.isPaused, lastPauseTime = next.lastPauseTime,
                    totalPausedTimeMs = next.totalPausedTimeMs, updatedAt = System.currentTimeMillis()))
                activeSession = next
                activeSessionUserId = userId
                next
            }
        } }

    override suspend fun finishWorkout(): WorkoutLog? {
        val completed = finishLocally() ?: return null
        return completed
    }

    private suspend fun finishLocally(): WorkoutLog? = lifecycleMutex.withLock lifecycle@{
        val session = sessionMutex.withLock {
            val raw = getActiveSessionForTimer() ?: return@withLock null
            val completed = raw.exerciseLogs.mapNotNull { log ->
                log.sets.filter { it.isCompleted }.takeIf { it.isNotEmpty() }?.let { log.copy(sets = it) }
            }
            if (completed.isEmpty()) return@withLock null
            terminalSessions.add(raw.id)
            raw.copy(exerciseLogs = completed)
        } ?: return@lifecycle null
        val endTime = System.currentTimeMillis()

        try {
            timerCleanup?.stop("WORKOUT_FINISHED")
            val result = withContext(Dispatchers.IO) { database.withTransaction {
                val persistedSession = activeSessionDao.getSessionById(session.id)
                    ?: return@withTransaction null
                check(persistedSession.userId == authRepository.getLocalUserId()) { "Authenticated owner changed" }
                val workoutLogId = session.id
                val now = endTime
                val totalVolume = session.totalVolume
                val totalSets = session.totalCompletedSets
                val totalReps = session.exerciseLogs.sumOf { log ->
                    log.sets.sumOf { it.reps }
                }

                val workoutLogEntity = WorkoutLogEntity(
                    id = workoutLogId,
                    userId = persistedSession.userId,
                    templateId = session.templateId,
                    templateName = session.templateName,
                    date = session.startTime,
                    startTime = session.startTime,
                    endTime = endTime,
                    totalVolume = totalVolume,
                    totalSets = totalSets,
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
                        totalSets = exerciseLog.sets.size,
                        totalReps = exerciseLog.sets.sumOf { it.reps },
                        syncStatus = SyncStatus.PENDING_SYNC.name,
                        pendingOperation = PendingOperation.CREATE.name,
                        createdAt = now,
                        updatedAt = now,
                        inputDefaults = exerciseLog.inputDefaults?.key
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
                    totalVolume = totalVolume,
                    exerciseLogs = session.exerciseLogs
                )
            } }

            if (result != null) {
                activeSession = null
                activeSessionUserId = null
                syncScheduler.enqueue(result.userId)
            }
            result
        } catch (e: CancellationException) {
            terminalSessions.remove(session.id)
            throw e
        } catch (e: Exception) {
            terminalSessions.remove(session.id)
            Log.e("WorkoutRepository", "Failed to persist finished workout", e)
            null
        }
    }

    override suspend fun cancelWorkout() {
        val session = getActiveSessionForTimer() ?: return
        terminalSessions.add(session.id)
        cancelSession(session.id)
    }

    override fun requestCancelSession(sessionId: String) {
        if (!terminalSessions.add(sessionId)) return
        cleanupScope.launch {
            repeat(3) { attempt ->
                try { cancelSession(sessionId); return@launch }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { Log.e("WorkoutRepository", "Cancel session cleanup failed", e); delay(250L * (attempt + 1)) }
            }
        }
    }

    private suspend fun cancelSession(sessionId: String) = lifecycleMutex.withLock {
        val owned = withContext(Dispatchers.IO) { sessionMutex.withLock {
            val userId = authRepository.getLocalUserId()
            val current = userId?.let { activeSessionDao.getActiveSession(it) }
            val belongs = current?.id == sessionId
            if (belongs) activeSessionDao.deleteSessionById(sessionId)
            else terminalSessions.remove(sessionId)
            if (activeSession?.id == sessionId) { activeSession = null; activeSessionUserId = null }
            belongs || current == null
        } }
        if (owned) withContext(Dispatchers.Main.immediate) { timerCleanup?.stop("WORKOUT_CANCELLED") }
    }

    override suspend fun updateSessionRestOverride(seconds: Int) {
        withContext(Dispatchers.IO) { sessionMutex.withLock {
            val userId = authRepository.getLocalUserId() ?: return@withLock
            database.withTransaction {
                val entity = activeSessionDao.getActiveSession(userId)?.takeUnless { it.id in terminalSessions } ?: return@withTransaction
                activeSessionDao.updateSession(entity.copy(sessionRestOverride = seconds, updatedAt = System.currentTimeMillis()))
            }
        } }
    }
    
    override suspend fun getSessionRestOverride(): Int? {
        return try {
            val userId = authRepository.getLocalUserId() ?: return null
            activeSession?.takeIf { activeSessionUserId == userId }
                ?.let { activeSessionDao.getSessionById(it.id) }?.sessionRestOverride
                ?: activeSessionDao.getActiveSession(userId)?.sessionRestOverride
        } catch (e: Exception) {
            Log.e("WorkoutRepository", "Failed to get session rest override: ${e.message}", e)
            null
        }
    }

    override suspend fun getWorkoutLogs(userId: String): List<WorkoutLog> {
        return accountAccess.read(userId, emptyList()) {
            try {
                val localLogs = workoutLogDao.getWorkoutLogsByUserList(userId)
                localLogs.map { logEntity -> mapWorkoutLogEntityToDomain(logEntity) }
            } catch (e: Exception) {
                Log.e("WorkoutRepository", "Error fetching workout logs from local: ${e.message}", e)
                emptyList()
            }
        }
    }

    override suspend fun getWorkoutLogById(logId: String): WorkoutLog? {
        return accountAccess.read(empty = null as WorkoutLog?) { owner ->
            try {
                workoutLogDao.getWorkoutLogById(logId)?.takeIf { it.userId == owner && it.deletedAt == null }
                    ?.let { mapWorkoutLogEntityToDomain(it) }
            } catch (e: Exception) {
                Log.e("WorkoutRepository", "Error fetching workout log from local: ${e.message}", e)
                null
            }
        }
    }

    override suspend fun getLastWorkoutLog(userId: String): WorkoutLog? {
        return accountAccess.read(userId, null as WorkoutLog?) {
            try {
                workoutLogDao.getLastWorkoutLog(userId)?.let { mapWorkoutLogEntityToDomain(it) }
            } catch (e: Exception) {
                Log.e("WorkoutRepository", "Error fetching last workout log from local: ${e.message}", e)
                null
            }
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
            
            val muscleCategory = MuscleCategory.entries.firstOrNull { it.name == exerciseLogEntity.muscleCategory }
                ?: exerciseEntity?.let { MuscleCategory.entries.getOrNull(it.muscleCategoryId - 1) }
                ?: MuscleCategory.CHEST
            
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

    override suspend fun getLastExerciseLog(exerciseId: String): ExerciseLog? =
        accountAccess.read(empty = null as ExerciseLog?) { userId ->
        val logEntity = exerciseLogDao.getLastExerciseLog(exerciseId, userId) ?: return@read null
        val exerciseEntity = exerciseDao.getExerciseById(exerciseId) ?: return@read null
        val setEntities = workoutSetDao.getSetsForExercise(logEntity.workoutLogId, exerciseId)
        val category = MuscleCategory.entries.getOrNull(exerciseEntity.muscleCategoryId - 1)
            ?: MuscleCategory.CHEST
        ExerciseLog(
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
     * Gets a map of set_number to (weight, reps) from the last completed workout for an exercise.
     * Uses local Room database for efficient per-set performance lookup.
     */
    override suspend fun getLastPerformanceForExercise(
        exerciseId: String,
        userId: String
    ): Map<Int, Pair<Float, Int>> {
        return accountAccess.read(userId, emptyMap()) {
            try {
                val lastSets = workoutSetDao.getLastWorkoutSetsForExercise(exerciseId, userId)
                lastSets.associate { it.setNumber to (it.weight to it.reps) }
            } catch (e: Exception) {
                Log.e("WorkoutRepository", "Error getting last performance for exercise: ${e.message}", e)
                emptyMap()
            }
        }
    }

    override suspend fun getLastWeightsForExercise(exerciseId: String, userId: String): Map<Int, Float> {
        return getLastPerformanceForExercise(exerciseId, userId).mapValues { it.value.first }
    }

    override suspend fun getPrefillForExercise(exerciseId: String, userId: String,
        defaults: ExerciseInputDefaults): Map<Int, Pair<Float, Int>> = accountAccess.read(userId, emptyMap()) {
            database.withTransaction {
                if (authRepository.getLocalUserId() != userId) return@withTransaction emptyMap()
                val sets = workoutSetDao.getLastWorkoutSetsForExercise(exerciseId, userId)
                val first = sets.firstOrNull() ?: return@withTransaction emptyMap()
                val log = exerciseLogDao.getExerciseLog(first.workoutLogId, exerciseId) ?: return@withTransaction emptyMap()
                if (log.inputDefaults != defaults.key) return@withTransaction emptyMap()
                sets.associate { it.setNumber to (it.weight to it.reps) }
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
    val restSeconds: Int = 60,
    val inputDefaults: ExerciseInputDefaults? = null
) {
    fun toDomain(): ExerciseLog = ExerciseLog(
        exercise = exercise.toDomain(),
        sets = sets.map { it.toDomain() },
        restSeconds = restSeconds,
        inputDefaults = inputDefaults
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
    restSeconds = restSeconds,
    inputDefaults = inputDefaults
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

package com.diajarkoding.imfit.data.repository

import android.util.Log
import androidx.room.withTransaction
import com.diajarkoding.imfit.data.local.dao.ActiveSessionDao
import com.diajarkoding.imfit.data.local.database.IMFITDatabase
import com.diajarkoding.imfit.data.local.entity.ActiveSessionEntity
import com.diajarkoding.imfit.data.local.entity.ExerciseEntity
import com.diajarkoding.imfit.data.local.entity.ExerciseLogEntity
import com.diajarkoding.imfit.data.local.entity.WorkoutLogEntity
import com.diajarkoding.imfit.data.local.entity.WorkoutSetEntity
import com.diajarkoding.imfit.data.local.sync.PendingOperation
import com.diajarkoding.imfit.data.local.sync.SyncStatus
import com.diajarkoding.imfit.data.remote.WorkoutRemoteDataSource
import com.diajarkoding.imfit.data.remote.dto.WorkoutLogDto
import com.diajarkoding.imfit.data.remote.dto.parseRemoteTimestamp
import com.diajarkoding.imfit.data.remote.dto.toDomain
import com.diajarkoding.imfit.data.sync.RemoteSnapshotImporter
import com.diajarkoding.imfit.data.sync.SyncScheduler
import com.diajarkoding.imfit.domain.model.Exercise
import com.diajarkoding.imfit.domain.model.ExerciseLog
import com.diajarkoding.imfit.domain.model.ExerciseInputDefaults
import com.diajarkoding.imfit.domain.model.HomeRemoteReadResult
import com.diajarkoding.imfit.domain.model.inputDefaults
import com.diajarkoding.imfit.domain.model.MuscleCategory
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
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
    private val activeSessionDao: ActiveSessionDao,
    private val database: IMFITDatabase,
    private val syncScheduler: SyncScheduler,
    private val timerCleanup: com.diajarkoding.imfit.core.timer.TimerSessionCleanup? = null,
    private val remoteDataSource: WorkoutRemoteDataSource? = null
) : WorkoutRepository {

    constructor(
        authRepository: AuthRepository,
        exerciseLogDao: com.diajarkoding.imfit.data.local.dao.ExerciseLogDao,
        workoutSetDao: com.diajarkoding.imfit.data.local.dao.WorkoutSetDao,
        exerciseDao: com.diajarkoding.imfit.data.local.dao.ExerciseDao,
        workoutLogDao: com.diajarkoding.imfit.data.local.dao.WorkoutLogDao,
        activeSessionDao: ActiveSessionDao,
        database: IMFITDatabase,
        syncScheduler: SyncScheduler,
        timerCleanup: com.diajarkoding.imfit.core.timer.TimerSessionCleanup? = null
    ) : this(
        authRepository = authRepository,
        exerciseLogDao = exerciseLogDao,
        workoutSetDao = workoutSetDao,
        exerciseDao = exerciseDao,
        workoutLogDao = workoutLogDao,
        activeSessionDao = activeSessionDao,
        database = database,
        syncScheduler = syncScheduler,
        timerCleanup = timerCleanup,
        remoteDataSource = null
    )

    private var activeSession: WorkoutSession? = null
    private var activeSessionUserId: String? = null
    private val sessionMutex = Mutex()
    private val lifecycleMutex = Mutex()
    private val terminalSessions = ConcurrentHashMap.newKeySet<String>()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val snapshotImporter = RemoteSnapshotImporter(database)
    private val json = Json {
        ignoreUnknownKeys = true 
        encodeDefaults = true
    }

    override fun observeWorkoutLogs(userId: String): Flow<List<WorkoutLog>> =
        database.invalidationTracker.createFlow("workout_logs", "exercise_logs", "workout_sets", "exercises")
            .map { database.withTransaction {
                workoutLogDao.getWorkoutLogsByUserList(userId).map { mapWorkoutLogEntityToDomain(it) }
            } }.distinctUntilChanged().flowOn(Dispatchers.IO)

    override fun observeLastWorkoutLog(userId: String): Flow<WorkoutLog?> =
        database.invalidationTracker.createFlow("workout_logs", "exercise_logs", "workout_sets", "exercises")
            .map { database.withTransaction {
                workoutLogDao.getLastWorkoutLog(userId)?.let { mapWorkoutLogEntityToDomain(it) }
            } }.distinctUntilChanged().flowOn(Dispatchers.IO)

    override fun observeWorkoutLog(logId: String): Flow<WorkoutLog?> =
        database.invalidationTracker.createFlow("workout_logs", "exercise_logs", "workout_sets", "exercises")
            .map { database.withTransaction {
                workoutLogDao.getWorkoutLogById(logId)?.takeIf { it.deletedAt == null }?.let { mapWorkoutLogEntityToDomain(it) }
            } }.distinctUntilChanged().flowOn(Dispatchers.IO)
    override suspend fun fetchRemoteLastWorkoutLog(userId: String): HomeRemoteReadResult<WorkoutLog?> =
        withContext(Dispatchers.IO) {
            ensureActiveOwner(userId)
            enqueuePendingSyncIfNeeded(userId)
            val source = remoteDataSource
            if (source == null) {
                return@withContext HomeRemoteReadResult.RemotePrimary(getLastWorkoutLog(userId))
            }
            try {
                val remoteDto = source.fetchLatestCompletedWorkoutLog(userId)
                ensureActiveOwner(userId)
                if (remoteDto != null && remoteDto.userId == userId && remoteDto.deletedAt == null) {
                    importRemoteWorkoutLogSnapshot(userId, remoteDto)
                }
                val remoteDomain = remoteDto?.takeIf { it.userId == userId && it.deletedAt == null }?.toDomain()
                val localLast = getLastWorkoutLog(userId)
                val merged = when {
                    localLast != null && remoteDomain != null -> {
                        if (localLast.endTime >= remoteDomain.endTime) localLast else remoteDomain
                    }
                    localLast != null && remoteDomain == null -> {
                        val localEntity = workoutLogDao.getWorkoutLogById(localLast.id)
                        if (localEntity != null && (localEntity.syncStatus != SyncStatus.SYNCED.name || localEntity.pendingOperation != null)) {
                            localLast
                        } else {
                            null
                        }
                    }
                    else -> remoteDomain
                }
                HomeRemoteReadResult.RemotePrimary(merged)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ensureActiveOwner(userId)
                val localLast = getLastWorkoutLog(userId)
                if (localLast != null) {
                    Log.w("WorkoutRepository", "Remote last workout fetch failed, using local fallback: ${e.message}")
                    HomeRemoteReadResult.LocalFallback(localLast, e)
                } else {
                    throw e
                }
            }
        }
    override suspend fun startWorkout(template: WorkoutTemplate): WorkoutSession = lifecycleMutex.withLock {
        val userId = authRepository.getLocalUserId() ?: throw IllegalStateException("Authentication required")
        require(template.userId == userId) { "Routine owner does not match authenticated user" }
        getActiveSessionForTimer()?.let { return@withLock it }
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
            ensureActiveOwner(userId)
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
            workoutId !in terminalSessions && activeSessionDao.getActiveSessionId(userId) == workoutId &&
                workoutId !in terminalSessions
        }

    override suspend fun getActiveSessionForTimer(): WorkoutSession? =
        withContext(Dispatchers.IO) {
            val userId = authRepository.getLocalUserId() ?: return@withContext null
            val entity = activeSessionDao.getActiveSession(userId)
            
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

    override suspend fun finishWorkout(): WorkoutLog? = lifecycleMutex.withLock lifecycle@{
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
            activeSessionDao.deleteSessionById(sessionId)
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

    override suspend fun getLastExerciseLog(exerciseId: String): ExerciseLog? {
        val userId = authRepository.getLocalUserId() ?: return null
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
     * Gets a map of set_number to (weight, reps) from the last completed workout for an exercise.
     * Uses local Room database for efficient per-set performance lookup.
     */
    override suspend fun getLastPerformanceForExercise(
        exerciseId: String,
        userId: String
    ): Map<Int, Pair<Float, Int>> {
        return try {
            val lastSets = workoutSetDao.getLastWorkoutSetsForExercise(exerciseId, userId)
            lastSets.associate { it.setNumber to (it.weight to it.reps) }
        } catch (e: Exception) {
            Log.e("WorkoutRepository", "Error getting last performance for exercise: ${e.message}", e)
            emptyMap()
        }
    }

    override suspend fun getLastWeightsForExercise(exerciseId: String, userId: String): Map<Int, Float> {
        return getLastPerformanceForExercise(exerciseId, userId).mapValues { it.value.first }
    }

    override suspend fun getPrefillForExercise(exerciseId: String, userId: String,
        defaults: ExerciseInputDefaults): Map<Int, Pair<Float, Int>> = database.withTransaction {
        val sets = workoutSetDao.getLastWorkoutSetsForExercise(exerciseId, userId)
        val first = sets.firstOrNull() ?: return@withTransaction emptyMap()
        val log = exerciseLogDao.getExerciseLog(first.workoutLogId, exerciseId) ?: return@withTransaction emptyMap()
        if (log.inputDefaults != defaults.key) return@withTransaction emptyMap()
        sets.associate { it.setNumber to (it.weight to it.reps) }
    }

    private suspend fun ensureActiveOwner(expectedUserId: String) {
        val currentId = authRepository.peekLocalUserId() ?: authRepository.getLocalUserId()
        check(currentId == expectedUserId) {
            "Authenticated user changed during Home remote read"
        }
    }

    private fun isStillCurrentOwner(expectedUserId: String): Boolean {
        val profileUserId = authRepository.userProfileState.value.userId
        return profileUserId == expectedUserId
    }
    private suspend fun enqueuePendingSyncIfNeeded(userId: String) {
        try {
            val hasPendingLogs = workoutLogDao.getPendingLogs(userId).isNotEmpty()
            if (hasPendingLogs) {
                syncScheduler.enqueue(userId)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("WorkoutRepository", "Unable to inspect pending sync queue: ${e.message}")
        }
    }

    private suspend fun importRemoteWorkoutLogSnapshot(userId: String, remote: WorkoutLogDto) {
        val catalogExercises = remote.exerciseLogs.orEmpty()
            .mapNotNull { it.exercises }
            .distinctBy { it.id }
            .map { dto ->
                ExerciseEntity(
                    id = dto.id,
                    name = dto.name,
                    description = dto.description,
                    muscleCategoryId = dto.muscleCategoryId,
                    imageUrl = dto.imageUrl,
                    isActive = dto.isActive
                )
            }
        if (catalogExercises.isNotEmpty() && isStillCurrentOwner(userId)) {
            exerciseDao.insertExercises(catalogExercises)
        }

        val startMs = parseRemoteTimestamp(remote.startTime, fallbackMs = System.currentTimeMillis())
        val endMs = parseRemoteTimestamp(remote.endTime, fallbackMs = startMs)
        val dateMs = parseRemoteTimestamp(remote.date, fallbackMs = startMs)
        val createdAtMs = parseRemoteTimestamp(remote.createdAt, fallbackMs = startMs)
        val updatedAtMs = parseRemoteTimestamp(remote.updatedAt, fallbackMs = endMs)

        val parentEntity = WorkoutLogEntity(
            id = remote.id,
            userId = remote.userId,
            templateId = remote.templateId,
            templateName = remote.templateName,
            date = dateMs,
            startTime = startMs,
            endTime = endMs,
            totalVolume = remote.totalVolume.toFloat(),
            totalSets = remote.totalSets,
            totalReps = remote.totalReps,
            syncStatus = SyncStatus.SYNCED.name,
            pendingOperation = null,
            deletedAt = remote.deletedAt?.let { parseRemoteTimestamp(it) },
            createdAt = createdAtMs,
            updatedAt = updatedAtMs
        )
        val sortedExercises = remote.exerciseLogs.orEmpty()
            .sortedWith(compareBy({ it.orderIndex }, { it.id.orEmpty() }, { it.exerciseId }))
        val exerciseEntities = sortedExercises.mapIndexed { index, child ->
            val exerciseLogId = child.id?.takeIf { it.isNotBlank() }
                ?: stableUuid("exercise-log", remote.id, index, child.exerciseId)
            val sets = child.workoutSets.orEmpty()
            ExerciseLogEntity(
                id = exerciseLogId,
                workoutLogId = remote.id,
                exerciseId = child.exerciseId,
                exerciseName = child.exerciseName,
                muscleCategory = child.muscleCategory,
                orderIndex = child.orderIndex,
                totalVolume = child.totalVolume.toFloat(),
                totalSets = if (child.totalSets > 0) child.totalSets else sets.size,
                totalReps = if (child.totalReps > 0) child.totalReps else sets.sumOf { it.reps },
                syncStatus = SyncStatus.SYNCED.name,
                pendingOperation = null,
                inputDefaults = (child.inputDefaults as? JsonPrimitive)?.contentOrNull
            )
        }
        val setEntities = sortedExercises.flatMapIndexed { exerciseIndex, child ->
            val exerciseLogId = exerciseEntities[exerciseIndex].id
            child.workoutSets.orEmpty()
                .sortedWith(compareBy({ it.setNumber }, { it.id.orEmpty() }))
                .mapIndexed { setIndex, set ->
                    val setId = set.id?.takeIf { it.isNotBlank() }
                        ?: stableUuid("workout-set", exerciseLogId, setIndex, set.setNumber)
                    WorkoutSetEntity(
                        id = setId,
                        exerciseLogId = exerciseLogId,
                        workoutLogId = remote.id,
                        exerciseId = set.exerciseId ?: child.exerciseId,
                        setNumber = set.setNumber,
                        weight = set.weight.toFloat(),
                        reps = set.reps,
                        isCompleted = set.isCompleted,
                        syncStatus = SyncStatus.SYNCED.name,
                        pendingOperation = null
                    )
                }
        }
        snapshotImporter.workout(
            parent = parentEntity,
            exercises = exerciseEntities,
            sets = setEntities,
            isCurrentOwner = { isStillCurrentOwner(userId) }
        )
    }

    companion object {
        private fun stableUuid(vararg parts: Any): String = UUID.nameUUIDFromBytes(
            parts.joinToString("\u001F").toByteArray(StandardCharsets.UTF_8)
        ).toString()

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

package com.diajarkoding.imfit.data.repository

import android.util.Log
import androidx.room.withTransaction
import com.diajarkoding.imfit.data.local.dao.ActiveSessionDao
import com.diajarkoding.imfit.data.local.database.IMFITDatabase
import com.diajarkoding.imfit.data.local.entity.ActiveSessionEntity
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
import com.diajarkoding.imfit.domain.model.RestEndReason
import com.diajarkoding.imfit.domain.model.finishedDurations
import com.diajarkoding.imfit.domain.model.RestOutcome
import com.diajarkoding.imfit.domain.model.TemplateExercise
import com.diajarkoding.imfit.domain.model.TrackingType
import com.diajarkoding.imfit.domain.model.ExerciseModality
import com.diajarkoding.imfit.domain.model.SetMeasurement
import com.diajarkoding.imfit.domain.model.WorkoutTemplate
import com.diajarkoding.imfit.domain.model.validateActiveSessionEdit
import com.diajarkoding.imfit.domain.repository.AuthRepository
import com.diajarkoding.imfit.domain.repository.WorkoutRepository
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
    private val workoutLogDao: com.diajarkoding.imfit.data.local.dao.WorkoutLogDao,
    private val activeSessionDao: ActiveSessionDao,
    private val database: IMFITDatabase,
    private val syncScheduler: SyncScheduler,
    private val timerCleanup: com.diajarkoding.imfit.core.timer.TimerSessionCleanup? = null,
    private val remoteDataSource: WorkoutRemoteDataSource? = null
) : WorkoutRepository {


    private var activeSession: WorkoutSession? = null
    private var activeSessionUserId: String? = null
    private val sessionMutex = Mutex()
    private val lifecycleMutex = Mutex()
    private val terminalSessions = ConcurrentHashMap.newKeySet<String>()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val snapshotImporter = RemoteSnapshotImporter(database)

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
                val remoteDomain = remoteDto?.takeIf { it.userId == userId && it.deletedAt == null }
                    ?.let { importRemoteWorkoutLogSnapshot(userId, it) }
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
    /**
     * T26: one draft result per routine target, each with a fresh stable UUID linked by `target_row_id`.
     * The frozen snapshot (targets, rest, metadata) lives in `inputDefaults`; `extra_set_targets` starts empty.
     * Explicit routine targets win over history, so no prefill is applied.
     */
    private fun buildV3ExerciseLog(templateExercise: TemplateExercise): ExerciseLog {
        val defaults = templateExercise.inputDefaults()
        val isStrength = templateExercise.trackingType == TrackingType.WEIGHT_REPS
        return ExerciseLog(
            exercise = templateExercise.exercise,
            sets = requireNotNull(defaults.setTargets).map { target ->
                WorkoutSet(
                    setNumber = target.setNumber,
                    weightKg = if (isStrength) target.weightKg else null,
                    reps = if (isStrength) target.reps else null,
                    durationSeconds = if (isStrength) null else target.durationSeconds,
                    isCompleted = false,
                    id = UUID.randomUUID().toString(),
                    targetRowId = target.rowId,
                    measurementVersion = 3
                )
            },
            restSeconds = 60,
            inputDefaults = defaults,
            trackingType = templateExercise.trackingType,
            modality = templateExercise.exercise.modality,
            measurementVersion = 3
        )
    }

    override suspend fun startWorkout(template: WorkoutTemplate): WorkoutSession = lifecycleMutex.withLock {
        val userId = authRepository.getLocalUserId() ?: throw IllegalStateException("Authentication required")
        require(template.userId == userId) { "Routine owner does not match authenticated user" }
        val isV3 = template.measurementVersion == 3 || template.exercises.any { it.measurementVersion == 3 || it.setTargets != null }
        if (isV3) {
            require(ActiveSessionV3Gate.enabled) {
                "Starting a v3 routine is not supported until the v3 execution workflow is available"
            }
            require(template.measurementVersion == 3 && template.exercises.all { it.measurementVersion == 3 && it.setTargets != null }) {
                "A v3 routine must contain only v3 targets"
            }
        }
        getActiveSessionForTimer()?.let { return@withLock it }
        timerCleanup?.stop("SESSION_REPLACED")
        val exerciseLogs = if (isV3) template.exercises.map(::buildV3ExerciseLog) else template.exercises.map { templateExercise ->
            val defaults = templateExercise.inputDefaults()
            val previous = getPrefillForExercise(templateExercise.id, userId, defaults)
            ExerciseLog(
                exercise = templateExercise.exercise,
                sets = (1..templateExercise.sets).map { setNumber ->
                    WorkoutSet(
                        setNumber = setNumber,
                        weightKg = previous[setNumber]?.weightKg ?: 0.0,
                        reps = previous[setNumber]?.reps ?: (templateExercise.reps ?: 0),
                        durationSeconds = previous[setNumber]?.durationSeconds ?: templateExercise.targetDurationSeconds,
                        distanceMeters = previous[setNumber]?.distanceMeters ?: templateExercise.targetDistanceMeters?.toDouble(),
                        additionalWeight = previous[setNumber]?.additionalWeight,
                        assistanceWeight = previous[setNumber]?.assistanceWeight,
                        isCompleted = false
                    )
                },
                restSeconds = 60,
                inputDefaults = defaults,
                trackingType = templateExercise.trackingType,
                modality = templateExercise.exercise.modality
            )
        }
        val session = WorkoutSession(
            id = UUID.randomUUID().toString(),
            templateId = template.id,
            templateName = template.name,
            startTime = System.currentTimeMillis(),
            exerciseLogs = exerciseLogs,
            schemaVersion = if (isV3) 3 else 2,
            templateRevision = if (isV3) template.revision else null
        )
        
        val sessionDataJson = WorkoutSessionCodec.encode(session)
            
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
            throw e
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
                val recovered = WorkoutSessionCodec.decode(entity.sessionDataJson)
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
                val current = WorkoutSessionCodec.decode(existing.sessionDataJson)
                val next = transform(current)
                validateActiveSessionEdit(current, next)
                if (sessionId in terminalSessions) return@withTransaction null
                activeSessionDao.updateSession(existing.copy(
                    sessionDataJson = WorkoutSessionCodec.encode(next),
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
        // Frozen by the caller (VM) before finish; null for legacy sessions or if the clocks were never frozen.
        val durations = session.finishedDurations(endTime)

        try {
            timerCleanup?.stop("WORKOUT_FINISHED")
            val result = withContext(Dispatchers.IO) { database.withTransaction {
                val persistedSession = activeSessionDao.getSessionById(session.id)
                    ?: return@withTransaction null
                val workoutLogId = session.id
                val now = endTime
                val totalVolume = session.totalVolume
                val totalSets = session.totalCompletedSets
                val totalReps = session.exerciseLogs.sumOf { it.totalReps }
                val totalDurationSeconds = session.exerciseLogs.sumOf { it.totalDurationSeconds }
                val totalDistanceMeters = session.totalDistanceMeters
                val additionalVolume = session.exerciseLogs.sumOf { it.additionalVolume.toDouble() }.toFloat()
                val dataQuality = if (session.exerciseLogs.all { it.metricQuality == "VERIFIED" }) "VERIFIED" else "LEGACY_UNVERIFIED"

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
                    updatedAt = now,
                    totalDurationSeconds = totalDurationSeconds,
                    totalDistanceMeters = totalDistanceMeters,
                    additionalVolume = additionalVolume,
                    dataQuality = dataQuality,
                    measurementVersion = session.schemaVersion.takeIf { it == 3 },
                    recordedLoadVolume = session.recordedLoadVolume.takeIf { session.schemaVersion == 3 },
                    pausedElapsedMs = durations?.pausedElapsedMs,
                    activeElapsedMs = durations?.activeElapsedMs
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
                        totalReps = exerciseLog.totalReps,
                        syncStatus = SyncStatus.PENDING_SYNC.name,
                        pendingOperation = PendingOperation.CREATE.name,
                        createdAt = now,
                        updatedAt = now,
                        inputDefaults = exerciseLog.inputDefaults?.toStoredString(),
                        trackingType = exerciseLog.trackingType.name,
                        modality = exerciseLog.modality.name,
                        totalDurationSeconds = exerciseLog.totalDurationSeconds,
                        totalDistanceMeters = exerciseLog.totalDistanceMeters,
                        additionalVolume = exerciseLog.additionalVolume,
                        metricQuality = exerciseLog.metricQuality,
                        measurementVersion = exerciseLog.measurementVersion,
                        recordedLoadVolume = exerciseLog.recordedLoadVolume.takeIf { exerciseLog.measurementVersion == 3 }
                    )
                }
                val setEntities = session.exerciseLogs.flatMapIndexed { exerciseIndex, exerciseLog ->
                    val exerciseLogId = exerciseEntities[exerciseIndex].id
                    exerciseLog.sets.mapIndexed { setIndex, set ->
                        WorkoutSetEntity(
                            id = if (session.schemaVersion == 3) set.id else stableUuid("workout-set", exerciseLogId, setIndex, set.setNumber),
                            exerciseLogId = exerciseLogId,
                            workoutLogId = workoutLogId,
                            exerciseId = exerciseLog.exercise.id,
                            setNumber = set.setNumber,
                            weight = set.weightKg,
                            reps = set.reps,
                            isCompleted = set.isCompleted,
                            syncStatus = SyncStatus.PENDING_SYNC.name,
                            pendingOperation = PendingOperation.CREATE.name,
                            createdAt = now,
                            updatedAt = now,
                            durationSeconds = set.durationSeconds,
                            distanceMeters = set.distanceMeters,
                            additionalWeight = set.additionalWeight,
                            assistanceWeight = set.assistanceWeight,
                            targetRowId = set.targetRowId,
                            measurementVersion = set.measurementVersion,
                            caloriesKcal = set.caloriesKcal,
                            speedKph = set.speedKph,
                            lapReps = set.lapReps,
                            inclinePercent = set.inclinePercent,
                            restElapsedMs = set.restOutcome?.elapsedMs,
                            restEndReason = set.restOutcome?.endReason?.name
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
                    exerciseLogs = session.exerciseLogs,
                    totalSets = totalSets,
                    totalReps = totalReps,
                    totalDurationSeconds = totalDurationSeconds,
                    totalDistanceMeters = totalDistanceMeters,
                    additionalVolume = additionalVolume,
                    dataQuality = dataQuality,
                    measurementVersion = session.schemaVersion.takeIf { it == 3 },
                    recordedLoadVolume = session.recordedLoadVolume.takeIf { session.schemaVersion == 3 },
                    pausedElapsedMs = durations?.pausedElapsedMs,
                    activeElapsedMs = durations?.activeElapsedMs
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
        require(seconds in 1..86_399) { "Rest duration must be between 1 second and 23:59:59" }
        withContext(Dispatchers.IO) { sessionMutex.withLock {
            val userId = checkNotNull(authRepository.getLocalUserId()) { "Authentication required" }
            database.withTransaction {
                val entity = checkNotNull(activeSessionDao.getActiveSession(userId)?.takeUnless { it.id in terminalSessions }) { "Active session required" }
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
            throw e
        }
    }

    override suspend fun getWorkoutLogById(logId: String): WorkoutLog? {
        return try {
            val logEntity = workoutLogDao.getWorkoutLogById(logId) ?: return null
            mapWorkoutLogEntityToDomain(logEntity)
        } catch (e: Exception) {
            Log.e("WorkoutRepository", "Error fetching workout log from local: ${e.message}", e)
            throw e
        }
    }

    override suspend fun getLastWorkoutLog(userId: String): WorkoutLog? {
        return try {
            val logEntity = workoutLogDao.getLastWorkoutLog(userId) ?: return null
            mapWorkoutLogEntityToDomain(logEntity)
        } catch (e: Exception) {
            Log.e("WorkoutRepository", "Error fetching last workout log from local: ${e.message}", e)
            throw e
        }
    }

    /**
     * Helper function to map WorkoutLogEntity to WorkoutLog domain model.
     * Fetches associated exercise logs and sets from local Room database.
     */
    private suspend fun mapWorkoutLogEntityToDomain(logEntity: com.diajarkoding.imfit.data.local.entity.WorkoutLogEntity): WorkoutLog {
        val exerciseLogEntities = exerciseLogDao.getExerciseLogsByWorkoutLogId(logEntity.id)
        require(logEntity.measurementVersion == null || logEntity.measurementVersion == 3) { "Unknown workout measurement version" }
        
        val exerciseLogs = exerciseLogEntities.map { exerciseLogEntity ->
            val setEntities = workoutSetDao.getSetsForExercise(logEntity.id, exerciseLogEntity.exerciseId)
                .filter { it.exerciseLogId == exerciseLogEntity.id }
            val defaults = exerciseLogEntity.inputDefaults?.let(ExerciseInputDefaults::fromStoredString)
            val v3 = exerciseLogEntity.measurementVersion == 3
            require(exerciseLogEntity.measurementVersion == null || exerciseLogEntity.measurementVersion == 3) { "Unknown exercise measurement version" }
            if (v3) {
                require(logEntity.measurementVersion == 3 && defaults?.schemaVersion == 3) { "Missing v3 history snapshot" }
            }
            
            val muscleCategory = if (v3) MuscleCategory.valueOf(exerciseLogEntity.muscleCategory) else
                MuscleCategory.entries.firstOrNull { it.name == exerciseLogEntity.muscleCategory } ?: MuscleCategory.CHEST
            
            val parsedTracking = TrackingType.valueOf(exerciseLogEntity.trackingType)
            val parsedModality = ExerciseModality.valueOf(exerciseLogEntity.modality)
            if (v3) {
                val config = requireNotNull(defaults) { "Missing v3 history snapshot" }
                require(config.trackingType == parsedTracking.name && config.modality == parsedModality) { "Inconsistent v3 history metadata" }
                require(setEntities.all { set ->
                    set.measurementVersion == 3 && !set.targetRowId.isNullOrBlank() &&
                        UUID.fromString(set.id).toString().equals(set.id, ignoreCase = true) &&
                        (config.setTargets.orEmpty().any { it.rowId == set.targetRowId } ||
                            config.extraSetTargets.any { it.rowId == set.targetRowId })
                }) { "Invalid v3 history result identity/configuration" }
            } else {
                require(logEntity.measurementVersion == null && defaults?.schemaVersion != 3 &&
                    setEntities.all { it.measurementVersion == null }) { "V3 results in legacy history" }
            }

            val exercise = Exercise(
                id = exerciseLogEntity.exerciseId,
                name = exerciseLogEntity.exerciseName,
                muscleCategory = muscleCategory,
                description = "",
                defaultTrackingType = parsedTracking,
                modality = parsedModality,
                allowedTrackingTypes = listOf(parsedTracking),
                trackingProfile = defaults?.trackingProfile,
                supportedMetrics = defaults?.supportedMetrics.orEmpty()
            ).also { if (v3) it.requireV3Metadata() }
            
            val sets = setEntities.map { setEntity ->
                WorkoutSet(
                    setNumber = setEntity.setNumber,
                    weightKg = setEntity.weight,
                    reps = setEntity.reps,
                    isCompleted = setEntity.isCompleted,
                    durationSeconds = setEntity.durationSeconds,
                    distanceMeters = setEntity.distanceMeters,
                    additionalWeight = setEntity.additionalWeight,
                    assistanceWeight = setEntity.assistanceWeight,
                    id = setEntity.id,
                    targetRowId = setEntity.targetRowId,
                    measurementVersion = setEntity.measurementVersion,
                    caloriesKcal = setEntity.caloriesKcal,
                    speedKph = setEntity.speedKph,
                    lapReps = setEntity.lapReps,
                    inclinePercent = setEntity.inclinePercent,
                    restOutcome = restOutcomeOf(setEntity)
                )
            }
            
            ExerciseLog(
                exercise = exercise,
                sets = sets,
                restSeconds = 60,
                inputDefaults = defaults,
                trackingType = parsedTracking,
                modality = parsedModality,
                metricQuality = exerciseLogEntity.metricQuality,
                measurementVersion = exerciseLogEntity.measurementVersion
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
            exerciseLogs = exerciseLogs,
            totalSets = logEntity.totalSets,
            totalReps = logEntity.totalReps,
            totalDurationSeconds = logEntity.totalDurationSeconds,
            totalDistanceMeters = logEntity.totalDistanceMeters,
            additionalVolume = logEntity.additionalVolume,
            dataQuality = logEntity.dataQuality,
            measurementVersion = logEntity.measurementVersion,
            recordedLoadVolume = logEntity.recordedLoadVolume,
            pausedElapsedMs = logEntity.pausedElapsedMs,
            activeElapsedMs = logEntity.activeElapsedMs
        )
    }

    private fun restOutcomeOf(set: WorkoutSetEntity): RestOutcome? {
        val reason = set.restEndReason ?: return null
        return RestOutcome(set.restElapsedMs ?: return null, RestEndReason.valueOf(reason))
    }


    override suspend fun getPrefillForExercise(exerciseId: String, userId: String,
        defaults: ExerciseInputDefaults): Map<Int, SetMeasurement> = database.withTransaction {
        if (authRepository.getLocalUserId() != userId) return@withTransaction emptyMap()
        val sets = workoutSetDao.getLastWorkoutSetsForExercise(exerciseId, userId)
        val first = sets.firstOrNull() ?: return@withTransaction emptyMap()
        val log = exerciseLogDao.getExerciseLog(first.workoutLogId, exerciseId) ?: return@withTransaction emptyMap()
        val storedDefaults = log.inputDefaults?.let(ExerciseInputDefaults::fromStoredString)
        if (storedDefaults?.key != defaults.key || log.trackingType != defaults.trackingType ||
            log.metricQuality != "VERIFIED" || (log.measurementVersion == 3) != (defaults.schemaVersion == 3)) return@withTransaction emptyMap()
        sets.filter { it.isCompleted }.associate { set ->
            set.setNumber to SetMeasurement(weightKg = set.weight, reps = set.reps,
                durationSeconds = set.durationSeconds, distanceMeters = set.distanceMeters,
                additionalWeight = set.additionalWeight, assistanceWeight = set.assistanceWeight,
                caloriesKcal = set.caloriesKcal, speedKph = set.speedKph, lapReps = set.lapReps,
                inclinePercent = set.inclinePercent, measurementVersion = set.measurementVersion)
        }
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

    private suspend fun importRemoteWorkoutLogSnapshot(userId: String, remote: WorkoutLogDto): WorkoutLog {
        val hydrated = remote.toDomain()
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
            updatedAt = updatedAtMs,
            totalDurationSeconds = remote.totalDurationSeconds,
            totalDistanceMeters = remote.totalDistanceMeters,
            additionalVolume = remote.additionalVolume.toFloat(),
            dataQuality = remote.dataQuality,
            measurementVersion = remote.measurementVersion,
            recordedLoadVolume = remote.recordedLoadVolume,
            pausedElapsedMs = remote.pausedElapsedMs,
            activeElapsedMs = remote.activeElapsedMs
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
                totalReps = if (child.totalReps > 0) child.totalReps else sets.sumOf { it.reps ?: 0 },
                syncStatus = SyncStatus.SYNCED.name,
                pendingOperation = null,
                inputDefaults = child.inputDefaults?.let { if (it is JsonPrimitive) it.contentOrNull else it.toString() },
                trackingType = child.trackingType ?: TrackingType.WEIGHT_REPS.name,
                modality = child.modality ?: ExerciseModality.STRENGTH.name,
                totalDurationSeconds = child.totalDurationSeconds,
                totalDistanceMeters = child.totalDistanceMeters,
                additionalVolume = child.additionalVolume.toFloat(),
                metricQuality = child.metricQuality,
                measurementVersion = child.measurementVersion,
                recordedLoadVolume = child.recordedLoadVolume
            )
        }
        val setEntities = sortedExercises.flatMapIndexed { exerciseIndex, child ->
            val exerciseLogId = exerciseEntities[exerciseIndex].id
            child.workoutSets.orEmpty()
                .sortedWith(compareBy({ it.setNumber }, { it.id.orEmpty() }))
                .mapIndexed { setIndex, set ->
                    require(set.measurementVersion != 3 || !set.id.isNullOrBlank()) { "Missing v3 result UUID" }
                    val setId = set.id?.takeIf { it.isNotBlank() }
                        ?: stableUuid("workout-set", exerciseLogId, setIndex, set.setNumber)
                    WorkoutSetEntity(
                        id = setId,
                        exerciseLogId = exerciseLogId,
                        workoutLogId = remote.id,
                        exerciseId = set.exerciseId ?: child.exerciseId,
                        setNumber = set.setNumber,
                        weight = if (set.measurementVersion == 3) set.weight else set.weight?.toFloat()?.toDouble(),
                        reps = set.reps,
                        isCompleted = set.isCompleted,
                        syncStatus = SyncStatus.SYNCED.name,
                        pendingOperation = null,
                        durationSeconds = set.durationSeconds,
                        distanceMeters = set.distanceMeters,
                        additionalWeight = set.additionalWeight?.toFloat(),
                        assistanceWeight = set.assistanceWeight?.toFloat(),
                        targetRowId = set.targetRowId,
                        measurementVersion = set.measurementVersion,
                        caloriesKcal = set.caloriesKcal,
                        speedKph = set.speedKph,
                        lapReps = set.lapReps,
                        inclinePercent = set.inclinePercent,
                        restElapsedMs = set.restElapsedMs,
                        restEndReason = set.restEndReason
                    )
                }
        }
        snapshotImporter.workout(
            parent = parentEntity,
            exercises = exerciseEntities,
            sets = setEntities,
            isCurrentOwner = { isStillCurrentOwner(userId) }
        )
        return hydrated
    }

    companion object {
        private fun stableUuid(vararg parts: Any): String = UUID.nameUUIDFromBytes(
            parts.joinToString("\u001F").toByteArray(StandardCharsets.UTF_8)
        ).toString()

    }
}


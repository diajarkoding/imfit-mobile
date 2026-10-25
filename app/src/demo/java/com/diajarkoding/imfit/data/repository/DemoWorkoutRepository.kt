package com.diajarkoding.imfit.data.repository

import com.diajarkoding.imfit.data.local.FakeWorkoutDataSource
import com.diajarkoding.imfit.data.local.FakeUserDataSource
import com.diajarkoding.imfit.domain.model.ExerciseLog
import com.diajarkoding.imfit.domain.model.ExerciseInputDefaults
import com.diajarkoding.imfit.domain.model.inputDefaults
import com.diajarkoding.imfit.domain.model.TemplateExercise
import com.diajarkoding.imfit.domain.model.WorkoutLog
import com.diajarkoding.imfit.domain.model.WorkoutSession
import com.diajarkoding.imfit.domain.model.WorkoutTemplate
import com.diajarkoding.imfit.domain.model.validateActiveSessionEdit
import com.diajarkoding.imfit.domain.repository.WorkoutRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

@Singleton
class DemoWorkoutRepository @Inject constructor(
    private val timerCleanup: com.diajarkoding.imfit.core.timer.TimerSessionCleanup?
) : WorkoutRepository {
    constructor() : this(null)
    private var sessionRestOverride: Int? = null
    private val templateRevision = MutableStateFlow(0L)
    private val historyRevision = MutableStateFlow(0L)
    private val sessionMutex = Mutex()
    private val lifecycleMutex = Mutex()
    private val terminalSessions = ConcurrentHashMap.newKeySet<String>()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    override fun observeTemplates(userId: String) = templateRevision.map { getTemplates(userId) }.distinctUntilChanged()
    override fun observeTemplate(templateId: String) = templateRevision.map { getTemplateById(templateId) }.distinctUntilChanged()
    override fun observeWorkoutLogs(userId: String) = historyRevision.map { getWorkoutLogs(userId) }.distinctUntilChanged()
    override fun observeLastWorkoutLog(userId: String) = historyRevision.map { getLastWorkoutLog(userId) }.distinctUntilChanged()
    override fun observeWorkoutLog(logId: String) = historyRevision.map { getWorkoutLogById(logId) }.distinctUntilChanged()

    override suspend fun getTemplates(userId: String): List<WorkoutTemplate> =
        FakeWorkoutDataSource.getTemplates(userId)

    override suspend fun getTemplateById(templateId: String): WorkoutTemplate? =
        FakeWorkoutDataSource.getTemplateById(templateId)

    override suspend fun createTemplate(
        userId: String,
        name: String,
        exercises: List<TemplateExercise>
    ): WorkoutTemplate = FakeWorkoutDataSource.createTemplate(userId, name, exercises).also { templateRevision.value++ }

    override suspend fun updateTemplate(
        templateId: String,
        name: String,
        exercises: List<TemplateExercise>
    ): WorkoutTemplate? = FakeWorkoutDataSource.updateTemplate(templateId, name, exercises).also { templateRevision.value++ }

    override suspend fun updateTemplateExercises(
        templateId: String,
        exercises: List<TemplateExercise>
    ): WorkoutTemplate? {
        val template = FakeWorkoutDataSource.getTemplateById(templateId) ?: return null
        return FakeWorkoutDataSource.updateTemplate(templateId, template.name, exercises).also { templateRevision.value++ }
    }

    override suspend fun updateTemplateExercise(
        templateId: String,
        exerciseId: String,
        sets: Int,
        reps: Int,
        restSeconds: Int
    ): WorkoutTemplate? = FakeWorkoutDataSource.updateTemplateExercise(
        templateId = templateId,
        exerciseId = exerciseId,
        sets = sets,
        reps = reps,
        restSeconds = restSeconds
    ).also { templateRevision.value++ }

    override suspend fun deleteTemplate(templateId: String): Boolean =
        FakeWorkoutDataSource.deleteTemplate(templateId).also { templateRevision.value++ }

    override suspend fun startWorkout(template: WorkoutTemplate): WorkoutSession = lifecycleMutex.withLock {
        timerCleanup?.stop("SESSION_REPLACED")
        sessionRestOverride = null
        sessionMutex.withLock {
            val session = FakeWorkoutDataSource.startWorkout(template)
            val prefilled = session.copy(exerciseLogs = session.exerciseLogs.map { log ->
                val config = template.exercises.first { it.id == log.exercise.id }.inputDefaults()
                val previous = getPrefillForExercise(log.exercise.id, template.userId, config)
                log.copy(inputDefaults = config, sets = log.sets.map { set ->
                    previous[set.setNumber]?.let { set.copy(weight = it.first, reps = it.second, isCompleted = false) } ?: set
                })
            })
            FakeWorkoutDataSource.updateActiveSession(prefilled)
            prefilled
        }
    }

    override suspend fun getActiveSession(): WorkoutSession? =
        sessionMutex.withLock { FakeWorkoutDataSource.getActiveSession()?.takeUnless { it.id in terminalSessions } }

    override suspend fun updateActiveSession(session: WorkoutSession) {
        mutateActiveSession(session.id) { session }
    }

    override suspend fun mutateActiveSession(sessionId: String, transform: (WorkoutSession) -> WorkoutSession): WorkoutSession? = sessionMutex.withLock {
        val current = FakeWorkoutDataSource.getActiveSession()?.takeIf { it.id == sessionId && it.id !in terminalSessions } ?: return@withLock null
        val next = transform(current)
        validateActiveSessionEdit(current, next)
        if (sessionId in terminalSessions) return@withLock null
        FakeWorkoutDataSource.updateActiveSession(next)
        next
    }

    override suspend fun finishWorkout(): WorkoutLog? = lifecycleMutex.withLock {
        val current = sessionMutex.withLock {
            FakeWorkoutDataSource.getActiveSession()
        } ?: return@withLock null

        val hasAnyCompleted = current.exerciseLogs.any { log -> log.sets.any { it.isCompleted } }
        if (!hasAnyCompleted) return@withLock null

        timerCleanup?.stop("WORKOUT_FINISHED")
        val workoutLog = sessionMutex.withLock {
            if (!terminalSessions.add(current.id)) return@withLock null
            FakeWorkoutDataSource.finishWorkout()
        }
        sessionRestOverride = null
        if (workoutLog != null) historyRevision.value++
        workoutLog
    }

    override suspend fun cancelWorkout() {
        val current = getActiveSession() ?: return
        terminalSessions.add(current.id)
        cancelSession(current.id)
    }

    override fun requestCancelSession(sessionId: String) {
        if (!terminalSessions.add(sessionId)) return
        cleanupScope.launch { cancelSession(sessionId) }
    }

    private suspend fun cancelSession(sessionId: String) = lifecycleMutex.withLock {
        val owned = sessionMutex.withLock {
            val current = FakeWorkoutDataSource.getActiveSession()
            if (current?.id == sessionId) {
                FakeWorkoutDataSource.cancelWorkout()
                sessionRestOverride = null
            }
            current == null || current.id == sessionId
        }
        if (owned && timerCleanup != null) withContext(Dispatchers.Main.immediate) { timerCleanup.stop("WORKOUT_CANCELLED") }
    }

    override suspend fun updateSessionRestOverride(seconds: Int) {
        sessionRestOverride = seconds
    }

    override suspend fun getSessionRestOverride(): Int? = sessionRestOverride

    override suspend fun getWorkoutLogs(userId: String): List<WorkoutLog> =
        FakeWorkoutDataSource.getWorkoutLogs(userId)

    override suspend fun getWorkoutLogById(logId: String): WorkoutLog? =
        FakeWorkoutDataSource.getWorkoutLogById(logId)

    override suspend fun getLastWorkoutLog(userId: String): WorkoutLog? =
        FakeWorkoutDataSource.getLastWorkoutLog(userId)

    override suspend fun getLastExerciseLog(exerciseId: String): ExerciseLog? =
        FakeUserDataSource.getCurrentUser()
            ?.let { FakeWorkoutDataSource.getWorkoutLogs(it.id) }
            .orEmpty()
            .asSequence()
            .flatMap { it.exerciseLogs.asSequence() }
            .firstOrNull { it.exercise.id == exerciseId }

    override suspend fun getLastWeightsForExercise(
        exerciseId: String,
        userId: String
    ): Map<Int, Float> = FakeWorkoutDataSource.getWorkoutLogs(userId)
        .asSequence()
        .flatMap { it.exerciseLogs.asSequence() }
        .firstOrNull { it.exercise.id == exerciseId }
        ?.sets
        ?.associate { it.setNumber to it.weight }
        .orEmpty()

    override suspend fun getPrefillForExercise(exerciseId: String, userId: String,
        defaults: ExerciseInputDefaults): Map<Int, Pair<Float, Int>> {
        val log = FakeWorkoutDataSource.getWorkoutLogs(userId).sortedByDescending { it.endTime }
            .asSequence().mapNotNull { workout -> workout.exerciseLogs.firstOrNull {
                it.exercise.id == exerciseId && it.sets.any { set -> set.isCompleted }
            } }.firstOrNull() ?: return emptyMap()
        if (log.inputDefaults != defaults) return emptyMap()
        return log.sets.filter { it.isCompleted }.associate { it.setNumber to (it.weight to it.reps) }
    }
}

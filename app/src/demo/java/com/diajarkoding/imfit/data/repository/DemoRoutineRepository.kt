package com.diajarkoding.imfit.data.repository

import com.diajarkoding.imfit.data.local.FakeWorkoutDataSource
import com.diajarkoding.imfit.domain.model.TemplateExercise
import com.diajarkoding.imfit.domain.model.WorkoutTemplate
import com.diajarkoding.imfit.domain.model.supportsRepetitions
import com.diajarkoding.imfit.domain.repository.RoutineError
import com.diajarkoding.imfit.domain.repository.RoutineRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DemoRoutineRepository @Inject constructor() : RoutineRepository {
    private val lock = Mutex()
    private val routines = FakeWorkoutDataSource.getTemplates("user_1").associateBy { it.id }.toMutableMap()
    private data class Command(val id: String, val owner: String, val name: String, val exercises: List<TemplateExercise>, val revision: Long?, val removed: List<String>, val deleted: Boolean)
    private val receipts = mutableMapOf<String, Pair<Command, WorkoutTemplate>>()

    override suspend fun getRoutines(userId: String): Result<List<WorkoutTemplate>> = lock.withLock {
        Result.success(routines.values.filter { it.userId == userId })
    }
    override suspend fun getRoutineById(routineId: String): Result<WorkoutTemplate?> = lock.withLock {
        Result.success(routines[routineId])
    }
    override fun getCachedRoutine(userId: String, routineId: String): WorkoutTemplate? =
        synchronized(routines) { routines[routineId]?.takeIf { it.userId == userId } }

    override suspend fun createRoutine(userId: String, name: String, requestId: String): Result<WorkoutTemplate> = lock.withLock {
        val id = UUID.nameUUIDFromBytes("routine:$userId:$requestId".toByteArray(Charsets.UTF_8)).toString()
        apply(requestId, Command(id, userId, name.trim(), emptyList(), null, emptyList(), false))
    }
    override suspend fun saveRoutine(routineId: String, name: String, exercises: List<TemplateExercise>, expectedRevision: Long?, requestId: String, baselineExerciseIds: List<String>): Result<WorkoutTemplate> = lock.withLock {
        val owner = routines[routineId]?.userId ?: receipts[requestId]?.first?.owner ?: return@withLock Result.failure(RoutineError.NotFound())
        apply(requestId, Command(routineId, owner, name.trim(), exercises.toList(), expectedRevision, baselineExerciseIds.toList(), false))
    }
    override suspend fun deleteRoutine(routineId: String, expectedRevision: Long?, requestId: String, baselineExerciseIds: List<String>, name: String): Result<Boolean> = lock.withLock {
        val owner = routines[routineId]?.userId ?: receipts[requestId]?.first?.owner ?: return@withLock Result.failure(RoutineError.NotFound())
        apply(requestId, Command(routineId, owner, name, emptyList(), expectedRevision, baselineExerciseIds.toList(), true)).map { true }
    }
    private fun apply(requestId: String, command: Command): Result<WorkoutTemplate> {
        receipts[requestId]?.let { (original, receipt) ->
            return if (original == command) Result.success(receipt)
            else Result.failure(RoutineError.ValidationError("Request payload changed"))
        }
        if (command.name.length !in 2..100 || command.exercises.size > 100 ||
            command.exercises.map { it.id }.distinct().size != command.exercises.size ||
            command.exercises.any { it.sets !in 1..20 || it.reps !in 1..100 || it.restSeconds !in 0..600 || !it.exercise.isActive || !it.exercise.supportsRepetitions }) {
            return Result.failure(RoutineError.ValidationError("Invalid routine name or prescription"))
        }
        val existing = routines[command.id]
        if (existing == null && command.revision != null) return Result.failure(RoutineError.NotFound())
        if (existing != null && command.revision != existing.revision) return Result.failure(RoutineError.RevisionConflict())
        val saved = WorkoutTemplate(command.id, command.owner, command.name, command.exercises, revision = (existing?.revision ?: 0L) + 1L)
        synchronized(routines) { if (command.deleted) routines.remove(command.id) else routines[command.id] = saved }
        receipts[requestId] = command to saved
        return Result.success(saved)
    }
}

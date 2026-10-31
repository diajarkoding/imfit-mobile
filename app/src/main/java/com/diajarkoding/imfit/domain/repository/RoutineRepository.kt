package com.diajarkoding.imfit.domain.repository

import com.diajarkoding.imfit.domain.model.TemplateExercise
import com.diajarkoding.imfit.domain.model.WorkoutTemplate
import java.util.UUID

sealed class RoutineError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class RevisionConflict(message: String = "Routine has been modified on the server. Please reload.") : RoutineError(message)
    class NotFound(message: String = "Routine not found.") : RoutineError(message)
    class ValidationError(message: String) : RoutineError(message)
    class Network(message: String, cause: Throwable? = null) : RoutineError(message, cause)
    class Unauthorized(message: String = "Authentication required.") : RoutineError(message)
    class ActiveSessionConflict(message: String = "Routine cannot be modified while a workout is active.") : RoutineError(message)
    class MissingExercises(val ids: List<String>) : RoutineError("Exercises unavailable: ${ids.joinToString()}")
    class Server(message: String, cause: Throwable? = null) : RoutineError(message, cause)
}

interface RoutineRepository {
    suspend fun getRoutines(userId: String): Result<List<WorkoutTemplate>>
    suspend fun getRoutineById(routineId: String): Result<WorkoutTemplate?>
    fun getCachedRoutine(userId: String, routineId: String): WorkoutTemplate? = null
    suspend fun createRoutine(
        userId: String,
        name: String,
        requestId: String = UUID.randomUUID().toString()
    ): Result<WorkoutTemplate>
    suspend fun saveRoutine(
        routineId: String,
        name: String,
        exercises: List<TemplateExercise>,
        expectedRevision: Long?,
        requestId: String = UUID.randomUUID().toString(),
        baselineExerciseIds: List<String> = emptyList()
    ): Result<WorkoutTemplate>
    suspend fun deleteRoutine(
        routineId: String,
        expectedRevision: Long?,
        requestId: String = UUID.randomUUID().toString(),
        baselineExerciseIds: List<String> = emptyList(),
        name: String = "Routine"
    ): Result<Boolean>
}

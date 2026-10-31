package com.diajarkoding.imfit.data.recovery

import androidx.room.withTransaction
import com.diajarkoding.imfit.data.local.database.IMFITDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton
interface LegacyRoutineRecovery {
    fun observeCount(ownerId: String): Flow<Int>
    suspend fun export(ownerId: String): String
}

@Singleton
class RoomLegacyRoutineRecovery @Inject constructor(private val database: IMFITDatabase) : LegacyRoutineRecovery {
    override fun observeCount(ownerId: String): Flow<Int> = database.invalidationTracker
        .createFlow("workout_templates", "template_exercises")
        .map { database.workoutTemplateDao().countQuarantinedTemplates(ownerId) }
        .distinctUntilChanged()
        .flowOn(Dispatchers.IO)
    /** Export does not acknowledge, mutate, revive, delete, or submit any legacy record. */
    override suspend fun export(ownerId: String): String = withContext(Dispatchers.IO) {
        database.withTransaction {
            buildJsonObject {
                put("format", "imfit-legacy-routine-recovery")
                put("version", 1)
                put("owner_id", ownerId)
                put("notice", "Preserved local changes, not synced. Review before manually recreating any routine. DELETE records are tombstones, not routines to restore.")
                put("routines", buildJsonArray {
                    database.workoutTemplateDao().getQuarantinedTemplates(ownerId).forEach { parent ->
                        add(buildJsonObject {
                            put("id", parent.id)
                            put("user_id", parent.userId)
                            put("name", parent.name)
                            put("is_deleted", parent.isDeleted)
                            put("sync_status", parent.syncStatus)
                            put("pending_operation", parent.pendingOperation?.let(::JsonPrimitive) ?: JsonNull)
                            put("created_at", parent.createdAt)
                            put("updated_at", parent.updatedAt)
                            put("exercises", buildJsonArray {
                                database.templateExerciseDao().getExercisesForTemplateList(parent.id).forEach { child ->
                                    add(buildJsonObject {
                                        put("template_id", child.templateId)
                                        put("exercise_id", child.exerciseId)
                                        put("order_index", child.orderIndex)
                                        put("sets", child.sets)
                                        put("reps", child.reps)
                                        put("rest_seconds", child.restSeconds)
                                        put("sync_status", child.syncStatus)
                                        put("pending_operation", child.pendingOperation?.let(::JsonPrimitive) ?: JsonNull)
                                        put("created_at", child.createdAt)
                                        put("updated_at", child.updatedAt)
                                        database.exerciseDao().getExerciseById(child.exerciseId)?.let { exercise ->
                                            put("catalog_snapshot", buildJsonObject {
                                                put("id", exercise.id)
                                                put("name", exercise.name)
                                                put("description", exercise.description)
                                                put("muscle_category_id", exercise.muscleCategoryId)
                                                put("image_url", exercise.imageUrl?.let(::JsonPrimitive) ?: JsonNull)
                                                put("is_active", exercise.isActive)
                                            })
                                        }
                                    })
                                }
                            })
                        })
                    }
                })
            }.toString()
        }
    }
}

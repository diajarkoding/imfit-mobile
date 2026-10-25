package com.diajarkoding.imfit.data.repository

import androidx.room.withTransaction
import com.diajarkoding.imfit.core.network.NetworkOperationException
import com.diajarkoding.imfit.data.local.database.IMFITDatabase
import com.diajarkoding.imfit.data.local.entity.TemplateExerciseEntity
import com.diajarkoding.imfit.data.local.entity.TemplateWriteIntentEntity
import com.diajarkoding.imfit.data.local.entity.WorkoutTemplateEntity
import com.diajarkoding.imfit.data.remote.ImfitAggregateRemoteDataSource
import com.diajarkoding.imfit.data.remote.TemplateWriteRequest
import com.diajarkoding.imfit.data.remote.templateWriteExercises
import com.diajarkoding.imfit.data.sync.RemoteWorkoutCoordinator
import com.diajarkoding.imfit.domain.model.TemplateExercise
import com.diajarkoding.imfit.domain.model.WorkoutTemplate
import com.diajarkoding.imfit.domain.repository.AuthRepository
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.OffsetDateTime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Network-required atomic template writes. No REST delete/reinsert or offline upload queue. */
@Singleton
class WorkoutTemplateRepository @Inject constructor(
    private val local: WorkoutLocalStore,
    private val coordinator: RemoteWorkoutCoordinator,
    private val remote: ImfitAggregateRemoteDataSource,
    private val auth: AuthRepository,
    private val database: IMFITDatabase,
) {
    private val access = AccountDataAccess(auth)
    private val json = Json { encodeDefaults = true }

    fun observeTemplates(owner: String) = local.observeTemplates(owner)
    fun observeTemplate(id: String) = local.observeTemplate(id)

    suspend fun getTemplates(userId: String): List<WorkoutTemplate> {
        require(userId.isNotBlank())
        return access.read(userId, emptyList()) { coordinator.refreshTemplates(it); local.getTemplates(it) }
    }

    suspend fun getTemplateById(templateId: String): WorkoutTemplate? {
        require(templateId.isNotBlank())
        return access.read(empty = null as WorkoutTemplate?) { coordinator.refreshTemplates(it); local.getTemplateById(templateId) }
    }

    suspend fun createTemplate(userId: String, name: String, exercises: List<TemplateExercise>): WorkoutTemplate {
        require(userId.isNotBlank())
        validateName(name)
        templateWriteExercises(exercises)
        check(auth.getLocalUserId() == userId) { "Authenticated owner required" }
        val key = key(userId, "create", name.trim())
        return write(userId, key, null, name.trim(), exercises, false)
    }

    suspend fun updateTemplate(templateId: String, name: String, exercises: List<TemplateExercise>): WorkoutTemplate? {
        require(templateId.isNotBlank())
        validateName(name)
        val owner = auth.getLocalUserId() ?: error("Authenticated owner required")
        return write(owner, key(owner, "edit", templateId), templateId, name.trim(), exercises, false)
    }

    suspend fun updateTemplateExercises(templateId: String, exercises: List<TemplateExercise>): WorkoutTemplate? {
        val current = local.getTemplateById(templateId) ?: return null
        return updateTemplate(templateId, current.name, exercises)
    }

    suspend fun updateTemplateExercise(templateId: String, exerciseId: String, sets: Int, reps: Int, restSeconds: Int): WorkoutTemplate? {
        require(sets in 1..20 && reps in 1..100 && restSeconds in 0..600)
        val current = local.getTemplateById(templateId) ?: return null
        if (current.exercises.none { it.id == exerciseId }) return null
        return updateTemplate(templateId, current.name, current.exercises.map {
            if (it.id == exerciseId) it.copy(sets = sets, reps = reps, restSeconds = restSeconds) else it
        })
    }

    suspend fun deleteTemplate(templateId: String): Boolean {
        val current = local.getTemplateById(templateId) ?: return false
        val owner = auth.getLocalUserId() ?: return false
        write(owner, key(owner, "edit", templateId), templateId, current.name, current.exercises, true)
        return true
    }

    private suspend fun write(owner: String, operationKey: String, id: String?, name: String,
        exercises: List<TemplateExercise>, deleted: Boolean): WorkoutTemplate {
        val payloadExercises = templateWriteExercises(exercises)
        try {
            return coordinator.withRemoteSession(owner) { credential ->
                val prepared = database.withTransaction {
                    coordinator.requireCurrentSession(credential)
                    val intentDao = database.templateWriteIntentDao()
                    val previous = intentDao.get(operationKey, owner)
                    val request = if (previous != null) {
                        json.decodeFromString<TemplateWriteRequest>(previous.payload).also {
                            check(it.ownerId == owner) { "Request belongs to another account" }
                            check(it.sameIntent(name, payloadExercises, deleted)) {
                                "Resolve the previous save before changing its draft"
                            }
                        }
                    } else {
                        val initial = id?.let { snapshot(it, owner) }
                        if (id != null) check(initial?.parent?.remoteRevision != null) {
                            "Refresh/review this template before editing; backend revision support is required"
                        }
                        TemplateWriteRequest(UUID.randomUUID().toString(), id ?: UUID.randomUUID().toString(),
                            initial?.parent?.remoteRevision, name, payloadExercises,
                            if (deleted) emptyList() else initial?.children.orEmpty().map { it.exerciseId }
                                .filterNot { old -> payloadExercises.any { it.exerciseId == old } }, deleted, owner)
                            .also { intentDao.insert(TemplateWriteIntentEntity(operationKey, owner, it.templateId, json.encodeToString(it))) }
                    }
                    request to snapshot(request.templateId, owner, allowAbsent = id == null)
                }
                val (request, original) = prepared
                coordinator.requireCurrentSession(credential)
                val receipt = remote.saveTemplate(request, credential.accessToken)
                check(receipt.requestId == request.requestId && receipt.templateId == request.templateId &&
                    receipt.exerciseCount == request.exercises.size && receipt.revision > (request.expectedRevision ?: -1)) {
                    "Unexpected save receipt; request retained for recovery"
                }
                val timestamp = OffsetDateTime.parse(receipt.serverUpdatedAt).toInstant().toEpochMilli()
                database.withTransaction {
                    coordinator.requireCurrentSession(credential)
                    val current = snapshot(request.templateId, owner, allowAbsent = true)
                    check(original == current) { "Local template changed while saving; recovery request retained" }
                    if ((current?.parent?.remoteRevision ?: -1) <= receipt.revision) {
                        database.workoutTemplateDao().insertTemplate(WorkoutTemplateEntity(request.templateId, owner, name,
                            isDeleted = deleted, syncStatus = "SYNCED", pendingOperation = null,
                            createdAt = current?.parent?.createdAt ?: timestamp, updatedAt = timestamp, remoteRevision = receipt.revision))
                        database.templateExerciseDao().deleteExercisesByTemplate(request.templateId)
                        database.templateExerciseDao().insertTemplateExercises(request.exercises.map {
                            TemplateExerciseEntity(request.templateId, it.exerciseId, it.orderIndex, it.sets, it.reps, it.restSeconds,
                                syncStatus = "SYNCED", pendingOperation = null, createdAt = timestamp, updatedAt = timestamp)
                        })
                    }
                    check(database.templateWriteIntentDao().acknowledge(operationKey, owner, json.encodeToString(request)) == 1)
                }
                WorkoutTemplate(request.templateId, owner, name, exercises,
                    createdAt = original?.parent?.createdAt ?: timestamp)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            throw NetworkOperationException("Template save failed. Your draft/request is retained; retry the same save or review the conflict.", failure)
        }
    }

    private suspend fun snapshot(id: String, owner: String, allowAbsent: Boolean = false): TemplateSnapshot? {
        val parent = database.workoutTemplateDao().getTemplateByIdIncludingDeleted(id)
        if (parent == null) { check(allowAbsent) { "Template unavailable" }; return null }
        check(parent.userId == owner && parent.syncStatus == "SYNCED" && parent.pendingOperation == null) {
            "Pending or foreign template requires review"
        }
        val children = database.templateExerciseDao().getExercisesForTemplateList(id).sortedBy { it.exerciseId }
        check(children.all { it.syncStatus == "SYNCED" && it.pendingOperation == null }) { "Pending children require review" }
        check(children.all { database.exerciseDao().getExerciseById(it.exerciseId) != null }) {
            "Incomplete catalog cache; refresh before editing so hidden children cannot be removed"
        }
        return TemplateSnapshot(parent, children)
    }

    private data class TemplateSnapshot(val parent: WorkoutTemplateEntity, val children: List<TemplateExerciseEntity>)
    private fun key(owner: String, operation: String, target: String) = UUID.nameUUIDFromBytes(
        "$owner\u001f$operation\u001f$target".toByteArray(Charsets.UTF_8)).toString()
    private fun validateName(name: String) { require(name.isNotBlank() && name.trim().length <= 100) }
}

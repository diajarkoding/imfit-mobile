package com.diajarkoding.imfit.data.repository

import android.os.SystemClock
import com.diajarkoding.imfit.data.local.dao.ExerciseDao
import com.diajarkoding.imfit.data.local.entity.ExerciseEntity
import com.diajarkoding.imfit.data.sync.SyncRunner
import com.diajarkoding.imfit.data.sync.SyncRunResult
import com.diajarkoding.imfit.domain.model.Exercise
import com.diajarkoding.imfit.domain.model.MuscleCategory
import com.diajarkoding.imfit.domain.repository.ExerciseRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Room is authoritative for presentation. Demo records belong only to DemoExerciseRepository. */
@Singleton
class ExerciseRepositoryImpl @Inject constructor(
    private val exerciseDao: ExerciseDao,
    private val syncRunner: SyncRunner
) : ExerciseRepository {
    private val refreshMutex = Mutex()
    private var lastRefreshTimestamp: Long? = null

    override fun observeExercises() = exerciseDao.getAllActiveExercises()
        .map { records -> records.map(ExerciseEntity::toExercise).distinctBy { it.id } }.distinctUntilChanged()

    override suspend fun refreshExercises(force: Boolean): Boolean = refreshMutex.withLock {
        val now = SystemClock.elapsedRealtime()
        if (!force && lastRefreshTimestamp?.let { now - it < 30_000L } == true) return@withLock false
        try {
            (syncRunner.refreshMasterData() == SyncRunResult.SUCCESS).also {
                if (it) lastRefreshTimestamp = SystemClock.elapsedRealtime()
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { false } // Keep observable cached data; never substitute synthetic records.
    }

    override suspend fun getAllExercises() = exerciseDao.getAllActiveExercisesList().map(ExerciseEntity::toExercise)
    override suspend fun getExercisesByCategory(category: MuscleCategory) =
        exerciseDao.getExercisesByCategoryList(category.ordinal + 1).map(ExerciseEntity::toExercise)
    override suspend fun getExerciseById(id: String) = exerciseDao.getExerciseById(id)?.toExercise()
    override suspend fun searchExercises(query: String) = if (query.isBlank()) getAllExercises()
        else exerciseDao.searchExercisesList(query).map(ExerciseEntity::toExercise)
}

private fun ExerciseEntity.toExercise() = Exercise(id, name,
    MuscleCategory.entries.getOrNull(muscleCategoryId - 1) ?: MuscleCategory.CHEST, description, imageUrl)

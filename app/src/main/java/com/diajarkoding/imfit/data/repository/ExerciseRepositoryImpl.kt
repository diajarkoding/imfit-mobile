package com.diajarkoding.imfit.data.repository

import androidx.paging.Pager
import androidx.paging.map
import com.diajarkoding.imfit.data.local.dao.ExerciseDao
import com.diajarkoding.imfit.data.local.dao.roomQuery
import com.diajarkoding.imfit.data.local.entity.ExerciseEntity
import com.diajarkoding.imfit.data.sync.SyncRunner
import com.diajarkoding.imfit.data.sync.SyncRunResult
import com.diajarkoding.imfit.domain.model.Exercise
import com.diajarkoding.imfit.domain.model.ExerciseCatalogFilter
import com.diajarkoding.imfit.domain.model.MuscleCategory
import com.diajarkoding.imfit.domain.repository.ExerciseRepository
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Room is authoritative for presentation. Demo records belong only to DemoExerciseRepository. */
@Singleton
class ExerciseRepositoryImpl @Inject constructor(
    private val exerciseDao: ExerciseDao,
    private val syncRunner: SyncRunner
) : ExerciseRepository {
    private val refreshGate = CatalogRefreshGate()

    override fun pageExercises(filter: ExerciseCatalogFilter) = Pager(
        exerciseCatalogPagingConfig(),
        pagingSourceFactory = { exerciseDao.pagingSource(filter.roomQuery()) }
    ).flow.map { page -> page.map(ExerciseEntity::toExercise) }

    override fun observeExercises() = exerciseDao.getAllActiveExercises()
        .map { records -> records.map(ExerciseEntity::toExercise).distinctBy { it.id } }.distinctUntilChanged()

    override suspend fun refreshExercises(force: Boolean): Boolean = refreshGate.refresh(force) {
        syncRunner.refreshMasterData() == SyncRunResult.SUCCESS
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

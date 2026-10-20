package com.diajarkoding.imfit.data.repository

import android.util.Log
import com.diajarkoding.imfit.data.local.FakeExerciseDataSource
import com.diajarkoding.imfit.data.local.dao.ExerciseDao
import com.diajarkoding.imfit.data.sync.SyncRunner
import com.diajarkoding.imfit.data.sync.SyncRunResult
import com.diajarkoding.imfit.domain.model.Exercise
import com.diajarkoding.imfit.domain.model.MuscleCategory
import com.diajarkoding.imfit.domain.repository.ExerciseRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExerciseRepositoryImpl @Inject constructor(
    private val exerciseDao: ExerciseDao,
    private val syncRunner: SyncRunner
) : ExerciseRepository {

    private var lastRefreshTimestamp: Long = 0L
    private val refreshCooldownMs: Long = 5 * 60 * 1000L // 5 minutes cooldown

    override suspend fun refreshExercises(force: Boolean): Boolean = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!force && (now - lastRefreshTimestamp < refreshCooldownMs)) {
            return@withContext false
        }
        try {
            val result = syncRunner.run(null)
            if (result == SyncRunResult.SUCCESS) {
                lastRefreshTimestamp = now
                Log.d("ExerciseRepository", "Refreshed exercises via sync runner")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.w("ExerciseRepository", "Failed to refresh exercises: ${e.message}")
            false
        }
    }

    override suspend fun getAllExercises(): List<Exercise> {
        return try {
            val localExercises = exerciseDao.getAllActiveExercisesList()
            
            if (localExercises.isNotEmpty()) {
                val exercises = localExercises.map { entity ->
                    val muscleCategory = MuscleCategory.entries.getOrNull(entity.muscleCategoryId - 1)
                        ?: MuscleCategory.CHEST
                    Exercise(
                        id = entity.id,
                        name = entity.name,
                        muscleCategory = muscleCategory,
                        description = entity.description,
                        imageUrl = entity.imageUrl
                    )
                }.distinctBy { it.id }
                exercises
            } else {
                // Fallback to fake data if local DB is empty (initial sync not done)
                Log.w("ExerciseRepository", "Local DB empty, using fake data")
                FakeExerciseDataSource.exercises.distinctBy { it.id }
            }
        } catch (e: Exception) {
            Log.e("ExerciseRepository", "Error fetching exercises from local: ${e.message}", e)
            FakeExerciseDataSource.exercises.distinctBy { it.id }
        }
    }

    override suspend fun getExercisesByCategory(category: MuscleCategory): List<Exercise> {
        val categoryId = MuscleCategory.entries.indexOf(category) + 1

        return try {
            val localExercises = exerciseDao.getExercisesByCategoryList(categoryId)
            
            if (localExercises.isNotEmpty()) {
                localExercises.map { entity ->
                    Exercise(
                        id = entity.id,
                        name = entity.name,
                        muscleCategory = category,
                        description = entity.description,
                        imageUrl = entity.imageUrl
                    )
                }.distinctBy { it.id }
            } else {
                FakeExerciseDataSource.getExercisesByCategory(category).distinctBy { it.id }
            }
        } catch (e: Exception) {
            Log.e("ExerciseRepository", "Error fetching exercises by category from local: ${e.message}", e)
            FakeExerciseDataSource.getExercisesByCategory(category).distinctBy { it.id }
        }
    }

    override suspend fun getExerciseById(id: String): Exercise? {
        return try {
            val entity = exerciseDao.getExerciseById(id)
            entity?.let {
                val muscleCategory = MuscleCategory.entries.getOrNull(it.muscleCategoryId - 1)
                    ?: MuscleCategory.CHEST
                Exercise(
                    id = it.id,
                    name = it.name,
                    muscleCategory = muscleCategory,
                    description = it.description,
                    imageUrl = it.imageUrl
                )
            } ?: FakeExerciseDataSource.getExerciseById(id)
        } catch (e: Exception) {
            Log.e("ExerciseRepository", "Error fetching exercise by ID from local: ${e.message}", e)
            FakeExerciseDataSource.getExerciseById(id)
        }
    }

    override suspend fun searchExercises(query: String): List<Exercise> {
        if (query.isBlank()) return getAllExercises()

        return try {
            val localExercises = exerciseDao.searchExercisesList(query)
            localExercises.map { entity ->
                val muscleCategory = MuscleCategory.entries.getOrNull(entity.muscleCategoryId - 1)
                    ?: MuscleCategory.CHEST
                Exercise(
                    id = entity.id,
                    name = entity.name,
                    muscleCategory = muscleCategory,
                    description = entity.description,
                    imageUrl = entity.imageUrl
                )
            }.distinctBy { it.id }
        } catch (e: Exception) {
            Log.e("ExerciseRepository", "Error searching exercises from local: ${e.message}", e)
            emptyList()
        }
    }

    fun clearCache() {
        // No-op retained for backwards compatibility
    }

    // Helper function to ensure uniqueness
    private fun <T> List<T>.ensureDistinctById(selector: (T) -> String): List<T> {
        val seen = mutableSetOf<String>()
        return this.filter {
            val id = selector(it)
            if (seen.contains(id)) false else {
                seen.add(id)
                true
            }
        }
    }
}

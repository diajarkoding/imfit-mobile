package com.diajarkoding.imfit.data.repository

import android.util.Log
import com.diajarkoding.imfit.data.local.FakeExerciseDataSource
import com.diajarkoding.imfit.data.local.dao.ExerciseDao
import com.diajarkoding.imfit.data.remote.dto.ExerciseDto
import com.diajarkoding.imfit.data.remote.dto.toDomain
import com.diajarkoding.imfit.domain.model.Exercise
import com.diajarkoding.imfit.domain.model.MuscleCategory
import com.diajarkoding.imfit.domain.repository.ExerciseRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExerciseRepositoryImpl @Inject constructor(
    private val supabaseClient: SupabaseClient,
    private val exerciseDao: ExerciseDao
) : ExerciseRepository {

    private var cachedExercises: List<Exercise>? = null

    override suspend fun getAllExercises(): List<Exercise> {
        cachedExercises?.let { return it.ensureDistinctById { exercise -> exercise.id } }
        
        // LOCAL-FIRST: Read from Room database, SyncManager handles remote sync
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
                cachedExercises = exercises
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
        
        // LOCAL-FIRST: Read from Room database
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
        // LOCAL-FIRST: Read from Room database
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
            }
        } catch (e: Exception) {
            Log.e("ExerciseRepository", "Error fetching exercise by ID from local: ${e.message}", e)
            FakeExerciseDataSource.getExerciseById(id)
        }
    }

    override suspend fun searchExercises(query: String): List<Exercise> {
        if (query.isBlank()) return getAllExercises()
        
        // LOCAL-FIRST: Read from Room database
        return try {
            val localExercises = exerciseDao.searchExercisesList(query)
            
            if (localExercises.isNotEmpty()) {
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
            } else {
                FakeExerciseDataSource.searchExercises(query).distinctBy { it.id }
            }
        } catch (e: Exception) {
            Log.e("ExerciseRepository", "Error searching exercises from local: ${e.message}", e)
            FakeExerciseDataSource.searchExercises(query).distinctBy { it.id }
        }
    }

    fun clearCache() {
        cachedExercises = null
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

package com.diajarkoding.imfit.data.repository

import android.util.Log
import com.diajarkoding.imfit.data.local.FakeExerciseDataSource
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
    private val supabaseClient: SupabaseClient
) : ExerciseRepository {

    private var cachedExercises: List<Exercise>? = null

    override suspend fun getAllExercises(): List<Exercise> {
        cachedExercises?.let { return it }
        
        return try {
            val exercises = supabaseClient.postgrest.from("exercises")
                .select {
                    filter { eq("is_active", true) }
                }
                .decodeList<ExerciseDto>()
                .map { it.toDomain() }
            cachedExercises = exercises
            exercises
        } catch (e: Exception) {
            Log.e("ExerciseRepository", "Error fetching exercises: ${e.message}", e)
            FakeExerciseDataSource.exercises
        }
    }

    override suspend fun getExercisesByCategory(category: MuscleCategory): List<Exercise> {
        val categoryId = MuscleCategory.entries.indexOf(category) + 1
        
        return try {
            supabaseClient.postgrest.from("exercises")
                .select {
                    filter {
                        eq("muscle_category_id", categoryId)
                        eq("is_active", true)
                    }
                }
                .decodeList<ExerciseDto>()
                .map { it.toDomain() }
        } catch (e: Exception) {
            Log.e("ExerciseRepository", "Error fetching exercises by category: ${e.message}", e)
            FakeExerciseDataSource.getExercisesByCategory(category)
        }
    }

    override suspend fun getExerciseById(id: String): Exercise? {
        return try {
            supabaseClient.postgrest.from("exercises")
                .select {
                    filter { eq("id", id) }
                }
                .decodeSingleOrNull<ExerciseDto>()
                ?.toDomain()
        } catch (e: Exception) {
            Log.e("ExerciseRepository", "Error fetching exercise by ID: ${e.message}", e)
            FakeExerciseDataSource.getExerciseById(id)
        }
    }

    override suspend fun searchExercises(query: String): List<Exercise> {
        if (query.isBlank()) return getAllExercises()
        
        return try {
            supabaseClient.postgrest.from("exercises")
                .select {
                    filter {
                        ilike("name", "%$query%")
                        eq("is_active", true)
                    }
                }
                .decodeList<ExerciseDto>()
                .map { it.toDomain() }
        } catch (e: Exception) {
            Log.e("ExerciseRepository", "Error searching exercises: ${e.message}", e)
            FakeExerciseDataSource.searchExercises(query)
        }
    }

    fun clearCache() {
        cachedExercises = null
    }
}

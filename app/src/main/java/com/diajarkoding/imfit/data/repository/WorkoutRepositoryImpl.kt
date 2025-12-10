package com.diajarkoding.imfit.data.repository

import android.util.Log
import com.diajarkoding.imfit.data.local.FakeWorkoutDataSource
import com.diajarkoding.imfit.data.remote.dto.ExerciseDto
import com.diajarkoding.imfit.data.remote.dto.ExerciseLogDto
import com.diajarkoding.imfit.data.remote.dto.TemplateExerciseDto
import com.diajarkoding.imfit.data.remote.dto.WorkoutLogDto
import com.diajarkoding.imfit.data.remote.dto.WorkoutSetDto
import com.diajarkoding.imfit.data.remote.dto.WorkoutTemplateDto
import com.diajarkoding.imfit.data.remote.dto.toDomain
import com.diajarkoding.imfit.domain.model.ExerciseLog
import com.diajarkoding.imfit.domain.model.TemplateExercise
import com.diajarkoding.imfit.domain.model.WorkoutLog
import com.diajarkoding.imfit.domain.model.WorkoutSession
import com.diajarkoding.imfit.domain.model.WorkoutSet
import com.diajarkoding.imfit.domain.model.WorkoutTemplate
import com.diajarkoding.imfit.domain.repository.WorkoutRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkoutRepositoryImpl @Inject constructor(
    private val supabaseClient: SupabaseClient
) : WorkoutRepository {

    private var activeSession: WorkoutSession? = null

    override suspend fun getTemplates(userId: String): List<WorkoutTemplate> {
        return try {
            supabaseClient.postgrest.from("workout_templates")
                .select(Columns.raw("*, template_exercises(*, exercises(*))")) {
                    filter {
                        eq("user_id", userId)
                        eq("is_deleted", false)
                    }
                }
                .decodeList<WorkoutTemplateDto>()
                .map { it.toDomain() }
        } catch (e: Exception) {
            Log.e("WorkoutRepository", "Error fetching templates: ${e.message}", e)
            FakeWorkoutDataSource.getTemplates(userId)
        }
    }

    override suspend fun getTemplateById(templateId: String): WorkoutTemplate? {
        return try {
            supabaseClient.postgrest.from("workout_templates")
                .select(Columns.raw("*, template_exercises(*, exercises(*))")) {
                    filter {
                        eq("id", templateId)
                        eq("is_deleted", false)
                    }
                }
                .decodeSingleOrNull<WorkoutTemplateDto>()
                ?.toDomain()
        } catch (e: Exception) {
            Log.e("WorkoutRepository", "Error fetching template: ${e.message}", e)
            FakeWorkoutDataSource.getTemplateById(templateId)
        }
    }

    override suspend fun createTemplate(userId: String, name: String, exercises: List<TemplateExercise>): WorkoutTemplate {
        return try {
            val templateId = UUID.randomUUID().toString()
            
            val templateDto = CreateTemplateDto(
                id = templateId,
                userId = userId,
                name = name
            )
            
            supabaseClient.postgrest.from("workout_templates")
                .insert(templateDto)
            
            exercises.forEachIndexed { index, exercise ->
                val exerciseDto = TemplateExerciseDto(
                    templateId = templateId,
                    exerciseId = exercise.exercise.id,
                    orderIndex = index,
                    sets = exercise.sets,
                    reps = exercise.reps,
                    restSeconds = exercise.restSeconds
                )
                supabaseClient.postgrest.from("template_exercises")
                    .insert(exerciseDto)
            }
            
            getTemplateById(templateId) ?: WorkoutTemplate(
                id = templateId,
                userId = userId,
                name = name,
                exercises = exercises
            )
        } catch (e: Exception) {
            Log.e("WorkoutRepository", "Error creating template: ${e.message}", e)
            FakeWorkoutDataSource.createTemplate(userId, name, exercises)
        }
    }

    override suspend fun updateTemplate(templateId: String, name: String, exercises: List<TemplateExercise>): WorkoutTemplate? {
        return try {
            supabaseClient.postgrest.from("workout_templates")
                .update({
                    set("name", name)
                    set("updated_at", OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                }) {
                    filter { eq("id", templateId) }
                }
            
            supabaseClient.postgrest.from("template_exercises")
                .delete {
                    filter { eq("template_id", templateId) }
                }
            
            exercises.forEachIndexed { index, exercise ->
                val exerciseDto = TemplateExerciseDto(
                    templateId = templateId,
                    exerciseId = exercise.exercise.id,
                    orderIndex = index,
                    sets = exercise.sets,
                    reps = exercise.reps,
                    restSeconds = exercise.restSeconds
                )
                supabaseClient.postgrest.from("template_exercises")
                    .insert(exerciseDto)
            }
            
            getTemplateById(templateId)
        } catch (e: Exception) {
            Log.e("WorkoutRepository", "Error updating template: ${e.message}", e)
            FakeWorkoutDataSource.updateTemplate(templateId, name, exercises)
        }
    }

    override suspend fun updateTemplateExercises(templateId: String, exercises: List<TemplateExercise>): WorkoutTemplate? {
        val template = getTemplateById(templateId) ?: return null
        return updateTemplate(templateId, template.name, exercises)
    }

    override suspend fun updateTemplateExercise(templateId: String, exerciseId: String, sets: Int, reps: Int, restSeconds: Int): WorkoutTemplate? {
        return try {
            supabaseClient.postgrest.from("template_exercises")
                .update({
                    set("sets", sets)
                    set("reps", reps)
                    set("rest_seconds", restSeconds)
                }) {
                    filter {
                        eq("template_id", templateId)
                        eq("exercise_id", exerciseId)
                    }
                }
            getTemplateById(templateId)
        } catch (e: Exception) {
            Log.e("WorkoutRepository", "Error updating template exercise: ${e.message}", e)
            FakeWorkoutDataSource.updateTemplateExercise(templateId, exerciseId, sets, reps, restSeconds)
        }
    }

    override suspend fun deleteTemplate(templateId: String): Boolean {
        return try {
            supabaseClient.postgrest.from("workout_templates")
                .update({
                    set("is_deleted", true)
                }) {
                    filter { eq("id", templateId) }
                }
            true
        } catch (e: Exception) {
            Log.e("WorkoutRepository", "Error deleting template: ${e.message}", e)
            FakeWorkoutDataSource.deleteTemplate(templateId)
        }
    }

    override suspend fun startWorkout(template: WorkoutTemplate): WorkoutSession {
        val exerciseLogs = template.exercises.map { templateExercise ->
            ExerciseLog(
                exercise = templateExercise.exercise,
                sets = (1..templateExercise.sets).map { setNumber ->
                    WorkoutSet(
                        setNumber = setNumber,
                        weight = 0f,
                        reps = templateExercise.reps,
                        isCompleted = false
                    )
                }
            )
        }
        
        activeSession = WorkoutSession(
            id = UUID.randomUUID().toString(),
            templateId = template.id,
            templateName = template.name,
            startTime = System.currentTimeMillis(),
            exerciseLogs = exerciseLogs
        )
        
        return activeSession!!
    }

    override suspend fun getActiveSession(): WorkoutSession? {
        return activeSession
    }

    override suspend fun updateActiveSession(session: WorkoutSession) {
        activeSession = session
    }

    override suspend fun finishWorkout(): WorkoutLog? {
        val session = activeSession ?: return null
        val endTime = System.currentTimeMillis()
        
        return try {
            val userId = supabaseClient.auth.currentUserOrNull()?.id
                ?: return FakeWorkoutDataSource.finishWorkout()
            
            val workoutLogId = UUID.randomUUID().toString()
            val startDateTime = OffsetDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(session.startTime),
                ZoneOffset.UTC
            )
            val endDateTime = OffsetDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(endTime),
                ZoneOffset.UTC
            )
            
            val workoutLogDto = CreateWorkoutLogDto(
                id = workoutLogId,
                userId = userId,
                templateId = session.templateId,
                templateName = session.templateName,
                date = startDateTime.toLocalDate().toString(),
                startTime = startDateTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                endTime = endDateTime.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                totalVolume = session.totalVolume.toDouble(),
                totalSets = session.totalCompletedSets,
                totalReps = session.exerciseLogs.sumOf { log -> log.sets.filter { it.isCompleted }.sumOf { it.reps } }
            )
            
            supabaseClient.postgrest.from("workout_logs")
                .insert(workoutLogDto)
            
            session.exerciseLogs.forEachIndexed { index, exerciseLog ->
                val exerciseLogId = UUID.randomUUID().toString()
                val exerciseLogDto = CreateExerciseLogDto(
                    id = exerciseLogId,
                    workoutLogId = workoutLogId,
                    exerciseId = exerciseLog.exercise.id,
                    exerciseName = exerciseLog.exercise.name,
                    muscleCategory = exerciseLog.exercise.muscleCategory.name,
                    orderIndex = index,
                    totalVolume = exerciseLog.totalVolume.toDouble()
                )
                
                supabaseClient.postgrest.from("exercise_logs")
                    .insert(exerciseLogDto)
                
                exerciseLog.sets.forEach { set ->
                    val setDto = WorkoutSetDto(
                        exerciseLogId = exerciseLogId,
                        setNumber = set.setNumber,
                        weight = set.weight.toDouble(),
                        reps = set.reps,
                        isCompleted = set.isCompleted
                    )
                    supabaseClient.postgrest.from("workout_sets")
                        .insert(setDto)
                }
            }
            
            activeSession = null
            
            WorkoutLog(
                id = workoutLogId,
                userId = userId,
                templateName = session.templateName,
                date = session.startTime,
                startTime = session.startTime,
                endTime = endTime,
                totalVolume = session.totalVolume,
                exerciseLogs = session.exerciseLogs
            )
        } catch (e: Exception) {
            Log.e("WorkoutRepository", "Error finishing workout: ${e.message}", e)
            FakeWorkoutDataSource.finishWorkout()
        }
    }

    override suspend fun cancelWorkout() {
        activeSession = null
    }

    override suspend fun getWorkoutLogs(userId: String): List<WorkoutLog> {
        return try {
            supabaseClient.postgrest.from("workout_logs")
                .select(Columns.raw("*, exercise_logs(*, exercises(*), workout_sets(*))")) {
                    filter { eq("user_id", userId) }
                    order("date", io.github.jan.supabase.postgrest.query.Order.DESCENDING)
                }
                .decodeList<WorkoutLogDto>()
                .map { it.toDomain() }
        } catch (e: Exception) {
            Log.e("WorkoutRepository", "Error fetching workout logs: ${e.message}", e)
            FakeWorkoutDataSource.getWorkoutLogs(userId)
        }
    }

    override suspend fun getWorkoutLogById(logId: String): WorkoutLog? {
        return try {
            supabaseClient.postgrest.from("workout_logs")
                .select(Columns.raw("*, exercise_logs(*, exercises(*), workout_sets(*))")) {
                    filter { eq("id", logId) }
                }
                .decodeSingleOrNull<WorkoutLogDto>()
                ?.toDomain()
        } catch (e: Exception) {
            Log.e("WorkoutRepository", "Error fetching workout log: ${e.message}", e)
            FakeWorkoutDataSource.getWorkoutLogById(logId)
        }
    }

    override suspend fun getLastWorkoutLog(userId: String): WorkoutLog? {
        return try {
            supabaseClient.postgrest.from("workout_logs")
                .select(Columns.raw("*, exercise_logs(*, exercises(*), workout_sets(*))")) {
                    filter { eq("user_id", userId) }
                    order("date", io.github.jan.supabase.postgrest.query.Order.DESCENDING)
                    limit(1)
                }
                .decodeSingleOrNull<WorkoutLogDto>()
                ?.toDomain()
        } catch (e: Exception) {
            Log.e("WorkoutRepository", "Error fetching last workout log: ${e.message}", e)
            FakeWorkoutDataSource.getLastWorkoutLog(userId)
        }
    }
}

@Serializable
private data class CreateTemplateDto(
    val id: String,
    @SerialName("user_id")
    val userId: String,
    val name: String
)

@Serializable
private data class CreateWorkoutLogDto(
    val id: String,
    @SerialName("user_id")
    val userId: String,
    @SerialName("template_id")
    val templateId: String?,
    @SerialName("template_name")
    val templateName: String,
    val date: String,
    @SerialName("start_time")
    val startTime: String,
    @SerialName("end_time")
    val endTime: String,
    @SerialName("total_volume")
    val totalVolume: Double,
    @SerialName("total_sets")
    val totalSets: Int,
    @SerialName("total_reps")
    val totalReps: Int
)

@Serializable
private data class CreateExerciseLogDto(
    val id: String,
    @SerialName("workout_log_id")
    val workoutLogId: String,
    @SerialName("exercise_id")
    val exerciseId: String,
    @SerialName("exercise_name")
    val exerciseName: String,
    @SerialName("muscle_category")
    val muscleCategory: String,
    @SerialName("order_index")
    val orderIndex: Int,
    @SerialName("total_volume")
    val totalVolume: Double
)

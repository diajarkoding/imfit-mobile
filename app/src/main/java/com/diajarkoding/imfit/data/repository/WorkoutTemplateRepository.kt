 package com.diajarkoding.imfit.data.repository
 
 import android.util.Log
 import com.diajarkoding.imfit.core.constants.WorkoutConstants
 import com.diajarkoding.imfit.core.network.NetworkMonitor
 import com.diajarkoding.imfit.core.network.NetworkOperationException
 import com.diajarkoding.imfit.core.network.NoNetworkException
 import com.diajarkoding.imfit.data.local.dao.ExerciseDao
 import com.diajarkoding.imfit.data.local.dao.TemplateExerciseDao
 import com.diajarkoding.imfit.data.local.dao.WorkoutTemplateDao
 import com.diajarkoding.imfit.data.local.entity.TemplateExerciseEntity
 import com.diajarkoding.imfit.data.local.entity.WorkoutTemplateEntity
 import com.diajarkoding.imfit.data.remote.dto.TemplateExerciseDto
 import com.diajarkoding.imfit.data.remote.dto.WorkoutTemplateDto
 import com.diajarkoding.imfit.data.remote.dto.toDomain
 import com.diajarkoding.imfit.domain.model.Exercise
 import com.diajarkoding.imfit.domain.model.MuscleCategory
 import com.diajarkoding.imfit.domain.model.TemplateExercise
 import com.diajarkoding.imfit.domain.model.WorkoutTemplate
 import io.github.jan.supabase.SupabaseClient
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
 
 /**
  * Repository for managing workout templates.
  * Implements online-first strategy with local caching.
  */
 @Singleton
 class WorkoutTemplateRepository @Inject constructor(
     private val supabaseClient: SupabaseClient,
     private val workoutTemplateDao: WorkoutTemplateDao,
     private val templateExerciseDao: TemplateExerciseDao,
     private val exerciseDao: ExerciseDao,
     private val networkMonitor: NetworkMonitor
 ) {
     
     companion object {
         private const val TAG = "WorkoutTemplateRepo"
     }
     
     // ==================== READ OPERATIONS ====================
     
     /**
      * Gets all templates for a user.
      * Tries Supabase first, falls back to local cache on failure.
      */
     suspend fun getTemplates(userId: String): List<WorkoutTemplate> {
         require(userId.isNotBlank()) { "User ID cannot be blank" }
         
         return try {
             val remoteTemplates = fetchTemplatesFromSupabase(userId)
             cacheTemplates(remoteTemplates, userId)
             Log.d(TAG, "Fetched ${remoteTemplates.size} templates from Supabase")
             remoteTemplates
         } catch (e: Exception) {
             Log.w(TAG, "Failed to fetch templates from Supabase, using cache: ${e.message}")
             getTemplatesFromCache(userId)
         }
     }
     
     /**
      * Gets a single template by ID.
      * Tries Supabase first, falls back to local cache on failure.
      */
     suspend fun getTemplateById(templateId: String): WorkoutTemplate? {
         require(templateId.isNotBlank()) { "Template ID cannot be blank" }
         
         return try {
             val response = supabaseClient.postgrest
                 .from("workout_templates")
                 .select(Columns.raw("*, template_exercises(*, exercises(*))")) {
                     filter {
                         eq("id", templateId)
                         eq("is_deleted", false)
                     }
                 }
                 .decodeSingleOrNull<WorkoutTemplateDto>()
             
             val template = response?.toDomain()
             template?.let { cacheTemplate(it) }
             template
         } catch (e: Exception) {
             Log.w(TAG, "Failed to fetch template from Supabase, using cache: ${e.message}")
             getTemplateByIdFromCache(templateId)
         }
     }
     
     // ==================== WRITE OPERATIONS ====================
     
     /**
      * Creates a new workout template.
      * Requires network connectivity.
      * 
      * @throws NoNetworkException if offline
      * @throws NetworkOperationException if creation fails
      * @throws IllegalArgumentException if name is blank or too long
      */
     suspend fun createTemplate(
         userId: String,
         name: String,
         exercises: List<TemplateExercise>
     ): WorkoutTemplate {
         // Validation
         require(userId.isNotBlank()) { "User ID cannot be blank" }
         require(name.isNotBlank()) { "Template name cannot be blank" }
         require(name.length <= WorkoutConstants.MAX_TEMPLATE_NAME_LENGTH) { 
             "Template name cannot exceed ${WorkoutConstants.MAX_TEMPLATE_NAME_LENGTH} characters" 
         }
         
         if (!networkMonitor.isOnline) {
             throw NoNetworkException("No internet connection. Please check your network and try again.")
         }
         
         val templateId = UUID.randomUUID().toString()
         val now = System.currentTimeMillis()
         
         try {
             // Create on Supabase first
             val templateDto = CreateTemplateDto(
                 id = templateId,
                 userId = userId,
                 name = name
             )
             supabaseClient.postgrest.from("workout_templates").insert(templateDto)
             
             // Insert exercises
             exercises.forEachIndexed { index, exercise ->
                 val exerciseDto = TemplateExerciseDto(
                     templateId = templateId,
                     exerciseId = exercise.exercise.id,
                     orderIndex = index,
                     sets = exercise.sets,
                     reps = exercise.reps,
                     restSeconds = exercise.restSeconds
                 )
                 supabaseClient.postgrest.from("template_exercises").insert(exerciseDto)
             }
             
             Log.d(TAG, "Created template on Supabase: $templateId")
             
             // Cache locally
             cacheNewTemplate(templateId, userId, name, exercises, now)
             
         } catch (e: Exception) {
             Log.e(TAG, "Failed to create template on Supabase: ${e.message}", e)
             throw NetworkOperationException("Failed to create template. Please check your connection.", e)
         }
         
         return WorkoutTemplate(
             id = templateId,
             userId = userId,
             name = name,
             exercises = exercises,
             createdAt = now
         )
     }
     
     /**
      * Updates an existing workout template.
      * Requires network connectivity.
      * 
      * @throws NoNetworkException if offline
      * @throws NetworkOperationException if update fails
      */
     suspend fun updateTemplate(
         templateId: String,
         name: String,
         exercises: List<TemplateExercise>
     ): WorkoutTemplate? {
         require(templateId.isNotBlank()) { "Template ID cannot be blank" }
         require(name.isNotBlank()) { "Template name cannot be blank" }
         
         if (!networkMonitor.isOnline) {
             throw NoNetworkException("No internet connection. Please check your network and try again.")
         }
         
         val now = System.currentTimeMillis()
         
         try {
             // Update on Supabase
             supabaseClient.postgrest.from("workout_templates")
                 .update({
                     set("name", name)
                     set("updated_at", OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                 }) {
                     filter { eq("id", templateId) }
                 }
             
             // Replace exercises
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
                 supabaseClient.postgrest.from("template_exercises").insert(exerciseDto)
             }
             
             Log.d(TAG, "Updated template on Supabase: $templateId")
             
             // Update local cache
             val existingTemplate = workoutTemplateDao.getTemplateById(templateId)
             if (existingTemplate != null) {
                 val updatedEntity = existingTemplate.copy(name = name, updatedAt = now)
                 workoutTemplateDao.updateTemplate(updatedEntity)
             }
             
             templateExerciseDao.deleteExercisesByTemplate(templateId)
             exercises.forEachIndexed { index, exercise ->
                 val templateExerciseEntity = TemplateExerciseEntity(
                     templateId = templateId,
                     exerciseId = exercise.exercise.id,
                     orderIndex = index,
                     sets = exercise.sets,
                     reps = exercise.reps,
                     restSeconds = exercise.restSeconds
                 )
                 templateExerciseDao.insertTemplateExercise(templateExerciseEntity)
             }
             
         } catch (e: Exception) {
             Log.e(TAG, "Failed to update template on Supabase: ${e.message}", e)
             throw NetworkOperationException("Failed to update template. Please check your connection.", e)
         }
         
         return getTemplateByIdFromCache(templateId)
     }
     
     /**
      * Updates a single exercise in a template.
      * Requires network connectivity.
      */
     suspend fun updateTemplateExercise(
         templateId: String,
         exerciseId: String,
         sets: Int,
         reps: Int,
         restSeconds: Int
     ): WorkoutTemplate? {
         require(templateId.isNotBlank()) { "Template ID cannot be blank" }
         require(exerciseId.isNotBlank()) { "Exercise ID cannot be blank" }
         require(sets > 0 && sets <= WorkoutConstants.MAX_SETS_PER_EXERCISE) { 
             "Sets must be between 1 and ${WorkoutConstants.MAX_SETS_PER_EXERCISE}" 
         }
         require(reps > 0 && reps <= WorkoutConstants.MAX_REPS_PER_SET) { 
             "Reps must be between 1 and ${WorkoutConstants.MAX_REPS_PER_SET}" 
         }
         require(restSeconds >= 0) { "Rest seconds cannot be negative" }
         
         if (!networkMonitor.isOnline) {
             throw NoNetworkException("No internet connection. Please check your network and try again.")
         }
         
         try {
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
             Log.d(TAG, "Updated exercise on Supabase: $exerciseId")
             
             // Update local cache
             val existingExercise = templateExerciseDao.getTemplateExercise(templateId, exerciseId)
             if (existingExercise != null) {
                 val updatedExercise = existingExercise.copy(
                     sets = sets,
                     reps = reps,
                     restSeconds = restSeconds,
                     updatedAt = System.currentTimeMillis()
                 )
                 templateExerciseDao.updateTemplateExercise(updatedExercise)
             }
         } catch (e: Exception) {
             Log.e(TAG, "Failed to update exercise on Supabase: ${e.message}", e)
             throw NetworkOperationException("Failed to update exercise. Please check your connection.", e)
         }
         
         return getTemplateByIdFromCache(templateId)
     }
     
     /**
      * Soft deletes a template.
      * Requires network connectivity.
      */
     suspend fun deleteTemplate(templateId: String): Boolean {
         require(templateId.isNotBlank()) { "Template ID cannot be blank" }
         
         if (!networkMonitor.isOnline) {
             throw NoNetworkException("No internet connection. Please check your network and try again.")
         }
         
         try {
             supabaseClient.postgrest.from("workout_templates")
                 .update({
                     set("is_deleted", true)
                 }) {
                     filter { eq("id", templateId) }
                 }
             Log.d(TAG, "Deleted template on Supabase: $templateId")
             
             // Update local cache
             val existingTemplate = workoutTemplateDao.getTemplateById(templateId)
             if (existingTemplate != null) {
                 val updatedTemplate = existingTemplate.copy(
                     isDeleted = true,
                     updatedAt = System.currentTimeMillis()
                 )
                 workoutTemplateDao.updateTemplate(updatedTemplate)
             }
             
             return true
         } catch (e: Exception) {
             Log.e(TAG, "Failed to delete template on Supabase: ${e.message}", e)
             throw NetworkOperationException("Failed to delete template. Please check your connection.", e)
         }
     }
     
     // ==================== PRIVATE HELPER METHODS ====================
     
     private suspend fun fetchTemplatesFromSupabase(userId: String): List<WorkoutTemplate> {
         val response = supabaseClient.postgrest
             .from("workout_templates")
             .select(Columns.raw("*, template_exercises(*, exercises(*))")) {
                 filter {
                     eq("user_id", userId)
                     eq("is_deleted", false)
                 }
             }
             .decodeList<WorkoutTemplateDto>()
         
         return response.map { it.toDomain() }
     }
     
     private suspend fun cacheTemplates(templates: List<WorkoutTemplate>, userId: String) {
         val templateIds = templates.map { it.id }.toSet()
         
         // Delete template exercises for templates we're updating
         templateIds.forEach { templateId ->
             templateExerciseDao.deleteExercisesByTemplate(templateId)
         }
         
         // Insert/update templates and their exercises
         templates.forEach { template ->
             val templateEntity = WorkoutTemplateEntity(
                 id = template.id,
                 userId = template.userId,
                 name = template.name,
                 isDeleted = false,
                 createdAt = template.createdAt,
                 updatedAt = System.currentTimeMillis()
             )
             workoutTemplateDao.insertTemplate(templateEntity)
             
             template.exercises.forEachIndexed { index, exercise ->
                 val exerciseEntity = TemplateExerciseEntity(
                     templateId = template.id,
                     exerciseId = exercise.exercise.id,
                     orderIndex = index,
                     sets = exercise.sets,
                     reps = exercise.reps,
                     restSeconds = exercise.restSeconds
                 )
                 templateExerciseDao.insertTemplateExercise(exerciseEntity)
             }
         }
         
         // Clean up templates that exist locally but not in remote
         val localTemplates = workoutTemplateDao.getTemplatesByUserList(userId)
         localTemplates.forEach { local ->
             if (local.id !in templateIds) {
                 workoutTemplateDao.deleteTemplate(local.id)
             }
         }
     }
     
     private suspend fun cacheTemplate(template: WorkoutTemplate) {
         val templateEntity = WorkoutTemplateEntity(
             id = template.id,
             userId = template.userId,
             name = template.name,
             isDeleted = false,
             createdAt = template.createdAt,
             updatedAt = System.currentTimeMillis()
         )
         workoutTemplateDao.insertTemplate(templateEntity)
         
         templateExerciseDao.deleteExercisesByTemplate(template.id)
         
         template.exercises.forEachIndexed { index, exercise ->
             val exerciseEntity = TemplateExerciseEntity(
                 templateId = template.id,
                 exerciseId = exercise.exercise.id,
                 orderIndex = index,
                 sets = exercise.sets,
                 reps = exercise.reps,
                 restSeconds = exercise.restSeconds
             )
             templateExerciseDao.insertTemplateExercise(exerciseEntity)
         }
     }
     
     private suspend fun cacheNewTemplate(
         templateId: String,
         userId: String,
         name: String,
         exercises: List<TemplateExercise>,
         timestamp: Long
     ) {
         val templateEntity = WorkoutTemplateEntity(
             id = templateId,
             userId = userId,
             name = name,
             isDeleted = false,
             createdAt = timestamp,
             updatedAt = timestamp
         )
         workoutTemplateDao.insertTemplate(templateEntity)
         
         exercises.forEachIndexed { index, exercise ->
             val templateExerciseEntity = TemplateExerciseEntity(
                 templateId = templateId,
                 exerciseId = exercise.exercise.id,
                 orderIndex = index,
                 sets = exercise.sets,
                 reps = exercise.reps,
                 restSeconds = exercise.restSeconds
             )
             templateExerciseDao.insertTemplateExercise(templateExerciseEntity)
         }
         
         Log.d(TAG, "Cached template locally: $templateId")
     }
     
     private suspend fun getTemplatesFromCache(userId: String): List<WorkoutTemplate> {
         val localTemplates = workoutTemplateDao.getTemplatesByUserList(userId)
         
         return localTemplates.map { templateEntity ->
             val templateExercises = templateExerciseDao.getExercisesForTemplateList(templateEntity.id)
             val exercises = templateExercises.mapNotNull { te ->
                 val exerciseEntity = exerciseDao.getExerciseById(te.exerciseId)
                 exerciseEntity?.let {
                     val muscleCategory = MuscleCategory.entries.getOrNull(
                         it.muscleCategoryId - WorkoutConstants.MUSCLE_CATEGORY_ID_OFFSET
                     ) ?: MuscleCategory.CHEST
                     TemplateExercise(
                         exercise = Exercise(
                             id = it.id,
                             name = it.name,
                             muscleCategory = muscleCategory,
                             description = it.description,
                             imageUrl = it.imageUrl
                         ),
                         sets = te.sets,
                         reps = te.reps,
                         restSeconds = te.restSeconds
                     )
                 }
             }
             
             WorkoutTemplate(
                 id = templateEntity.id,
                 userId = templateEntity.userId,
                 name = templateEntity.name,
                 exercises = exercises,
                 createdAt = templateEntity.createdAt
             )
         }
     }
     
     private suspend fun getTemplateByIdFromCache(templateId: String): WorkoutTemplate? {
         val templateEntity = workoutTemplateDao.getTemplateById(templateId) ?: return null
         
         val templateExercises = templateExerciseDao.getExercisesForTemplateList(templateEntity.id)
         val exercises = templateExercises.mapNotNull { te ->
             val exerciseEntity = exerciseDao.getExerciseById(te.exerciseId)
             exerciseEntity?.let {
                 val muscleCategory = MuscleCategory.entries.getOrNull(
                     it.muscleCategoryId - WorkoutConstants.MUSCLE_CATEGORY_ID_OFFSET
                 ) ?: MuscleCategory.CHEST
                 TemplateExercise(
                     exercise = Exercise(
                         id = it.id,
                         name = it.name,
                         muscleCategory = muscleCategory,
                         description = it.description,
                         imageUrl = it.imageUrl
                     ),
                     sets = te.sets,
                     reps = te.reps,
                     restSeconds = te.restSeconds
                 )
             }
         }
         
         return WorkoutTemplate(
             id = templateEntity.id,
             userId = templateEntity.userId,
             name = templateEntity.name,
             exercises = exercises,
             createdAt = templateEntity.createdAt
         )
     }
 }
 
 @Serializable
 private data class CreateTemplateDto(
     val id: String,
     @SerialName("user_id")
     val userId: String,
     val name: String
 )

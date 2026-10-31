package com.diajarkoding.imfit.data.local.dao

import androidx.room.*
import com.diajarkoding.imfit.data.local.entity.TemplateExerciseEntity

@Dao
interface TemplateExerciseDao {
    @Query("SELECT * FROM template_exercises WHERE template_id = :templateId ORDER BY order_index")
    suspend fun getExercisesForTemplateList(templateId: String): List<TemplateExerciseEntity>

}

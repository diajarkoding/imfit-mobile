package com.diajarkoding.imfit.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(
    tableName = "template_exercises",
    primaryKeys = ["template_id", "exercise_id"]
)
data class TemplateExerciseEntity(
    @ColumnInfo(name = "template_id")
    val templateId: String,
    @ColumnInfo(name = "exercise_id")
    val exerciseId: String,
    @ColumnInfo(name = "order_index")
    val orderIndex: Int,
    val sets: Int,
    val reps: Int,
    @ColumnInfo(name = "rest_seconds")
    val restSeconds: Int,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)
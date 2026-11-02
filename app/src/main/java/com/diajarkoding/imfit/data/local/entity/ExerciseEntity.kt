package com.diajarkoding.imfit.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "exercises")
data class ExerciseEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    @ColumnInfo(name = "muscle_category_id")
    val muscleCategoryId: Int,
    val description: String,
    @ColumnInfo(name = "image_url")
    val imageUrl: String? = null,
    @ColumnInfo(name = "is_active")
    val isActive: Boolean = true,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "default_tracking_type")
    val defaultTrackingType: String = "WEIGHT_REPS",
    @ColumnInfo(name = "modality")
    val modality: String = "STRENGTH",
    @ColumnInfo(name = "allowed_tracking_types", defaultValue = "''")
    val allowedTrackingTypes: List<String> = listOf(defaultTrackingType)
)

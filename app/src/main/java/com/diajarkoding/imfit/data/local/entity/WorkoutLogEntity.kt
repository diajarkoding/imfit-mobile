package com.diajarkoding.imfit.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "workout_logs")
data class WorkoutLogEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "user_id")
    val userId: String,
    @ColumnInfo(name = "template_id")
    val templateId: String?,
    @ColumnInfo(name = "template_name")
    val templateName: String,
    val date: Long,
    @ColumnInfo(name = "start_time")
    val startTime: Long,
    @ColumnInfo(name = "end_time")
    val endTime: Long,
    @ColumnInfo(name = "total_volume")
    val totalVolume: Float,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)
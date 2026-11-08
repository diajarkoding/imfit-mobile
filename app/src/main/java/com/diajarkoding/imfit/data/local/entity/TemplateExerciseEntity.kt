package com.diajarkoding.imfit.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import com.diajarkoding.imfit.data.local.sync.SyncStatus

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
    val reps: Int? = null,
    @ColumnInfo(name = "rest_seconds")
    val restSeconds: Int,
    @ColumnInfo(name = "sync_status")
    val syncStatus: String = SyncStatus.PENDING_SYNC.name,
    @ColumnInfo(name = "pending_operation")
    val pendingOperation: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "tracking_type")
    val trackingType: String = "WEIGHT_REPS",
    @ColumnInfo(name = "target_duration_seconds")
    val targetDurationSeconds: Int? = null,
    @ColumnInfo(name = "target_distance_meters")
    val targetDistanceMeters: Int? = null,
    @ColumnInfo(name = "set_targets")
    val setTargets: String? = null,
    @ColumnInfo(name = "measurement_version")
    val measurementVersion: Int? = null
)

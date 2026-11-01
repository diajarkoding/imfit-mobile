package com.diajarkoding.imfit.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.diajarkoding.imfit.data.local.sync.SyncStatus

@Entity(
    tableName = "exercise_logs",
    indices = [
        Index(value = ["workout_log_id"]),
        Index(value = ["exercise_id"])
    ]
)
data class ExerciseLogEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "workout_log_id")
    val workoutLogId: String,
    @ColumnInfo(name = "exercise_id")
    val exerciseId: String,
    @ColumnInfo(name = "exercise_name")
    val exerciseName: String,
    @ColumnInfo(name = "muscle_category")
    val muscleCategory: String,
    @ColumnInfo(name = "order_index")
    val orderIndex: Int,
    @ColumnInfo(name = "total_volume")
    val totalVolume: Float,
    @ColumnInfo(name = "total_sets")
    val totalSets: Int,
    @ColumnInfo(name = "total_reps")
    val totalReps: Int,
    @ColumnInfo(name = "sync_status")
    val syncStatus: String = SyncStatus.PENDING_SYNC.name,
    @ColumnInfo(name = "pending_operation")
    val pendingOperation: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),
    // Prescription provenance is persisted with the completed-workout snapshot.
    @ColumnInfo(name = "input_defaults")
    val inputDefaults: String? = null,
    @ColumnInfo(name = "tracking_type")
    val trackingType: String = "WEIGHT_REPS",
    @ColumnInfo(name = "modality")
    val modality: String = "STRENGTH",
    @ColumnInfo(name = "total_duration_seconds")
    val totalDurationSeconds: Int = 0,
    @ColumnInfo(name = "total_distance_meters")
    val totalDistanceMeters: Int = 0,
    @ColumnInfo(name = "additional_volume")
    val additionalVolume: Float = 0f,
    @ColumnInfo(name = "metric_quality")
    val metricQuality: String = "LEGACY_UNVERIFIED"
)

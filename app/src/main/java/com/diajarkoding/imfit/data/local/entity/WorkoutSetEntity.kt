package com.diajarkoding.imfit.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.diajarkoding.imfit.data.local.sync.SyncStatus

@Entity(tableName = "workout_sets")
data class WorkoutSetEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "exercise_log_id")
    val exerciseLogId: String,
    @ColumnInfo(name = "workout_log_id")
    val workoutLogId: String,
    @ColumnInfo(name = "exercise_id")
    val exerciseId: String,
    @ColumnInfo(name = "set_number")
    val setNumber: Int,
    val weight: Float,
    val reps: Int,
    @ColumnInfo(name = "is_completed")
    val isCompleted: Boolean = false,
    @ColumnInfo(name = "sync_status")
    val syncStatus: String = SyncStatus.PENDING_SYNC.name,
    @ColumnInfo(name = "pending_operation")
    val pendingOperation: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)
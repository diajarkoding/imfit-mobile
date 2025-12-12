package com.diajarkoding.imfit.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.diajarkoding.imfit.data.local.sync.SyncStatus

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
    @ColumnInfo(name = "total_sets")
    val totalSets: Int = 0,
    @ColumnInfo(name = "total_reps")
    val totalReps: Int = 0,
    @ColumnInfo(name = "sync_status")
    val syncStatus: String = SyncStatus.PENDING_SYNC.name,
    @ColumnInfo(name = "pending_operation")
    val pendingOperation: String? = null,
    @ColumnInfo(name = "deleted_at")
    val deletedAt: Long? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)

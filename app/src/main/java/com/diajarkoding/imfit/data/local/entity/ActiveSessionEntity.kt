package com.diajarkoding.imfit.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity for persisting active workout sessions to support offline capability.
 * Allows workout sessions to survive app restarts.
 */
@Entity(tableName = "active_sessions")
data class ActiveSessionEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "user_id")
    val userId: String,
    @ColumnInfo(name = "template_id")
    val templateId: String,
    @ColumnInfo(name = "template_name")
    val templateName: String,
    @ColumnInfo(name = "start_time")
    val startTime: Long,
    @ColumnInfo(name = "current_exercise_index")
    val currentExerciseIndex: Int = 0,
    @ColumnInfo(name = "session_data_json")
    val sessionDataJson: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "is_paused", defaultValue = "0")
    val isPaused: Boolean = false,
    @ColumnInfo(name = "total_paused_time_ms", defaultValue = "0")
    val totalPausedTimeMs: Long = 0,
    @ColumnInfo(name = "last_pause_time")
    val lastPauseTime: Long? = null,
    @ColumnInfo(name = "rest_timer_end_time", defaultValue = "0")
    val restTimerEndTime: Long = 0,
    @ColumnInfo(name = "rest_timer_exercise_name")
    val restTimerExerciseName: String? = null,
    @ColumnInfo(name = "session_rest_override")
    val sessionRestOverride: Int? = null
)

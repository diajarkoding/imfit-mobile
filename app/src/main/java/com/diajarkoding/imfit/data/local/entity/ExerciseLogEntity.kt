package com.diajarkoding.imfit.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(
    tableName = "exercise_logs",
    primaryKeys = ["workout_log_id", "exercise_id"]
)
data class ExerciseLogEntity(
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
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)
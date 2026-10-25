package com.diajarkoding.imfit.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.diajarkoding.imfit.data.local.sync.SyncStatus

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val email: String,
    @ColumnInfo(name = "birth_date")
    val birthDate: String? = null,
    @ColumnInfo(name = "profile_photo_uri")
    val profilePhotoUri: String? = null,
    @ColumnInfo(name = "sync_status")
    val syncStatus: String = SyncStatus.SYNCED.name,
    @ColumnInfo(name = "pending_operation")
    val pendingOperation: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)
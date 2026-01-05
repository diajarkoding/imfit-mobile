package com.diajarkoding.imfit.data.local.dao

import androidx.room.*
import com.diajarkoding.imfit.data.local.entity.UserEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {
    @Query("SELECT * FROM users WHERE id = :id")
    suspend fun getUserById(id: String): UserEntity?

    @Query("SELECT * FROM users WHERE email = :email")
    suspend fun getUserByEmail(email: String): UserEntity?

    @Query("SELECT * FROM users")
    fun getAllUsers(): Flow<List<UserEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUser(user: UserEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUsers(users: List<UserEntity>)

    @Update
    suspend fun updateUser(user: UserEntity)

    @Delete
    suspend fun deleteUser(user: UserEntity)

    @Query("DELETE FROM users WHERE id = :id")
    suspend fun deleteUserById(id: String)

    @Query("DELETE FROM users")
    suspend fun deleteAllUsers()

    // Sync methods
    @Query("SELECT * FROM users WHERE sync_status != 'SYNCED'")
    suspend fun getPendingUsers(): List<UserEntity>

    @Query("UPDATE users SET sync_status = 'SYNCED', pending_operation = NULL WHERE id = :userId")
    suspend fun markAsSynced(userId: String)

    @Query("UPDATE users SET sync_status = :status WHERE id = :userId")
    suspend fun updateSyncStatus(userId: String, status: String)
}
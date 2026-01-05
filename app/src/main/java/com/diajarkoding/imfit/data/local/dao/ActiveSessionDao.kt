package com.diajarkoding.imfit.data.local.dao

import androidx.room.*
import com.diajarkoding.imfit.data.local.entity.ActiveSessionEntity

@Dao
interface ActiveSessionDao {
    @Query("SELECT * FROM active_sessions WHERE user_id = :userId LIMIT 1")
    suspend fun getActiveSession(userId: String): ActiveSessionEntity?

    @Query("SELECT * FROM active_sessions LIMIT 1")
    suspend fun getAnyActiveSession(): ActiveSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: ActiveSessionEntity)

    @Update
    suspend fun updateSession(session: ActiveSessionEntity)

    @Query("DELETE FROM active_sessions WHERE user_id = :userId")
    suspend fun deleteSession(userId: String)

    @Query("DELETE FROM active_sessions WHERE id = :sessionId")
    suspend fun deleteSessionById(sessionId: String)

    @Query("DELETE FROM active_sessions")
    suspend fun deleteAllSessions()
}

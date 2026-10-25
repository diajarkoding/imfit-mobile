package com.diajarkoding.imfit.domain.repository

import com.diajarkoding.imfit.domain.model.User
import com.diajarkoding.imfit.domain.model.RegisterResult

interface AuthRepository {
    suspend fun register(
        name: String,
        email: String,
        password: String,
        birthDate: String? = null,
        profilePhotoUri: String? = null
    ): Result<RegisterResult>
    suspend fun login(email: String, password: String): Result<User>
    suspend fun logout()
    suspend fun getCurrentUser(): User?
    fun observeCurrentUser(): kotlinx.coroutines.flow.Flow<User?> = kotlinx.coroutines.flow.flow { emit(getCurrentUser()) }
    /** Local auth identity for timer recovery; never fetch a remote profile. */
    suspend fun getLocalUserId(): String? = getCurrentUser()?.id
    /** Local identity changes only; must not fetch a remote profile. */
    fun observeLocalUserId(): kotlinx.coroutines.flow.Flow<String?> = kotlinx.coroutines.flow.flow { emit(getLocalUserId()) }
    /** Synchronous SDK read only; never waits for init or network. Null means not-yet-loaded OR absent. */
    suspend fun peekLocalUserId(): String? = getLocalUserId()
    /** Auth state machine name for diagnostics only (e.g. Authenticated, NotAuthenticated); never a user ID. */
    suspend fun localAuthStatusName(): String = "UNKNOWN"
    suspend fun isLoggedIn(): Boolean
    suspend fun updateProfile(user: User): Result<User>
    
    /**
     * Gets a signed URL for accessing a private avatar image.
     * @param storagePath The storage path stored in database
     * @return The signed URL valid for 1 hour, or null if failed
     */
    suspend fun getSignedAvatarUrl(storagePath: String?): String?
}

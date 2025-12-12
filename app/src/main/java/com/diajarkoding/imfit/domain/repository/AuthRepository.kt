package com.diajarkoding.imfit.domain.repository

import com.diajarkoding.imfit.domain.model.User

interface AuthRepository {
    suspend fun register(
        name: String,
        email: String,
        password: String,
        birthDate: String? = null,
        profilePhotoUri: String? = null
    ): Result<User>
    suspend fun login(email: String, password: String): Result<User>
    suspend fun logout()
    suspend fun getCurrentUser(): User?
    suspend fun isLoggedIn(): Boolean
    suspend fun updateProfile(user: User): Result<User>
    
    /**
     * Gets a signed URL for accessing a private avatar image.
     * @param storagePath The storage path stored in database
     * @return The signed URL valid for 1 hour, or null if failed
     */
    suspend fun getSignedAvatarUrl(storagePath: String?): String?
}
package com.diajarkoding.imfit.domain.repository

import com.diajarkoding.imfit.domain.model.User

interface AuthRepository {
    suspend fun register(name: String, email: String, password: String): Result<User>
    suspend fun login(email: String, password: String): Result<User>
    suspend fun logout()
    fun getCurrentUser(): User?
    fun isLoggedIn(): Boolean
}
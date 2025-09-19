package com.diajarkoding.imfit.domain.repository

import com.diajarkoding.imfit.data.remote.dto.LoginRequest
import com.diajarkoding.imfit.data.remote.dto.RegisterRequest
import com.diajarkoding.imfit.domain.model.Result

interface AuthRepository {
    suspend fun register(registerRequest: RegisterRequest): Result<Unit>
    suspend fun login(loginRequest: LoginRequest): Result<String> // Mengembalikan token (String)
}
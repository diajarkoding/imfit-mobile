package com.diajarkoding.imfit.domain.usecase

import com.diajarkoding.imfit.data.remote.dto.LoginRequest
import com.diajarkoding.imfit.domain.repository.AuthRepository
import javax.inject.Inject

class LoginUserUseCase @Inject constructor(
    private val authRepository: AuthRepository
) {
    suspend operator fun invoke(loginRequest: LoginRequest) = authRepository.login(loginRequest)
}
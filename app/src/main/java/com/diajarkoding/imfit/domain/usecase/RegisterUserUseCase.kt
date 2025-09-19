package com.diajarkoding.imfit.domain.usecase

import com.diajarkoding.imfit.data.remote.dto.RegisterRequest
import com.diajarkoding.imfit.domain.repository.AuthRepository
import javax.inject.Inject

class RegisterUserUseCase @Inject constructor(
    private val authRepository: AuthRepository
) {
    suspend operator fun invoke(registerRequest: RegisterRequest) =
        authRepository.register(registerRequest)
}
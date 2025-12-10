package com.diajarkoding.imfit.data.repository

import com.diajarkoding.imfit.data.local.FakeUserDataSource
import com.diajarkoding.imfit.domain.model.User
import com.diajarkoding.imfit.domain.repository.AuthRepository
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepositoryImpl @Inject constructor() : AuthRepository {

    override suspend fun register(
        name: String,
        email: String,
        password: String,
        birthDate: String?,
        profilePhotoUri: String?
    ): Result<User> {
        delay(500) // Simulate network delay
        return FakeUserDataSource.register(name, email, password, birthDate, profilePhotoUri)
    }

    override suspend fun login(email: String, password: String): Result<User> {
        delay(500) // Simulate network delay
        return FakeUserDataSource.login(email, password)
    }

    override suspend fun logout() {
        delay(200)
        FakeUserDataSource.logout()
    }

    override fun getCurrentUser(): User? {
        return FakeUserDataSource.getCurrentUser()
    }

    override fun isLoggedIn(): Boolean {
        return FakeUserDataSource.isLoggedIn()
    }
}
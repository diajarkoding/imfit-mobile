package com.diajarkoding.imfit.data.repository

import com.diajarkoding.imfit.data.local.FakeUserDataSource
import com.diajarkoding.imfit.domain.model.User
import com.diajarkoding.imfit.domain.model.RegisterResult
import com.diajarkoding.imfit.domain.repository.AuthRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DemoAuthRepository @Inject constructor() : AuthRepository {

    init {
        FakeUserDataSource.login(DEMO_EMAIL, DEMO_PASSWORD)
    }

    override suspend fun register(
        name: String,
        email: String,
        password: String,
        birthDate: String?,
        profilePhotoUri: String?
    ): Result<RegisterResult> = FakeUserDataSource.register(
        name = name,
        email = email,
        password = password,
        birthDate = birthDate,
        profilePhotoUri = profilePhotoUri
    ).map(RegisterResult::Authenticated)

    override suspend fun login(email: String, password: String): Result<User> =
        FakeUserDataSource.login(email, password)

    override suspend fun logout() {
        FakeUserDataSource.logout()
    }

    override suspend fun getCurrentUser(): User? = FakeUserDataSource.getCurrentUser()

    override suspend fun isLoggedIn(): Boolean = FakeUserDataSource.isLoggedIn()

    override suspend fun updateProfile(user: User): Result<User> =
        FakeUserDataSource.updateProfile(user)

    override suspend fun getSignedAvatarUrl(storagePath: String?): String? = storagePath

    companion object {
        const val DEMO_EMAIL = "demo@imfit.com"
        const val DEMO_PASSWORD = "password123"
    }
}

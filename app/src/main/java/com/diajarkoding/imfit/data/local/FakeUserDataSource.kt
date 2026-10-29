package com.diajarkoding.imfit.data.local

import com.diajarkoding.imfit.domain.model.User

object FakeUserDataSource {
    private val sessionEpochMutable = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val sessionEpoch: kotlinx.coroutines.flow.StateFlow<Long> = sessionEpochMutable

    private val registeredUsers = mutableListOf(
        UserCredentials(
            user = User(id = "user_1", name = "Demo User", email = "demo@imfit.com"),
            password = "password123"
        )
    )

    private val profileMutable = kotlinx.coroutines.flow.MutableStateFlow<User?>(null)
    val profile: kotlinx.coroutines.flow.StateFlow<User?> = profileMutable

    data class UserCredentials(
        val user: User,
        val password: String
    )

    fun register(
        name: String,
        email: String,
        password: String,
        birthDate: String? = null,
        profilePhotoUri: String? = null
    ): Result<User> {
        if (registeredUsers.any { it.user.email.equals(email, ignoreCase = true) }) {
            return Result.failure(Exception("Email already registered"))
        }

        val newUser = User(
            id = "user_${System.currentTimeMillis()}",
            name = name,
            email = email,
            birthDate = birthDate,
            profilePhotoUri = profilePhotoUri
        )

        registeredUsers.add(UserCredentials(newUser, password))
        profileMutable.value = newUser
        sessionEpochMutable.value++
        return Result.success(newUser)
    }

    fun login(email: String, password: String): Result<User> {
        val credentials = registeredUsers.find {
            it.user.email.equals(email, ignoreCase = true) && it.password == password
        }

        return if (credentials != null) {
            profileMutable.value = credentials.user
            sessionEpochMutable.value++
            Result.success(credentials.user)
        } else {
            Result.failure(Exception("Invalid email or password"))
        }
    }

    fun logout() {
        profileMutable.value = null
        sessionEpochMutable.value++
    }

    fun getCurrentUser(): User? = profileMutable.value

    fun isLoggedIn(): Boolean = profileMutable.value != null

    fun updateProfile(patch: com.diajarkoding.imfit.domain.model.ProfilePatch): Result<User> {
        val current = profileMutable.value
            ?: return Result.failure(IllegalStateException("No user is logged in"))
        val index = registeredUsers.indexOfFirst { it.user.id == current.id }
        if (index == -1) return Result.failure(IllegalStateException("User is not registered"))
        val name = when (val field = patch.name) {
            com.diajarkoding.imfit.domain.model.ProfileField.Omitted -> current.name
            is com.diajarkoding.imfit.domain.model.ProfileField.Value -> field.value
        }
        val birthDate = when (val field = patch.birthDate) {
            com.diajarkoding.imfit.domain.model.ProfileField.Omitted -> current.birthDate
            is com.diajarkoding.imfit.domain.model.ProfileField.Value -> field.value
        }
        val avatar = when (val field = patch.avatarPath) {
            com.diajarkoding.imfit.domain.model.ProfileField.Omitted -> current.profilePhotoUri
            is com.diajarkoding.imfit.domain.model.ProfileField.Value -> field.value
        }
        if (name.isBlank() || (birthDate != null && runCatching { java.time.LocalDate.parse(birthDate) }.isFailure) ||
            (avatar != null && !com.diajarkoding.imfit.domain.repository.isCanonicalAvatarPath(avatar, current.id))) {
            return Result.failure(IllegalArgumentException("Invalid profile patch"))
        }
        val updated = current.copy(name = name, birthDate = birthDate, profilePhotoUri = avatar)
        registeredUsers[index] = registeredUsers[index].copy(user = updated)
        profileMutable.value = updated
        return Result.success(updated)
    }
}

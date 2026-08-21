package com.diajarkoding.imfit.data.local

import com.diajarkoding.imfit.domain.model.User

object FakeUserDataSource {

    private val registeredUsers = mutableListOf(
        UserCredentials(
            user = User(id = "user_1", name = "Demo User", email = "demo@imfit.com"),
            password = "password123"
        )
    )

    private var currentUser: User? = null

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
        currentUser = newUser
        return Result.success(newUser)
    }

    fun login(email: String, password: String): Result<User> {
        val credentials = registeredUsers.find {
            it.user.email.equals(email, ignoreCase = true) && it.password == password
        }

        return if (credentials != null) {
            currentUser = credentials.user
            Result.success(credentials.user)
        } else {
            Result.failure(Exception("Invalid email or password"))
        }
    }

    fun logout() {
        currentUser = null
    }

    fun getCurrentUser(): User? = currentUser

    fun isLoggedIn(): Boolean = currentUser != null

    fun updateProfile(user: User): Result<User> {
        val current = currentUser
            ?: return Result.failure(IllegalStateException("No user is logged in"))
        if (current.id != user.id) {
            return Result.failure(IllegalArgumentException("Cannot update another user"))
        }

        val index = registeredUsers.indexOfFirst { it.user.id == user.id }
        if (index == -1) {
            return Result.failure(IllegalStateException("User is not registered"))
        }

        val existingCredentials = registeredUsers[index]
        registeredUsers[index] = existingCredentials.copy(user = user)
        currentUser = user
        return Result.success(user)
    }
}

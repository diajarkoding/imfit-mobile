package com.diajarkoding.imfit.data.repository

import android.util.Log
import com.diajarkoding.imfit.data.remote.dto.ProfileDto
import com.diajarkoding.imfit.data.remote.dto.toDomain
import com.diajarkoding.imfit.domain.model.User
import com.diajarkoding.imfit.domain.repository.AuthRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val supabaseClient: SupabaseClient
) : AuthRepository {

    private var cachedUser: User? = null

    override suspend fun register(
        name: String,
        email: String,
        password: String,
        birthDate: String?,
        profilePhotoUri: String?
    ): Result<User> {
        return try {
            supabaseClient.auth.signUpWith(Email) {
                this.email = email
                this.password = password
                this.data = buildJsonObject {
                    put("name", name)
                    birthDate?.let { put("birth_date", it) }
                }
            }
            
            val userId = supabaseClient.auth.currentUserOrNull()?.id
                ?: return Result.failure(Exception("Registration failed: No user ID"))
            
            val profile = fetchProfile(userId)
            if (profile != null) {
                cachedUser = profile
                Result.success(profile)
            } else {
                val newUser = User(
                    id = userId,
                    name = name,
                    email = email,
                    birthDate = birthDate,
                    profilePhotoUri = profilePhotoUri
                )
                cachedUser = newUser
                Result.success(newUser)
            }
        } catch (e: Exception) {
            Log.e("AuthRepository", "Register error: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun login(email: String, password: String): Result<User> {
        return try {
            supabaseClient.auth.signInWith(Email) {
                this.email = email
                this.password = password
            }
            
            val userId = supabaseClient.auth.currentUserOrNull()?.id
                ?: return Result.failure(Exception("Login failed: No user ID"))
            
            val profile = fetchProfile(userId)
            if (profile != null) {
                cachedUser = profile
                Result.success(profile)
            } else {
                Result.failure(Exception("Profile not found"))
            }
        } catch (e: Exception) {
            Log.e("AuthRepository", "Login error: ${e.message}", e)
            Result.failure(e)
        }
    }

    override suspend fun logout() {
        try {
            supabaseClient.auth.signOut()
            cachedUser = null
        } catch (e: Exception) {
            Log.e("AuthRepository", "Logout error: ${e.message}", e)
        }
    }

    override suspend fun getCurrentUser(): User? {
        if (cachedUser != null) return cachedUser
        
        val userId = supabaseClient.auth.currentUserOrNull()?.id ?: return null
        cachedUser = fetchProfile(userId)
        return cachedUser
    }

    override suspend fun isLoggedIn(): Boolean {
        return supabaseClient.auth.currentUserOrNull() != null
    }

    override suspend fun updateProfile(user: User): Result<User> {
        return try {
            supabaseClient.postgrest.from("profiles")
                .update({
                    set("name", user.name)
                    user.birthDate?.let { set("birth_date", it) }
                    user.profilePhotoUri?.let { set("avatar_url", it) }
                }) {
                    filter { eq("id", user.id) }
                }
            cachedUser = user
            Result.success(user)
        } catch (e: Exception) {
            Log.e("AuthRepository", "Update profile error: ${e.message}", e)
            Result.failure(e)
        }
    }

    private suspend fun fetchProfile(userId: String): User? {
        return try {
            val profile = supabaseClient.postgrest.from("profiles")
                .select {
                    filter { eq("id", userId) }
                }
                .decodeSingleOrNull<ProfileDto>()
            profile?.toDomain()
        } catch (e: Exception) {
            Log.e("AuthRepository", "Fetch profile error: ${e.message}", e)
            null
        }
    }
}
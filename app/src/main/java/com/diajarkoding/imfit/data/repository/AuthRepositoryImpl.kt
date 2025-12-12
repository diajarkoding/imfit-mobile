package com.diajarkoding.imfit.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import com.diajarkoding.imfit.BuildConfig
import com.diajarkoding.imfit.data.exception.AuthException
import com.diajarkoding.imfit.data.exception.mapSupabaseException
import com.diajarkoding.imfit.data.remote.dto.ProfileDto
import com.diajarkoding.imfit.data.remote.dto.toDomain
import com.diajarkoding.imfit.domain.model.User
import com.diajarkoding.imfit.domain.repository.AuthRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.storage.storage
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AuthRepository"
private const val AVATARS_BUCKET = "avatars"

@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val supabaseClient: SupabaseClient,
    @ApplicationContext private val context: Context
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
            // Validate input parameters
            if (name.isBlank()) {
                return Result.failure(AuthException.InvalidEmail())
            }
            if (email.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                return Result.failure(AuthException.InvalidEmail())
            }
            if (password.length < 6) {
                return Result.failure(AuthException.WeakPassword())
            }

            Log.d(TAG, "Starting registration for email: $email")

            supabaseClient.auth.signUpWith(Email) {
                this.email = email
                this.password = password
                this.data = buildJsonObject {
                    put("name", name)
                    birthDate?.let { put("birth_date", it) }
                }
            }

            val userId = supabaseClient.auth.currentUserOrNull()?.id
                ?: return Result.failure(AuthException.UnknownError("Registration completed but no user ID received"))

            Log.d(TAG, "Registration successful for user ID: $userId")

            // Upload profile photo to Supabase Storage if provided
            var avatarUrl: String? = null
            if (!profilePhotoUri.isNullOrBlank()) {
                avatarUrl = uploadProfilePhoto(userId, profilePhotoUri)
                if (avatarUrl != null) {
                    Log.d(TAG, "Profile photo uploaded successfully: $avatarUrl")
                } else {
                    Log.w(TAG, "Failed to upload profile photo, using placeholder")
                }
            }

            // Create profile in profiles table
            val newUser = User(
                id = userId,
                name = name,
                email = email,
                birthDate = birthDate,
                profilePhotoUri = avatarUrl // Use remote URL, not local URI
            )

            // Update profile with additional data (trigger already created base profile)
            try {
                if (birthDate != null || avatarUrl != null) {
                    supabaseClient.postgrest.from("profiles")
                        .update(buildJsonObject {
                            birthDate?.let { put("birth_date", it) }
                            avatarUrl?.let { put("avatar_url", it) }
                        }) {
                            filter { eq("id", userId) }
                        }
                    Log.d(TAG, "Profile updated with birth_date/avatar for user ID: $userId")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update profile: ${e.message}", e)
            }

            cachedUser = newUser
            Result.success(newUser)
        } catch (e: Exception) {
            Log.e(TAG, "Register error: ${e.message}", e)
            val authException = mapSupabaseException(e)
            Result.failure(authException)
        }
    }

    /**
     * Uploads a profile photo to Supabase Storage and returns the storage path.
     * The path is stored in the database and used to generate signed URLs.
     * 
     * @param userId The user's ID (used as folder name)
     * @param localUri The local content:// or file:// URI
     * @return The storage path of the uploaded image, or null if upload fails
     */
    private suspend fun uploadProfilePhoto(userId: String, localUri: String): String? {
        return try {
            Log.d(TAG, "Uploading profile photo from: $localUri")
            
            // Read image bytes from content resolver
            val uri = Uri.parse(localUri)
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: run {
                    Log.e(TAG, "Failed to open input stream for URI: $localUri")
                    return null
                }
            
            val imageBytes = inputStream.use { it.readBytes() }
            Log.d(TAG, "Read ${imageBytes.size} bytes from image")
            
            // Generate unique filename
            val filename = "${UUID.randomUUID()}.jpg"
            val path = "$userId/$filename"
            
            // Upload to Supabase Storage (private bucket)
            val bucket = supabaseClient.storage.from(AVATARS_BUCKET)
            bucket.upload(path, imageBytes) {
                upsert = true
            }
            Log.d(TAG, "Image uploaded to Storage: $path")
            
            // Return the storage path (not URL) for private bucket
            path
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upload profile photo: ${e.message}", e)
            null
        }
    }

    /**
     * Generates a signed URL for a private avatar image.
     * The URL is valid for 1 hour.
     * 
     * @param storagePath The storage path (e.g., "userId/filename.jpg")
     * @return The signed URL or null if generation fails
     */
    override suspend fun getSignedAvatarUrl(storagePath: String?): String? {
        if (storagePath.isNullOrBlank()) return null
        
        return try {
            val bucket = supabaseClient.storage.from(AVATARS_BUCKET)
            val signedUrl = bucket.createSignedUrl(storagePath, expiresIn = 3600.seconds)
            Log.d(TAG, "Generated signed URL for: $storagePath")
            signedUrl
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate signed URL: ${e.message}", e)
            null
        }
    }


    override suspend fun login(email: String, password: String): Result<User> {
        return try {
            // Validate input parameters
            if (email.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                return Result.failure(AuthException.InvalidEmail())
            }
            if (password.isBlank()) {
                return Result.failure(AuthException.InvalidCredentials())
            }

            Log.d("AuthRepository", "Starting login for email: $email")

            supabaseClient.auth.signInWith(Email) {
                this.email = email
                this.password = password
            }

            val userId = supabaseClient.auth.currentUserOrNull()?.id
                ?: return Result.failure(AuthException.InvalidCredentials())

            Log.d("AuthRepository", "Login successful for user ID: $userId")

            var profile = fetchProfile(userId)
            if (profile == null) {
                Log.w("AuthRepository", "User authenticated but no profile found for user ID: $userId")
                // Create profile if it doesn't exist (fallback for users without trigger)
                val currentUser = supabaseClient.auth.currentUserOrNull()
                if (currentUser != null) {
                    val userName = currentUser.userMetadata?.get("name")?.toString()?.removeSurrounding("\"") ?: "User"
                    val birthDate = currentUser.userMetadata?.get("birth_date")?.toString()?.removeSurrounding("\"")
                    try {
                        supabaseClient.postgrest.from("profiles")
                            .upsert(buildJsonObject {
                                put("id", userId)
                                put("name", userName)
                                put("email", email)
                                birthDate?.let { put("birth_date", it) }
                            })
                        Log.d("AuthRepository", "Profile upserted for user: $userId")
                        profile = fetchProfile(userId)
                    } catch (e: Exception) {
                        Log.e("AuthRepository", "Failed to upsert profile during login: ${e.message}", e)
                    }
                }
            }

            if (profile != null) {
                cachedUser = profile
                Result.success(profile)
            } else {
                val currentUser = supabaseClient.auth.currentUserOrNull()
                if (currentUser != null) {
                    val basicUser = User(
                        id = userId,
                        name = currentUser.userMetadata?.get("name")?.toString()?.removeSurrounding("\"") ?: "User",
                        email = email,
                        birthDate = null,
                        profilePhotoUri = null
                    )
                    cachedUser = basicUser
                    Result.success(basicUser)
                } else {
                    Result.failure(AuthException.UserNotFound())
                }
            }
        } catch (e: Exception) {
            Log.e("AuthRepository", "Login error: ${e.message}", e)
            val authException = mapSupabaseException(e)
            Result.failure(authException)
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
        return try {
            // Return cached user if available
            cachedUser?.let { return it }

            // Check if user is authenticated
            val currentUser = supabaseClient.auth.currentUserOrNull()
                ?: return null

            Log.d("AuthRepository", "Fetching current user for user ID: ${currentUser.id}")

            // Try to fetch profile from database
            val profile = fetchProfile(currentUser.id)
            if (profile != null) {
                cachedUser = profile
                return profile
            } else {
                // Create basic user object from auth metadata
                val basicUser = User(
                    id = currentUser.id,
                    name = currentUser.userMetadata?.get("name")?.toString() ?: "User",
                    email = currentUser.email ?: "",
                    birthDate = null,
                    profilePhotoUri = null
                )
                cachedUser = basicUser
                return basicUser
            }
        } catch (e: Exception) {
            Log.e("AuthRepository", "Get current user error: ${e.message}", e)
            // Don't throw exception, just return null to indicate no user
            null
        }
    }

    override suspend fun isLoggedIn(): Boolean {
        return try {
            val currentUser = supabaseClient.auth.currentUserOrNull()
            val isLoggedIn = currentUser != null
            Log.d("AuthRepository", "Is user logged in: $isLoggedIn")
            isLoggedIn
        } catch (e: Exception) {
            Log.e("AuthRepository", "Check login status error: ${e.message}", e)
            false
        }
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
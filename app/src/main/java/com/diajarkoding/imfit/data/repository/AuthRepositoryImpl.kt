package com.diajarkoding.imfit.data.repository

import android.content.Context
import android.net.Uri
import com.diajarkoding.imfit.core.timer.TimerSessionCleanup
import com.diajarkoding.imfit.data.exception.AuthException
import com.diajarkoding.imfit.data.exception.mapSupabaseException
import com.diajarkoding.imfit.data.remote.online.*
import com.diajarkoding.imfit.domain.model.*
import com.diajarkoding.imfit.domain.repository.AuthRepository
import com.diajarkoding.imfit.domain.repository.isCanonicalAvatarPath
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.storage.storage
import java.util.UUID
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val AVATARS_BUCKET = "avatars"

@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val supabaseClient: SupabaseClient,
    private val readsProvider: Provider<RemoteReadGateway>,
    private val changesProvider: Provider<OnlineMutationChanges>,
    @ApplicationContext private val context: Context,
    private val timerCleanup: TimerSessionCleanup,
) : AuthRepository {
    private data class CachedProfile(val identity: AuthIdentity, val user: User)
    private val cached = MutableStateFlow<CachedProfile?>(null)
    private val reads by lazy {
        readsProvider.get().also { gateway ->
            gateway.auth.onIdentityChanged { cached.value = null }
        }
    }

    override suspend fun register(
        name: String, email: String, password: String, birthDate: String?, profilePhotoUri: String?,
    ): Result<RegisterResult> {
        var accountCreated = false
        return try {
            if (name.isBlank() || !validEmail(email)) return Result.failure(AuthException.InvalidEmail())
            if (!isStrongPassword(password)) return Result.failure(AuthException.WeakPassword())
            supabaseClient.auth.signUpWith(Email) {
                this.email = email
                this.password = password
                data = buildJsonObject {
                    put("name", name)
                    birthDate?.let { put("birth_date", it) }
                }
            }
            accountCreated = true
            val owner = supabaseClient.auth.currentUserOrNull()?.id
                ?: return Result.success(RegisterResult.CheckEmail)
            val identity = reads.auth.capture().identity
            check(identity.ownerId == owner) { "Authenticated owner changed" }
            val avatar = profilePhotoUri?.takeIf(String::isNotBlank)?.let { uploadProfilePhoto(identity, it) }
            val patch = ProfilePatch(
                birthDate = birthDate?.let { ProfileField.Value(it) } ?: ProfileField.Omitted,
                avatarPath = avatar?.let { ProfileField.Value(it) } ?: ProfileField.Omitted,
            )
            val user = writeProfile(identity, patch)
            Result.success(RegisterResult.Authenticated(user))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (accountCreated) Result.failure(IllegalStateException(
                "Account may already have been created, but profile setup failed. Sign in to review it; do not register again.", failure))
            else Result.failure(mapSupabaseException(failure))
        }
    }

    /** Upload failure is not an instruction to clear the avatar. Never persists a signed URL. */
    private suspend fun uploadProfilePhoto(identity: AuthIdentity, localUri: String): String {
        if (!reads.auth.isCurrent(identity)) throw StaleAuthException()
        val bytes = (context.contentResolver.openInputStream(Uri.parse(localUri))
            ?: throw IllegalStateException("Unable to read selected photo")).use { it.readBytes() }
        val path = "${identity.ownerId}/${UUID.randomUUID()}.jpg"
        supabaseClient.storage.from(AVATARS_BUCKET).upload(path, bytes) { upsert = false }
        if (!reads.auth.isCurrent(identity)) throw StaleAuthException()
        return path
    }

    override suspend fun getSignedAvatarUrl(storagePath: String?): String? {
        if (storagePath.isNullOrBlank()) return null
        val identity = reads.auth.capture().identity
        if (!isCanonicalAvatarPath(storagePath, identity.ownerId)) return null
        return try {
            val url = supabaseClient.storage.from(AVATARS_BUCKET).createSignedUrl(storagePath, expiresIn = 3600.seconds)
            if (!reads.auth.isCurrent(identity)) throw StaleAuthException()
            url
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun login(email: String, password: String): Result<User> = try {
        if (!validEmail(email)) Result.failure(AuthException.InvalidEmail())
        else if (password.isBlank()) Result.failure(AuthException.InvalidCredentials())
        else {
            cached.value = null
            supabaseClient.auth.signInWith(Email) { this.email = email; this.password = password }
            Result.success(getCurrentUser() ?: throw AuthException.UserNotFound())
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Result.failure(mapSupabaseException(failure))
    }

    override suspend fun logout() {
        cached.value = null
        timerCleanup.stop("LOGOUT")
        supabaseClient.auth.signOut()
    }

    override suspend fun peekLocalUserId(): String? = supabaseClient.auth.currentUserOrNull()?.id
    override fun observeLocalUserId(): Flow<String?> = supabaseClient.auth.sessionStatus
        .map { supabaseClient.auth.currentUserOrNull()?.id }.distinctUntilChanged()
    override suspend fun localAuthStatusName(): String =
        supabaseClient.auth.sessionStatus.value::class.simpleName ?: "null"
    override suspend fun getLocalUserId(): String? {
        peekLocalUserId()?.let { return it }
        supabaseClient.auth.awaitInitialization()
        return peekLocalUserId()
    }

    override suspend fun getCurrentUser(): User? {
        val owner = getLocalUserId() ?: run { cached.value = null; return null }
        val identity = reads.auth.capture().identity
        if (identity.ownerId != owner) throw StaleAuthException()
        cached.value?.takeIf { it.identity == identity && reads.auth.isCurrent(identity) }?.let { return it.user }
        // Profile failure propagates: it is not auth absence and never creates a fallback profile.
        return acceptProfile(identity, reads.profile())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeCurrentUser(): Flow<User?> = flow {
        getLocalUserId()
        // Initialize the lazy gateway before observing its epoch stream.
        try { reads.auth.capture() } catch (failure: OnlineFailure) {
            if (failure.reason != RemoteError.Unauthenticated) throw failure
        }
        emitAll(reads.auth.identity.flatMapLatest { identity ->
            if (identity == null) {
                cached.value = null
                flowOf(null)
            } else flow {
                emit(getCurrentUser())
                emitAll(cached.map { entry ->
                    entry?.takeIf { it.identity == identity && reads.auth.isCurrent(identity) }?.user
                }.filterNotNull())
            }
        })
    }.distinctUntilChanged().buffer(0)

    override suspend fun isLoggedIn(): Boolean = getLocalUserId() != null

    override suspend fun updateProfile(patch: ProfilePatch): Result<User> = try {
        val identity = reads.auth.capture().identity
        Result.success(writeProfile(identity, patch))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Result.failure(failure)
    }

    private suspend fun writeProfile(identity: AuthIdentity, patch: ProfilePatch): User {
        if (!reads.auth.isCurrent(identity)) throw StaleAuthException()
        val user = acceptProfile(identity, reads.updateProfile(patch))
        if (patch != ProfilePatch() && !changesProvider.get().publish(ProfileChanged(identity))) throw StaleAuthException()
        return user
    }

    private fun acceptProfile(identity: AuthIdentity, row: RemoteProfile): User {
        if (!reads.auth.isCurrent(identity)) throw StaleAuthException()
        if (row.id != identity.ownerId) throw OnlineFailure(RemoteError.Protocol)
        val user = User(row.id, row.name, row.email, row.birthDate, row.avatarUrl)
        reads.auth.withCurrent(identity) { cached.value = CachedProfile(identity, user) }
        return user
    }

    private fun validEmail(email: String): Boolean = email.isNotBlank() && android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()
    private fun isStrongPassword(password: String): Boolean = password.length >= 8 &&
        password.any(Char::isUpperCase) && password.any(Char::isLowerCase) && password.any(Char::isDigit)
}

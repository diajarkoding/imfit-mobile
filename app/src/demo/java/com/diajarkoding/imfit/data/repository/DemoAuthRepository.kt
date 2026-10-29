package com.diajarkoding.imfit.data.repository

import com.diajarkoding.imfit.data.local.FakeUserDataSource
import com.diajarkoding.imfit.domain.model.User
import com.diajarkoding.imfit.domain.model.RegisterResult
import com.diajarkoding.imfit.domain.repository.AuthRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.map

@Singleton
class DemoAuthRepository @Inject constructor(
    private val timerCleanup: com.diajarkoding.imfit.core.timer.TimerSessionCleanup?,
    private val readsProvider: javax.inject.Provider<com.diajarkoding.imfit.data.remote.online.RemoteReadGateway>?,
    private val changesProvider: javax.inject.Provider<com.diajarkoding.imfit.data.remote.online.OnlineMutationChanges>?,
) : AuthRepository {
    constructor() : this(null, null, null)
    private val reads by lazy { readsProvider?.get() ?: com.diajarkoding.imfit.data.remote.online.DemoReadGateway(this) }
    private val changes by lazy { changesProvider?.get() ?: com.diajarkoding.imfit.data.remote.online.OnlineMutationChanges(reads.auth) }

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
        timerCleanup?.stop("LOGOUT")
        FakeUserDataSource.logout()
    }

    override suspend fun getCurrentUser(): User? = FakeUserDataSource.getCurrentUser()

    override suspend fun getLocalUserId(): String? = FakeUserDataSource.getCurrentUser()?.id
    override suspend fun peekLocalUserId(): String? = FakeUserDataSource.getCurrentUser()?.id
    override fun observeLocalUserId() = FakeUserDataSource.sessionEpoch.map { FakeUserDataSource.getCurrentUser()?.id }
    override fun observeCurrentUser() = FakeUserDataSource.profile

    override suspend fun isLoggedIn(): Boolean = FakeUserDataSource.isLoggedIn()

    override suspend fun updateProfile(patch: com.diajarkoding.imfit.domain.model.ProfilePatch): Result<User> = try {
        val identity = reads.auth.capture().identity
        val row = reads.updateProfile(patch)
        if (!reads.auth.isCurrent(identity)) throw com.diajarkoding.imfit.data.remote.online.StaleAuthException()
        if (row.id != identity.ownerId) throw com.diajarkoding.imfit.data.remote.online.OnlineFailure(com.diajarkoding.imfit.data.remote.online.RemoteError.Protocol)
        val user = User(row.id, row.name, row.email, row.birthDate, row.avatarUrl)
        if (patch != com.diajarkoding.imfit.domain.model.ProfilePatch() && !changes.publish(com.diajarkoding.imfit.data.remote.online.ProfileChanged(identity))) throw com.diajarkoding.imfit.data.remote.online.StaleAuthException()
        Result.success(user)
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Result.failure(failure)
    }

    override suspend fun getSignedAvatarUrl(storagePath: String?): String? = storagePath?.takeIf {
        val owner = getLocalUserId()
        owner != null && com.diajarkoding.imfit.domain.repository.isCanonicalAvatarPath(it, owner)
    }?.let { "https://demo.invalid/avatars/$it" }

    companion object {
        const val DEMO_EMAIL = "demo@imfit.com"
        const val DEMO_PASSWORD = "password123"
    }
}

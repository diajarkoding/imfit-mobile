package com.diajarkoding.imfit.data.repository

import com.diajarkoding.imfit.domain.model.AuthSessionState
import com.diajarkoding.imfit.domain.model.AvatarLoadState
import com.diajarkoding.imfit.domain.model.ProfileLoadState
import com.diajarkoding.imfit.domain.model.User
import com.diajarkoding.imfit.domain.model.UserProfileState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

sealed interface AuthSessionSignal {
    data object Initializing : AuthSessionSignal
    data class Authenticated(
        val userId: String,
        val email: String = "",
        val fallbackName: String = "User",
        val fallbackBirthDate: String? = null
    ) : AuthSessionSignal
    data object RefreshFailure : AuthSessionSignal
    data object NotAuthenticated : AuthSessionSignal
}

/**
 * Coordinates centralized user profile state, session transitions (including RefreshFailure),
 * cross-account isolation guards, and in-memory signed avatar URL caching.
 */
class UserProfileCoordinator(
    private val scope: CoroutineScope,
    sessionSignals: Flow<AuthSessionSignal>,
    private val currentIdentity: () -> AuthSessionSignal.Authenticated?,
    private val awaitIdentity: suspend () -> AuthSessionSignal.Authenticated?,
    private val readLocalUser: suspend (userId: String) -> User?,
    private val writeLocalUser: suspend (user: User) -> Unit,
    private val fetchRemoteProfile: suspend (userId: String) -> Result<User?>,
    private val createSignedAvatarUrl: suspend (storagePath: String) -> String?,
    private val clockMs: () -> Long = System::currentTimeMillis
) {
    private data class AvatarCacheKey(val userId: String, val storagePath: String)
    private data class CachedSignedUrl(val signedUrl: String, val expiresAtEpochMs: Long) {
        fun isFresh(nowMs: Long): Boolean = nowMs < expiresAtEpochMs - SAFE_MARGIN_MS
    }

    private val _state = MutableStateFlow(UserProfileState())
    val state: StateFlow<UserProfileState> = _state.asStateFlow()

    private val avatarCache = ConcurrentHashMap<AvatarCacheKey, CachedSignedUrl>()
    private val refreshMutex = Mutex()
    private val generation = AtomicLong(0L)

    @Volatile
    private var activeUserId: String? = null

    @Volatile
    private var inMemoryUser: User? = null

    private var sessionJob: Job? = null

    init {
        scope.launch {
            sessionSignals.collectLatest { signal ->
                handleSessionSignal(signal)
            }
        }
    }

    private suspend fun handleSessionSignal(signal: AuthSessionSignal) {
        when (signal) {
            is AuthSessionSignal.Initializing -> {
                if (activeUserId == null && _state.value.currentUser == null) {
                    _state.value = UserProfileState(
                        sessionState = AuthSessionState.Initializing,
                        userId = null,
                        profileState = ProfileLoadState.Loading,
                        avatarState = AvatarLoadState.None
                    )
                }
            }

            is AuthSessionSignal.Authenticated -> {
                val previousUserId = activeUserId
                if (previousUserId != null && previousUserId != signal.userId) {
                    clearUserContextForSwitch(signal.userId)
                }
                activeUserId = signal.userId
                val current = _state.value
                val wasRefreshFailure = current.sessionState == AuthSessionState.RefreshFailure
                _state.update {
                    it.copy(
                        sessionState = AuthSessionState.Authenticated,
                        userId = signal.userId
                    )
                }
                val needsFetch = previousUserId != signal.userId ||
                    current.profileState is ProfileLoadState.Loading ||
                    current.profileState is ProfileLoadState.Unauthenticated ||
                    current.profileState is ProfileLoadState.Error ||
                    (wasRefreshFailure && current.avatarState is AvatarLoadState.Error)
                if (needsFetch) {
                    sessionJob?.cancel()
                    sessionJob = scope.launch {
                        refreshProfile(force = previousUserId != signal.userId)
                    }
                }
            }

            is AuthSessionSignal.RefreshFailure -> {
                sessionJob?.cancel()
                generation.incrementAndGet()
                val retainedUserId = activeUserId ?: currentIdentity()?.userId ?: _state.value.userId
                if (retainedUserId == null) {
                    _state.value = UserProfileState.unauthenticated()
                    return
                }
                val retainedUser = _state.value.currentUser
                    ?: inMemoryUser?.takeIf { it.id == retainedUserId }
                    ?: runCatching { readLocalUser(retainedUserId) }.getOrNull()
                if (retainedUser != null) {
                    inMemoryUser = retainedUser
                }
                _state.update { current ->
                    val nextProfileState = when {
                        current.profileState is ProfileLoadState.Ready -> current.profileState
                        retainedUser != null -> ProfileLoadState.Ready(retainedUser)
                        else -> ProfileLoadState.Error(retainedUserId, "Session refresh failed", null)
                    }
                    val nextAvatarState = when (current.avatarState) {
                        is AvatarLoadState.Ready -> current.avatarState
                        is AvatarLoadState.Loading -> AvatarLoadState.Error
                        else -> current.avatarState
                    }
                    current.copy(
                        sessionState = AuthSessionState.RefreshFailure,
                        userId = retainedUserId,
                        profileState = nextProfileState,
                        avatarState = nextAvatarState
                    )
                }
            }

            is AuthSessionSignal.NotAuthenticated -> {
                onLogout()
            }
        }
    }

    private fun clearUserContextForSwitch(newUserId: String) {
        sessionJob?.cancel()
        generation.incrementAndGet()
        avatarCache.clear()
        inMemoryUser = null
        activeUserId = newUserId
        _state.value = UserProfileState(
            sessionState = AuthSessionState.Authenticated,
            userId = newUserId,
            profileState = ProfileLoadState.Loading,
            avatarState = AvatarLoadState.None
        )
    }

    fun onLogout() {
        sessionJob?.cancel()
        generation.incrementAndGet()
        activeUserId = null
        inMemoryUser = null
        avatarCache.clear()
        _state.value = UserProfileState.unauthenticated()
    }

    /**
     * Immediately updates centralized state after login, registration, or profile update.
     */
    suspend fun onUserAuthenticatedOrUpdated(user: User) {
        val currentId = currentIdentity()?.userId ?: user.id
        if (currentId != user.id) return
        val previousUserId = activeUserId
        if (previousUserId != null && previousUserId != user.id) {
            avatarCache.clear()
        } else {
            // Invalidate any cached signed URL for this user if storagePath changed
            val newPath = user.profilePhotoUri?.takeIf { it.isNotBlank() }
            avatarCache.keys.removeIf { key -> key.userId == user.id && key.storagePath != newPath }
        }
        activeUserId = user.id
        inMemoryUser = user
        safeWriteLocalUser(user)
        val gen = generation.incrementAndGet()
        val avatarState = resolveAvatarState(user.id, user.profilePhotoUri, force = false, expectedGeneration = gen)
        if (!isStillValidOwner(user.id, gen)) return
        _state.value = UserProfileState(
            sessionState = AuthSessionState.Authenticated,
            userId = user.id,
            profileState = ProfileLoadState.Ready(user),
            avatarState = avatarState
        )
    }

    suspend fun getCachedOrFetchSignedAvatarUrl(
        userId: String?,
        storagePath: String?,
        force: Boolean = false
    ): String? {
        if (storagePath.isNullOrBlank()) return null
        if (UserProfileState.isDirectLoadableUri(storagePath)) return storagePath
        val resolvedUserId = userId ?: activeUserId ?: currentIdentity()?.userId
        if (resolvedUserId != null) {
            val key = AvatarCacheKey(resolvedUserId, storagePath)
            val now = clockMs()
            if (!force) {
                avatarCache[key]?.takeIf { it.isFresh(now) }?.let { return it.signedUrl }
            }
            val signed = createSignedAvatarUrl(storagePath) ?: return null
            if (currentIdentity()?.userId == resolvedUserId || activeUserId == resolvedUserId) {
                avatarCache[key] = CachedSignedUrl(signed, now + SIGNED_URL_TTL_MS)
            }
            return signed
        }
        return createSignedAvatarUrl(storagePath)
    }

    suspend fun refreshProfile(force: Boolean = false): Result<User?> = refreshMutex.withLock {
        val current = _state.value
        if (current.sessionState == AuthSessionState.RefreshFailure && current.userId != null) {
            val fallbackUser = current.currentUser
                ?: inMemoryUser?.takeIf { it.id == current.userId }
                ?: runCatching { readLocalUser(current.userId) }.getOrNull()
            if (fallbackUser != null && current.currentUser == null) {
                inMemoryUser = fallbackUser
                _state.update {
                    it.copy(profileState = ProfileLoadState.Ready(fallbackUser))
                }
            }
            return@withLock Result.success(fallbackUser)
        }

        val identity = currentIdentity() ?: awaitIdentity()
        if (identity == null) {
            if (_state.value.sessionState == AuthSessionState.RefreshFailure && _state.value.userId != null) {
                return@withLock Result.success(_state.value.currentUser)
            }
            onLogout()
            return@withLock Result.success(null)
        }

        val userId = identity.userId
        if (activeUserId != null && activeUserId != userId) {
            clearUserContextForSwitch(userId)
        }
        activeUserId = userId

        val now = clockMs()
        val stateSnap = _state.value
        if (!force &&
            stateSnap.userId == userId &&
            stateSnap.profileState is ProfileLoadState.Ready
        ) {
            val existingUser = stateSnap.profileState.user
            val photoPath = existingUser.profilePhotoUri?.takeIf { it.isNotBlank() }
            val avatarReady = when {
                photoPath == null -> stateSnap.avatarState is AvatarLoadState.None
                UserProfileState.isDirectLoadableUri(photoPath) -> stateSnap.avatarState is AvatarLoadState.Ready
                else -> {
                    val cached = avatarCache[AvatarCacheKey(userId, photoPath)]
                    stateSnap.avatarState is AvatarLoadState.Ready && cached != null && cached.isFresh(now)
                }
            }
            if (avatarReady) {
                return@withLock Result.success(existingUser)
            }
        }

        val reqGen = generation.incrementAndGet()
        val seedUser = _state.value.currentUser?.takeIf { it.id == userId }
            ?: inMemoryUser?.takeIf { it.id == userId }
            ?: runCatching { readLocalUser(userId) }.getOrNull()

        if (seedUser != null) {
            inMemoryUser = seedUser
        }

        val initialPhotoPath = seedUser?.profilePhotoUri?.takeIf { it.isNotBlank() }
        val initialAvatarState = when {
            initialPhotoPath == null -> AvatarLoadState.None
            UserProfileState.isDirectLoadableUri(initialPhotoPath) -> AvatarLoadState.Ready(initialPhotoPath)
            else -> {
                val cached = avatarCache[AvatarCacheKey(userId, initialPhotoPath)]?.takeIf { it.isFresh(now) }
                if (cached != null) AvatarLoadState.Ready(cached.signedUrl) else AvatarLoadState.Loading
            }
        }

        _state.update { prev ->
            UserProfileState(
                sessionState = AuthSessionState.Authenticated,
                userId = userId,
                profileState = if (seedUser != null) ProfileLoadState.Ready(seedUser) else ProfileLoadState.Loading,
                avatarState = if (prev.userId == userId && prev.avatarState is AvatarLoadState.Ready && !force) {
                    prev.avatarState
                } else {
                    initialAvatarState
                }
            )
        }

        val remoteResult = try {
            fetchRemoteProfile(userId)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Result.failure(t)
        }

        if (!isStillValidOwner(userId, reqGen)) {
            return@withLock Result.success(_state.value.currentUser)
        }

        return@withLock remoteResult.fold(
            onSuccess = { remoteUser ->
                if (remoteUser != null) {
                    if (!isStillValidOwner(userId, reqGen)) return@fold Result.success(_state.value.currentUser)
                    val previousPath = inMemoryUser?.takeIf { it.id == userId }?.profilePhotoUri
                    if (previousPath != remoteUser.profilePhotoUri) {
                        avatarCache.keys.removeIf { it.userId == userId && it.storagePath != remoteUser.profilePhotoUri }
                    }
                    inMemoryUser = remoteUser
                    safeWriteLocalUser(remoteUser)
                    val resolvedAvatar = resolveAvatarState(
                        userId = userId,
                        photoPath = remoteUser.profilePhotoUri,
                        force = force && _state.value.avatarState is AvatarLoadState.Error,
                        expectedGeneration = reqGen
                    )
                    if (isStillValidOwner(userId, reqGen)) {
                        _state.value = UserProfileState(
                            sessionState = AuthSessionState.Authenticated,
                            userId = userId,
                            profileState = ProfileLoadState.Ready(remoteUser),
                            avatarState = resolvedAvatar
                        )
                    }
                    Result.success(remoteUser)
                } else {
                    val fallbackUser = User(
                        id = userId,
                        name = identity.fallbackName,
                        email = identity.email,
                        birthDate = identity.fallbackBirthDate,
                        profilePhotoUri = null
                    )
                    if (isStillValidOwner(userId, reqGen)) {
                        inMemoryUser = fallbackUser
                        safeWriteLocalUser(fallbackUser)
                        _state.value = UserProfileState(
                            sessionState = AuthSessionState.Authenticated,
                            userId = userId,
                            profileState = ProfileLoadState.Empty(
                                userId = userId,
                                fallbackName = identity.fallbackName,
                                fallbackEmail = identity.email
                            ),
                            avatarState = AvatarLoadState.None
                        )
                    }
                    Result.success(fallbackUser)
                }
            },
            onFailure = { error ->
                if (isStillValidOwner(userId, reqGen)) {
                    val fallbackAvatar = if (seedUser != null) {
                        resolveAvatarState(
                            userId = userId,
                            photoPath = seedUser.profilePhotoUri,
                            force = false,
                            expectedGeneration = reqGen
                        )
                    } else {
                        AvatarLoadState.None
                    }
                    if (isStillValidOwner(userId, reqGen)) {
                        _state.value = UserProfileState(
                            sessionState = AuthSessionState.Authenticated,
                            userId = userId,
                            profileState = ProfileLoadState.Error(
                                userId = userId,
                                message = error.message,
                                cachedUser = seedUser
                            ),
                            avatarState = fallbackAvatar
                        )
                    }
                }
                if (seedUser != null) Result.success(seedUser) else Result.failure(error)
            }
        )
    }

    private suspend fun resolveAvatarState(
        userId: String,
        photoPath: String?,
        force: Boolean,
        expectedGeneration: Long
    ): AvatarLoadState {
        val cleanPath = photoPath?.takeIf { it.isNotBlank() } ?: return AvatarLoadState.None
        if (UserProfileState.isDirectLoadableUri(cleanPath)) {
            return AvatarLoadState.Ready(cleanPath)
        }
        val key = AvatarCacheKey(userId, cleanPath)
        val now = clockMs()
        if (!force) {
            avatarCache[key]?.takeIf { it.isFresh(now) }?.let {
                return AvatarLoadState.Ready(it.signedUrl)
            }
        }
        _state.update { current ->
            if (current.userId == userId && current.avatarState !is AvatarLoadState.Ready) {
                current.copy(avatarState = AvatarLoadState.Loading)
            } else {
                current
            }
        }
        val signedUrl = try {
            createSignedAvatarUrl(cleanPath)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
        if (!isStillValidOwner(userId, expectedGeneration)) {
            return AvatarLoadState.None
        }
        return if (!signedUrl.isNullOrBlank()) {
            avatarCache[key] = CachedSignedUrl(signedUrl, clockMs() + SIGNED_URL_TTL_MS)
            AvatarLoadState.Ready(signedUrl)
        } else {
            AvatarLoadState.Error
        }
    }
    private suspend fun safeWriteLocalUser(user: User) {
        try {
            writeLocalUser(user)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
        }
    }


    private fun isStillValidOwner(expectedUserId: String, expectedGeneration: Long): Boolean {
        if (generation.get() != expectedGeneration) return false
        if (activeUserId != expectedUserId) return false
        val currentId = currentIdentity()?.userId
        return currentId == expectedUserId
    }

    companion object {
        const val SIGNED_URL_TTL_MS = 60 * 60 * 1000L // 60 minutes
        const val SAFE_MARGIN_MS = 15 * 60 * 1000L // Refresh 15 minutes before expiry (45-min valid window)
    }
}

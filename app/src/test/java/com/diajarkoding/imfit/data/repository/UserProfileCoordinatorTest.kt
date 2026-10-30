package com.diajarkoding.imfit.data.repository

import com.diajarkoding.imfit.domain.model.AuthSessionState
import com.diajarkoding.imfit.domain.model.AvatarLoadState
import com.diajarkoding.imfit.domain.model.ProfileLoadState
import com.diajarkoding.imfit.domain.model.User
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import kotlinx.coroutines.yield
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class UserProfileCoordinatorTest {

    @Test
    fun accountSwitchCancelsStaleRequestClearsAvatarCacheAndIsolatesUsers() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val signals = MutableSharedFlow<AuthSessionSignal>(replay = 1)
            var activeIdentity: AuthSessionSignal.Authenticated? =
                AuthSessionSignal.Authenticated(userId = "user_A", email = "a@imfit.com", fallbackName = "User A")
            val localDb = ConcurrentHashMap<String, User>()
            val signedUrlCalls = AtomicInteger(0)
            var gateUserARefresh: CompletableDeferred<User?>? = null

            val userA = User("user_A", "User A", "a@imfit.com", "1999-01-01", "user_A/avatar.jpg")
            val userB = User("user_B", "User B", "b@imfit.com", "2000-02-02", "user_B/avatar.jpg")

            val coordinator = UserProfileCoordinator(
                scope = scope,
                sessionSignals = signals,
                currentIdentity = { activeIdentity },
                awaitIdentity = { activeIdentity },
                readLocalUser = { localDb[it] },
                writeLocalUser = { user -> localDb[user.id] = user },
                fetchRemoteProfile = { userId ->
                    when (userId) {
                        "user_A" -> {
                            val gate = gateUserARefresh
                            if (gate != null) Result.success(gate.await()) else Result.success(userA)
                        }
                        "user_B" -> Result.success(userB)
                        else -> Result.success(null)
                    }
                },
                createSignedAvatarUrl = { path ->
                    val count = signedUrlCalls.incrementAndGet()
                    "https://signed.imfit.com/$path?token=$count"
                }
            )

            // 1. Authenticate User A
            signals.emit(activeIdentity!!)
            awaitCondition { coordinator.state.value.currentUser?.id == "user_A" }
            assertEquals("User A", coordinator.state.value.displayName)
            assertEquals("https://signed.imfit.com/user_A/avatar.jpg?token=1", coordinator.state.value.resolvedAvatarUrl)

            // 2. Start a slow force refresh for User A, then switch to User B before User A's response arrives
            val staleUserADeferred = CompletableDeferred<User?>()
            gateUserARefresh = staleUserADeferred

            // Switch identity to User B while User A's stale response is later completed
            activeIdentity = AuthSessionSignal.Authenticated(userId = "user_B", email = "b@imfit.com", fallbackName = "User B")
            signals.emit(activeIdentity!!)
            staleUserADeferred.complete(userA.copy(name = "Stale User A Overwrite"))

            awaitCondition { coordinator.state.value.currentUser?.id == "user_B" }
            assertEquals("User B", coordinator.state.value.displayName)
            assertEquals("b@imfit.com", coordinator.state.value.email)
            assertEquals("https://signed.imfit.com/user_B/avatar.jpg?token=2", coordinator.state.value.resolvedAvatarUrl)
            assertFalse("Stale User A response must never overwrite local DB after switch",
                localDb["user_A"]?.name == "Stale User A Overwrite")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun refreshFailureRetainsProfileAndAvatarWithoutForcingLogoutAndRecoversCleanly() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val signals = MutableSharedFlow<AuthSessionSignal>(replay = 1)
            val identityA = AuthSessionSignal.Authenticated("user_A", "a@imfit.com", "User A")
            var activeIdentity: AuthSessionSignal.Authenticated? = identityA
            val remoteFetchCalls = AtomicInteger(0)
            val signedUrlCalls = AtomicInteger(0)
            val userA = User("user_A", "User A", "a@imfit.com", "1999-01-01", "user_A/photo.jpg")

            val coordinator = UserProfileCoordinator(
                scope = scope,
                sessionSignals = signals,
                currentIdentity = { activeIdentity },
                awaitIdentity = { activeIdentity },
                readLocalUser = { null },
                writeLocalUser = {},
                fetchRemoteProfile = {
                    remoteFetchCalls.incrementAndGet()
                    Result.success(userA)
                },
                createSignedAvatarUrl = { path ->
                    val n = signedUrlCalls.incrementAndGet()
                    "https://signed.imfit.com/$path?n=$n"
                }
            )

            signals.emit(identityA)
            awaitCondition { coordinator.state.value.profileState is ProfileLoadState.Ready }
            assertEquals(1, remoteFetchCalls.get())
            assertEquals(1, signedUrlCalls.get())
            assertTrue(coordinator.state.value.hasValidatedRemoteSession)
            assertTrue(coordinator.state.value.hasRetainedSessionContext)

            // Emit SessionStatus.RefreshFailure (e.g. temporary offline token refresh failure)
            activeIdentity = null
            signals.emit(AuthSessionSignal.RefreshFailure)

            val duringFailure = coordinator.state.value
            assertEquals(AuthSessionState.RefreshFailure, duringFailure.sessionState)
            assertTrue("UI context must be retained during RefreshFailure", duringFailure.hasRetainedSessionContext)
            assertFalse("Remote session must not be marked validated during RefreshFailure", duringFailure.hasValidatedRemoteSession)
            assertEquals("User A", duringFailure.displayName)
            assertEquals("https://signed.imfit.com/user_A/photo.jpg?n=1", duringFailure.resolvedAvatarUrl)

            // Calling refreshProfile(force = false) during RefreshFailure must not wipe state or hit remote
            val refreshDuringFailure = coordinator.refreshProfile(force = false)
            assertEquals("User A", refreshDuringFailure.getOrNull()?.name)
            assertEquals(1, remoteFetchCalls.get())

            // Recover back to Authenticated -> re-validates session and reuses fresh avatar cache
            activeIdentity = identityA
            signals.emit(identityA)
            val recovered = coordinator.state.value
            assertEquals(AuthSessionState.Authenticated, recovered.sessionState)
            assertTrue(recovered.hasValidatedRemoteSession)
            assertEquals("User A", recovered.displayName)
            assertEquals("https://signed.imfit.com/user_A/photo.jpg?n=1", recovered.resolvedAvatarUrl)
            assertEquals("Cached signed URL must be reused on recovery", 1, signedUrlCalls.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun repeatedRefreshAndAvatarPathChangesRespectCacheTtlAndInvalidation() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val signals = MutableSharedFlow<AuthSessionSignal>(replay = 1)
            val identityA = AuthSessionSignal.Authenticated("user_A", "a@imfit.com", "User A")
            val nowMs = AtomicLong(1_000_000L)
            val remoteFetchCalls = AtomicInteger(0)
            val signedUrlCalls = AtomicInteger(0)
            var currentRemoteUser = User("user_A", "User A", "a@imfit.com", null, null)

            val coordinator = UserProfileCoordinator(
                scope = scope,
                sessionSignals = signals,
                currentIdentity = { identityA },
                awaitIdentity = { identityA },
                readLocalUser = { null },
                writeLocalUser = {},
                fetchRemoteProfile = {
                    remoteFetchCalls.incrementAndGet()
                    Result.success(currentRemoteUser)
                },
                createSignedAvatarUrl = { path ->
                    if (path.contains("broken")) null
                    else "https://signed.imfit.com/$path?c=${signedUrlCalls.incrementAndGet()}"
                },
                clockMs = { nowMs.get() }
            )

            // 1. User without avatar -> AvatarLoadState.None, 0 signed URL calls
            signals.emit(identityA)
            awaitCondition { coordinator.state.value.profileState is ProfileLoadState.Ready }
            assertEquals(AvatarLoadState.None, coordinator.state.value.avatarState)
            assertFalse(coordinator.state.value.isAvatarLoading)
            assertEquals(0, signedUrlCalls.get())

            // 2. Update user with avatar path "user_A/v1.jpg"
            currentRemoteUser = currentRemoteUser.copy(profilePhotoUri = "user_A/v1.jpg")
            coordinator.onUserAuthenticatedOrUpdated(currentRemoteUser)
            assertEquals(AvatarLoadState.Ready("https://signed.imfit.com/user_A/v1.jpg?c=1"), coordinator.state.value.avatarState)
            assertEquals(1, signedUrlCalls.get())

            // 3. Repeated refreshProfile(force = false) within 45-min window -> 0 extra fetches, 0 shimmer
            coordinator.refreshProfile(force = false)
            coordinator.refreshProfile(force = false)
            assertEquals(1, remoteFetchCalls.get())
            assertEquals(1, signedUrlCalls.get())
            assertFalse(coordinator.state.value.isProfileLoading)

            // 4. Change avatar path to "user_A/v2.jpg" -> invalidates v1 cache and fetches v2 signed URL
            currentRemoteUser = currentRemoteUser.copy(profilePhotoUri = "user_A/v2.jpg")
            coordinator.onUserAuthenticatedOrUpdated(currentRemoteUser)
            assertEquals(AvatarLoadState.Ready("https://signed.imfit.com/user_A/v2.jpg?c=2"), coordinator.state.value.avatarState)
            assertEquals(2, signedUrlCalls.get())

            // 5. Advance clock past 45-min safe window -> next refresh fetches fresh signed URL
            nowMs.addAndGet(46 * 60 * 1000L)
            coordinator.refreshProfile(force = false)
            assertEquals(AvatarLoadState.Ready("https://signed.imfit.com/user_A/v2.jpg?c=3"), coordinator.state.value.avatarState)
            assertEquals(3, signedUrlCalls.get())

            // 6. Broken avatar path -> transitions to AvatarLoadState.Error (not stuck in Loading)
            currentRemoteUser = currentRemoteUser.copy(profilePhotoUri = "user_A/broken.jpg")
            coordinator.onUserAuthenticatedOrUpdated(currentRemoteUser)
            assertEquals(AvatarLoadState.Error, coordinator.state.value.avatarState)
            assertFalse(coordinator.state.value.isAvatarLoading)
            assertNull(coordinator.state.value.resolvedAvatarUrl)
        } finally {
            scope.cancel()
        }
    }

    private suspend fun awaitCondition(predicate: () -> Boolean) {
        withTimeout(2_000) {
            while (!predicate()) {
                yield()
            }
        }
    }
}

package com.diajarkoding.imfit.domain.model

import com.diajarkoding.imfit.presentation.ui.home.SectionLoadState
import com.diajarkoding.imfit.presentation.ui.home.toRefreshingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Step1ContractsAndStateTest {

    @Test
    fun refreshFailureRetainsUiContextWithoutValidatingRemoteSession() {
        val user = User(id = "u1", name = "Iskan", email = "iskan@imfit.com")
        val state = UserProfileState(
            sessionState = AuthSessionState.RefreshFailure,
            userId = user.id,
            profileState = ProfileLoadState.Ready(user),
            avatarState = AvatarLoadState.Ready("https://example.com/signed.jpg")
        )

        assertTrue(state.hasRetainedSessionContext)
        assertFalse(state.hasValidatedRemoteSession)
        assertEquals("Iskan", state.displayName)
        assertEquals("https://example.com/signed.jpg", state.resolvedAvatarUrl)
    }

    @Test
    fun fromDirectUserDoesNotExposePrivateStoragePathAsResolvedAvatarUrl() {
        val privatePathUser = User(
            id = "u1",
            name = "Iskan",
            email = "iskan@imfit.com",
            profilePhotoUri = "u1/avatar-123.jpg"
        )
        val privateState = UserProfileState.fromDirectUser(privatePathUser)
        assertEquals(AvatarLoadState.None, privateState.avatarState)
        assertNull(privateState.resolvedAvatarUrl)

        val publicUrlUser = privatePathUser.copy(profilePhotoUri = "https://cdn.imfit.com/avatar.jpg")
        val publicState = UserProfileState.fromDirectUser(publicUrlUser)
        assertEquals(AvatarLoadState.Ready("https://cdn.imfit.com/avatar.jpg"), publicState.avatarState)
        assertEquals("https://cdn.imfit.com/avatar.jpg", publicState.resolvedAvatarUrl)
    }

    @Test
    fun toRefreshingStatePreservesExistingDataInsteadOfResettingToLoading() {
        val loaded: SectionLoadState<String?> = SectionLoadState.Success(data = null, isRefreshing = false, isFromRemote = true)
        val refreshing = loaded.toRefreshingState()
        assertTrue(refreshing is SectionLoadState.Success)
        val success = refreshing as SectionLoadState.Success
        assertNull(success.data)
        assertTrue(success.isRefreshing)

        val errorWithPrev: SectionLoadState<String> = SectionLoadState.Error(
            errorRes = 1,
            previousData = "cached",
            hasPreviousData = true
        )
        assertEquals(errorWithPrev, errorWithPrev.toRefreshingState())

        val errorWithoutPrev: SectionLoadState<String> = SectionLoadState.Error(
            errorRes = 1,
            previousData = null,
            hasPreviousData = false
        )
        assertEquals(errorWithoutPrev, errorWithoutPrev.toRefreshingState())
    }

    @Test
    fun homeRemoteReadResultDistinguishesRemotePrimaryFromLocalFallback() {
        val primary = HomeRemoteReadResult.RemotePrimary(listOf("Push Day"))
        assertTrue(primary.isFromRemote)
        assertEquals(listOf("Push Day"), primary.data)

        val fallback = HomeRemoteReadResult.LocalFallback(listOf("Push Day"), IllegalStateException("offline"))
        assertFalse(fallback.isFromRemote)
        assertEquals(listOf("Push Day"), fallback.data)
    }
}

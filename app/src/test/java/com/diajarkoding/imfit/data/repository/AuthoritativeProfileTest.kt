package com.diajarkoding.imfit.data.repository

import com.diajarkoding.imfit.data.remote.online.*
import com.diajarkoding.imfit.domain.model.*
import com.diajarkoding.imfit.domain.repository.AuthRepository
import com.diajarkoding.imfit.domain.repository.avatarPresentationUrl
import com.diajarkoding.imfit.domain.repository.presentAvatar
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import javax.inject.Provider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthoritativeProfileTest {
    private class Fixture {
        val auth = OnlineAuth().also { it.signIn("A", "token") }
        val reads = mockk<RemoteReadGateway> { every { auth } returns this@Fixture.auth }
        val changes = OnlineMutationChanges(auth)
        val repository = AuthRepositoryImpl(mockk(), Provider { reads }, Provider { changes }, mockk(), mockk())
        val row = RemoteProfile("A", "Server canonical", "fixture@example.invalid", null, "A/avatar.jpg")
    }

    @Test fun `update returns server representation and publishes only after row`() = runTest {
        val fixture = Fixture()
        val response = CompletableDeferred<RemoteProfile>()
        val patch = ProfilePatch(name = ProfileField.Value("Input"))
        coEvery { fixture.reads.updateProfile(patch) } coAnswers { response.await() }
        val events = mutableListOf<OnlineMutationChange>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { fixture.changes.events.collect { events += it } }
        val update = async { fixture.repository.updateProfile(patch) }
        runCurrent()
        assertTrue(events.isEmpty())
        assertFalse(update.isCompleted)
        response.complete(fixture.row)
        val result = update.await().getOrThrow()
        assertEquals("Server canonical", result.name)
        assertEquals("A/avatar.jpg", result.profilePhotoUri)
        runCurrent()
        assertEquals(listOf(ProfileChanged(fixture.auth.capture().identity)), events)
    }

    @Test fun `failed patch and forged returned owner never publish success`() = runTest {
        for (wrongOwner in listOf(false, true)) {
            val fixture = Fixture()
            coEvery { fixture.reads.updateProfile(any()) } coAnswers {
                if (wrongOwner) fixture.row.copy(id = "B") else throw OnlineFailure(RemoteError.Network)
            }
            val events = mutableListOf<OnlineMutationChange>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { fixture.changes.events.collect { events += it } }
            assertTrue(fixture.repository.updateProfile(ProfilePatch(avatarPath = ProfileField.Value("A/new.jpg"))).isFailure)
            runCurrent()
            assertTrue(events.isEmpty())
        }
    }

    @Test fun `late A response cannot update profile or publish to new A login`() = runTest {
        val fixture = Fixture()
        val response = CompletableDeferred<RemoteProfile>()
        coEvery { fixture.reads.updateProfile(any()) } coAnswers { response.await() }
        val events = mutableListOf<OnlineMutationChange>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { fixture.changes.events.collect { events += it } }
        val update = async { fixture.repository.updateProfile(ProfilePatch(name = ProfileField.Value("Input"))) }
        runCurrent()
        fixture.auth.signIn("B", "B"); fixture.auth.signIn("A", "new-A")
        response.complete(fixture.row)
        assertTrue(runCatching { update.await() }.exceptionOrNull() is StaleAuthException)
        assertTrue(events.isEmpty())
    }

    @Test fun `cancellation propagates rather than profile result failure`() = runTest {
        val fixture = Fixture()
        coEvery { fixture.reads.updateProfile(any()) } throws CancellationException("cancelled")
        assertTrue(runCatching { fixture.repository.updateProfile(ProfilePatch(name = ProfileField.Value("Input"))) }.exceptionOrNull() is CancellationException)
    }

    @Test fun `empty patch returns fetched profile without invalidation`() = runTest {
        val fixture = Fixture()
        coEvery { fixture.reads.updateProfile(ProfilePatch()) } returns fixture.row
        val events = mutableListOf<OnlineMutationChange>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { fixture.changes.events.collect { events += it } }
        assertEquals("Server canonical", fixture.repository.updateProfile(ProfilePatch()).getOrThrow().name)
        assertTrue(events.isEmpty())
    }

    @Test fun `avatar signing failure affects presentation photo only and cancellation propagates`() = runTest {
        val repository = mockk<AuthRepository>()
        val canonical = User("A", "Kept name", "fixture@example.invalid", "1990-01-01", "A/avatar.jpg")
        coEvery { repository.getSignedAvatarUrl("A/avatar.jpg") } throws IllegalStateException("storage unavailable")
        val presentation = repository.presentAvatar(canonical)
        assertEquals(canonical.copy(profilePhotoUri = null), presentation)
        assertEquals("A/avatar.jpg", canonical.profilePhotoUri)
        coEvery { repository.getSignedAvatarUrl("A/avatar.jpg") } returns "https://signed.invalid/avatar"
        assertEquals("https://signed.invalid/avatar", repository.avatarPresentationUrl(canonical.profilePhotoUri))
        coEvery { repository.getSignedAvatarUrl(any()) } throws CancellationException("cancelled")
        assertTrue(runCatching { repository.presentAvatar(canonical) }.exceptionOrNull() is CancellationException)
    }
}

package com.diajarkoding.imfit.data.repository

import com.diajarkoding.imfit.data.local.FakeUserDataSource
import com.diajarkoding.imfit.domain.model.ProfileField
import com.diajarkoding.imfit.domain.model.ProfilePatch
import org.junit.Assert.*
import org.junit.Test

class LegacyIndependentProfileTest {
    @Test fun `demo patch preserves omitted fields and clears explicit nullable values`() {
        val email = "profile-${java.util.UUID.randomUUID()}@example.invalid"
        val original = FakeUserDataSource.register("Original", email, "fixture-password", "1990-01-01").getOrThrow()
        try {
            val avatar = "${original.id}/avatar.jpg"
            val populated = FakeUserDataSource.updateProfile(ProfilePatch(avatarPath = ProfileField.Value(avatar))).getOrThrow()
            assertEquals("1990-01-01", populated.birthDate)
            val renamed = FakeUserDataSource.updateProfile(ProfilePatch(name = ProfileField.Value("Renamed"))).getOrThrow()
            assertEquals("Renamed", renamed.name)
            assertEquals(avatar, renamed.profilePhotoUri)
            assertEquals(original.email, renamed.email)
            assertEquals(original.id, renamed.id)
            val cleared = FakeUserDataSource.updateProfile(ProfilePatch(
                birthDate = ProfileField.Value(null), avatarPath = ProfileField.Value(null))).getOrThrow()
            assertNull(cleared.birthDate)
            assertNull(cleared.profilePhotoUri)
            assertEquals("Renamed", cleared.name)
            assertEquals(cleared, FakeUserDataSource.getCurrentUser())
            FakeUserDataSource.logout()
            assertEquals(cleared, FakeUserDataSource.login(email, "fixture-password").getOrThrow())
        } finally { FakeUserDataSource.logout() }
    }

    @Test fun `invalid avatar patch does not change confirmed demo profile`() {
        val email = "profile-${java.util.UUID.randomUUID()}@example.invalid"
        val original = FakeUserDataSource.register("Original", email, "fixture-password").getOrThrow()
        try {
            assertTrue(FakeUserDataSource.updateProfile(ProfilePatch(avatarPath = ProfileField.Value("foreign/avatar.jpg"))).isFailure)
            assertEquals(original, FakeUserDataSource.getCurrentUser())
            assertTrue(FakeUserDataSource.updateProfile(ProfilePatch(birthDate = ProfileField.Value("not-a-date"))).isFailure)
            assertEquals(original, FakeUserDataSource.getCurrentUser())
        } finally { FakeUserDataSource.logout() }
    }
}

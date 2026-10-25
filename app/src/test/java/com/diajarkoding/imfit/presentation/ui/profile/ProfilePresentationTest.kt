package com.diajarkoding.imfit.presentation.ui.profile

import com.diajarkoding.imfit.presentation.ui.progress.ProgressState
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class ProfilePresentationTest {
    @Test fun firstLoadWithoutIdentityShowsLoading() {
        assertEquals(ProfileDataPresentation.Loading, profileDataPresentation(ProgressState()))
    }

    @Test fun failedLoadWithoutIdentityShowsRetry() {
        assertEquals(ProfileDataPresentation.Unavailable,
            profileDataPresentation(ProgressState(isLoading = false, hasError = true)))
    }

    @Test fun retryWithoutIdentityShowsLoadingNotError() {
        assertEquals(ProfileDataPresentation.Loading,
            profileDataPresentation(ProgressState(isLoading = true, hasError = true)))
    }

    @Test fun historyFailureDoesNotHideKnownProfile() {
        assertEquals(ProfileDataPresentation.Ready,
            profileDataPresentation(ProgressState(userName = "Alex", userEmail = "alex@example.com", isLoading = false, hasError = true)))
    }

    @Test fun reloadKeepsKnownIdentityVisible() {
        assertEquals(ProfileDataPresentation.Ready,
            profileDataPresentation(ProgressState(userName = "Alex", isLoading = true)))
    }

    @Test fun emailOnlyIdentityIsStillAvailable() {
        assertEquals(ProfileDataPresentation.Ready,
            profileDataPresentation(ProgressState(userEmail = "alex@example.com", isLoading = false, hasError = true)))
    }

    @Test fun legitimateEmptyIdentityDoesNotInventLoadFailure() {
        assertEquals(ProfileDataPresentation.Ready, profileDataPresentation(ProgressState(isLoading = false)))
    }

    @Test fun guestWithNoPhotoOrBirthDateIsContent() {
        assertEquals(ProfileDataPresentation.Ready,
            profileDataPresentation(ProgressState(userName = "Guest", isLoading = false)))
    }

    @Test fun isoBirthDateUsesIndonesianMonthName() {
        assertEquals("15 Januari 1998", formatProfileBirthDate("1998-01-15", Locale.forLanguageTag("id-ID"), "—"))
    }

    @Test fun isoBirthDateUsesEnglishMonthName() {
        assertEquals("15 January 1998", formatProfileBirthDate("1998-01-15", Locale.ENGLISH, "—"))
    }

    @Test fun missingBirthDateUsesPlaceholder() {
        for (value in listOf(null, "", "  ")) assertEquals("—", formatProfileBirthDate(value, Locale.ENGLISH, "—"))
    }

    @Test fun legacyOrInvalidDateIsNotReinterpreted() {
        for (value in listOf("15 Januari 1998", "1998-02-31", "unknown")) {
            assertEquals(value, formatProfileBirthDate(value, Locale.ENGLISH, "—"))
        }
    }
}

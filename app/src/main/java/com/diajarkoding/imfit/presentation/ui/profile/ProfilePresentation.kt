package com.diajarkoding.imfit.presentation.ui.profile

import com.diajarkoding.imfit.presentation.ui.progress.ProgressState
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

internal enum class ProfileDataPresentation { Loading, Ready, Unavailable }

internal fun profileDataPresentation(state: ProgressState): ProfileDataPresentation {
    // ProgressState also observes workout history. Its error must not hide known identity.
    val hasIdentity = state.userName.isNotBlank() || state.userEmail.isNotBlank() ||
        !state.userBirthDate.isNullOrBlank() || !state.userProfilePhotoUri.isNullOrBlank()
    return when {
        hasIdentity -> ProfileDataPresentation.Ready
        state.isLoading -> ProfileDataPresentation.Loading
        state.hasError -> ProfileDataPresentation.Unavailable
        else -> ProfileDataPresentation.Ready
    }
}

internal fun formatProfileBirthDate(value: String?, locale: Locale, missingValue: String): String {
    if (value.isNullOrBlank()) return missingValue
    // ISO is the stored contract. Keep legacy text readable rather than inventing a date.
    return runCatching { LocalDate.parse(value).format(DateTimeFormatter.ofPattern("d MMMM yyyy", locale)) }
        .getOrDefault(value)
}

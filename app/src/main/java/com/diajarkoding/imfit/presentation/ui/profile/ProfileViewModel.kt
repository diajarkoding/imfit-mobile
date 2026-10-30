package com.diajarkoding.imfit.presentation.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diajarkoding.imfit.domain.model.AuthSessionState
import com.diajarkoding.imfit.domain.model.ProfileLoadState
import com.diajarkoding.imfit.domain.model.UserProfileState
import com.diajarkoding.imfit.domain.repository.AuthRepository
import com.diajarkoding.imfit.presentation.ui.progress.ProgressState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    val userProfileState: StateFlow<UserProfileState> = authRepository.userProfileState

    val state: StateFlow<ProgressState> = authRepository.userProfileState
        .map { it.toProfileProgressState() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = authRepository.userProfileState.value.toProfileProgressState()
        )

    init {
        loadData()
    }

    fun loadData() {
        viewModelScope.launch {
            authRepository.refreshProfile(force = false)
        }
    }

    fun retry() {
        viewModelScope.launch {
            authRepository.refreshProfile(force = true)
        }
    }

    fun logout(onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            authRepository.logout()
            onComplete()
        }
    }
}

internal fun UserProfileState.toProfileProgressState(): ProgressState {
    val hasError = profileState is ProfileLoadState.Error && currentUser == null
    val userName = when {
        sessionState == AuthSessionState.Unauthenticated -> "Guest"
        currentUser != null -> currentUser!!.name
        else -> ""
    }
    return ProgressState(
        userName = userName,
        userEmail = email,
        userBirthDate = birthDate,
        userProfilePhotoUri = resolvedAvatarUrl,
        isLoading = isProfileLoading,
        hasError = hasError
    )
}

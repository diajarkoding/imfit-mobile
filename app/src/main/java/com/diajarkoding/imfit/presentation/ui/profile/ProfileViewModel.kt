package com.diajarkoding.imfit.presentation.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diajarkoding.imfit.core.data.SessionManager
import com.diajarkoding.imfit.domain.usecase.LogoutUserUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val sessionManager: SessionManager,
    private val logoutUserUseCase: LogoutUserUseCase
) : ViewModel() {

    private val _state = MutableStateFlow(ProfileState())
    val state = _state.asStateFlow()

    fun onEvent(event: ProfileEvent) {
        when (event) {
            ProfileEvent.LogoutButtonPressed -> logout()
        }
    }

    private fun logout() {
        viewModelScope.launch {
            // Panggil API untuk logout (opsional, tapi praktik yang baik)
            logoutUserUseCase()

            // Selalu bersihkan token lokal, terlepas dari hasil API
            sessionManager.clearAuthToken()

            _state.update { it.copy(logoutSuccess = true) }
        }
    }
}
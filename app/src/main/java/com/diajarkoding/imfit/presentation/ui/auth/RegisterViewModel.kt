package com.diajarkoding.imfit.presentation.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RegisterViewModel @Inject constructor() : ViewModel() {

    private val _state = MutableStateFlow(RegisterState())
    val state = _state.asStateFlow()

    fun onEvent(event: RegisterEvent) {
        when (event) {
            is RegisterEvent.ProfilePictureChanged -> {
                _state.update { it.copy(profileImageUri = event.uri) }
            }

            is RegisterEvent.FullnameChanged -> _state.update { it.copy(fullname = event.value) }
            is RegisterEvent.UsernameChanged -> _state.update { it.copy(username = event.value) }
            is RegisterEvent.EmailChanged -> _state.update { it.copy(email = event.value) }
            is RegisterEvent.PasswordChanged -> _state.update { it.copy(password = event.value) }
            is RegisterEvent.DateOfBirthChanged -> _state.update { it.copy(dateOfBirth = event.value) }
            RegisterEvent.RegisterButtonPressed -> register()
            else -> {}
        }
    }

    private fun register() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            delay(2000) // Simulasi panggilan jaringan

            val currentState = _state.value
            if (currentState.fullname.isBlank() || currentState.username.isBlank() || currentState.email.isBlank() || currentState.password.isBlank()) {
                _state.update { it.copy(isLoading = false, error = "Semua kolom wajib diisi.") }
                return@launch
            }

            // Simulasi berhasil
            _state.update { it.copy(isLoading = false, registerSuccess = true) }
        }
    }
}
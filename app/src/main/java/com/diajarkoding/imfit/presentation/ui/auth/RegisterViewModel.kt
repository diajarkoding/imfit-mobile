package com.diajarkoding.imfit.presentation.ui.auth

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.core.utils.Validator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RegisterViewModel @Inject constructor(
    private val application: Application
) : ViewModel() {
    private val _state = MutableStateFlow(RegisterState())
    val state = _state.asStateFlow()

    fun onEvent(event: RegisterEvent) {
        when (event) {
            // Saat pengguna mengetik, hapus error untuk field tersebut
            is RegisterEvent.FullnameChanged -> _state.update {
                it.copy(
                    fullname = event.value,
                    fullnameError = null
                )
            }

            is RegisterEvent.UsernameChanged -> _state.update {
                it.copy(
                    username = event.value,
                    usernameError = null
                )
            }

            is RegisterEvent.EmailChanged -> _state.update {
                it.copy(
                    email = event.value,
                    emailError = null
                )
            }

            is RegisterEvent.PasswordChanged -> _state.update {
                it.copy(
                    password = event.value,
                    passwordError = null
                )
            }

            is RegisterEvent.DateOfBirthChanged -> _state.update {
                it.copy(
                    dateOfBirth = event.value,
                    dateOfBirthError = null
                )
            }

            is RegisterEvent.ProfilePictureChanged -> _state.update { it.copy(profileImageUri = event.uri) }
            RegisterEvent.RegisterButtonPressed -> validateAndRegister()
            else -> {}
        }
    }

    private fun validateAndRegister() {
        val currentState = _state.value
        var hasError = false

        // Reset semua error sebelum validasi ulang
        _state.update {
            it.copy(
                fullnameError = null,
                usernameError = null,
                emailError = null,
                passwordError = null,
                dateOfBirthError = null
            )
        }

        // --- Validasi Full Name ---
        if (!Validator.isNotEmpty(currentState.fullname)) {
            _state.update { it.copy(fullnameError = application.getString(R.string.validator_fullName)) }
            hasError = true
        }

        // --- Validasi Username ---
        if (!Validator.isNotEmpty(currentState.username)) {
            _state.update { it.copy(usernameError = application.getString(R.string.validator_username)) }
            hasError = true
        }

        // --- Validasi Email ---
        if (!Validator.isNotEmpty(currentState.email)) {
            _state.update { it.copy(emailError = application.getString(R.string.validator_email)) }
            hasError = true
        } else if (!Validator.isEmailValid(currentState.email)) {
            _state.update { it.copy(emailError = application.getString(R.string.validator_email_format)) }
            hasError = true
        }

        // --- Validasi Password ---
        if (!Validator.isNotEmpty(currentState.password)) {
            _state.update { it.copy(passwordError = application.getString(R.string.validator_password)) }
            hasError = true
        } else if (!Validator.isPasswordValid(currentState.password)) {
            _state.update { it.copy(passwordError = application.getString(R.string.validator_password_format)) }
            hasError = true
        }

        // --- Validasi Date of Birth ---
        if (!Validator.isNotEmpty(currentState.dateOfBirth)) {
            _state.update { it.copy(dateOfBirthError = application.getString(R.string.validator_dateOfBirth)) }
            hasError = true
        }

        // Hentikan proses jika ada error
        if (hasError) {
            return
        }

        // Lanjutkan ke proses registrasi jika tidak ada error
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            delay(2000)
            _state.update { it.copy(isLoading = false, registerSuccess = true) }
        }
    }
}
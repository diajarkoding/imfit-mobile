package com.diajarkoding.imfit.presentation.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diajarkoding.imfit.core.utils.Validator
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
            // Saat pengguna mengetik, hapus error untuk field tersebut
            is RegisterEvent.FullnameChanged -> _state.update { it.copy(fullname = event.value, fullnameError = null) }
            is RegisterEvent.UsernameChanged -> _state.update { it.copy(username = event.value, usernameError = null) }
            is RegisterEvent.EmailChanged -> _state.update { it.copy(email = event.value, emailError = null) }
            is RegisterEvent.PasswordChanged -> _state.update { it.copy(password = event.value, passwordError = null) }
            is RegisterEvent.DateOfBirthChanged -> _state.update { it.copy(dateOfBirth = event.value, dateOfBirthError = null) }
            is RegisterEvent.ProfilePictureChanged -> _state.update { it.copy(profileImageUri = event.uri) }
            RegisterEvent.RegisterButtonPressed -> validateAndRegister()
            else -> {}
        }
    }

    private fun validateAndRegister() {
        val currentState = _state.value

        // Reset semua error sebelum validasi ulang
        var hasError = false
        _state.update { it.copy(
            fullnameError = null,
            usernameError = null,
            emailError = null,
            passwordError = null,
            dateOfBirthError = null
        )}

        if (!Validator.isNotEmpty(currentState.fullname)) {
            _state.update { it.copy(fullnameError = "Nama lengkap tidak boleh kosong.") }
            hasError = true
        }
        if (!Validator.isNotEmpty(currentState.username)) {
            _state.update { it.copy(usernameError = "Nama pengguna tidak boleh kosong.") }
            hasError = true
        }
        if (!Validator.isEmailValid(currentState.email)) {
            _state.update { it.copy(emailError = "Format email tidak valid.") }
            hasError = true
        }
        if (!Validator.isPasswordValid(currentState.password)) {
            _state.update { it.copy(passwordError = "Kata sandi tidak valid.") }
            hasError = true
        }
        if (!Validator.isNotEmpty(currentState.dateOfBirth)) {
            _state.update { it.copy(dateOfBirthError = "Tanggal lahir harus diisi.") }
            hasError = true
        }

        if (hasError) {
            return // Hentikan proses jika ada error
        }

        // Lanjutkan ke proses registrasi jika tidak ada error
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            delay(2000)
            _state.update { it.copy(isLoading = false, registerSuccess = true) }
        }
    }
}
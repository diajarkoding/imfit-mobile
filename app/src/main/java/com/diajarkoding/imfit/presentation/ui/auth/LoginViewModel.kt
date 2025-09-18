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
class LoginViewModel @Inject constructor() : ViewModel() {

    private val _state = MutableStateFlow(LoginState())
    val state = _state.asStateFlow()

    fun onEvent(event: LoginEvent) {
        when (event) {
            // Saat pengguna mengetik, hapus error untuk field tersebut
            is LoginEvent.EmailOrUsernameChanged -> {
                _state.update { it.copy(emailOrUsername = event.value, emailOrUsernameError = null) }
            }
            is LoginEvent.PasswordChanged -> {
                _state.update { it.copy(password = event.value, passwordError = null) }
            }
            is LoginEvent.RememberMeChanged -> {
                _state.update { it.copy(rememberMe = event.value) }
            }
            LoginEvent.LoginButtonPressed -> {
                validateAndLogin()
            }
        }
    }

    private fun validateAndLogin() {
        val currentState = _state.value
        var hasError = false

        // Reset semua error sebelum validasi ulang
        _state.update { it.copy(emailOrUsernameError = null, passwordError = null) }

        if (!Validator.isNotEmpty(currentState.emailOrUsername)) {
            _state.update { it.copy(emailOrUsernameError = "Email atau nama pengguna tidak boleh kosong.") }
            hasError = true
        }

        if (!Validator.isNotEmpty(currentState.password)) {
            _state.update { it.copy(passwordError = "Kata sandi tidak boleh kosong.") }
            hasError = true
        }

        // Hentikan proses jika ada error
        if (hasError) {
            return
        }

        // Lanjutkan ke proses login jika tidak ada error
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            delay(2000)
            _state.update {
                it.copy(isLoading = false, loginSuccess = true)
            }
        }
    }
}
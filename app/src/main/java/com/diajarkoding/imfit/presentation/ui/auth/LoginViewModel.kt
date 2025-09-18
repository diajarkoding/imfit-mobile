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
            is LoginEvent.EmailOrUsernameChanged -> {
                _state.update { it.copy(emailOrUsername = event.value) }
            }

            is LoginEvent.PasswordChanged -> {
                _state.update { it.copy(password = event.value) }
            }

            is LoginEvent.RememberMeChanged -> {
                _state.update { it.copy(rememberMe = event.value) }
            }

            LoginEvent.LoginButtonPressed -> {
                login()
            }
        }
    }

    private fun login() {
        val currentState = _state.value

        // --- VALIDASI ---
        if (!Validator.isNotEmpty(currentState.emailOrUsername)) {
            _state.update { it.copy(error = "Email atau nama pengguna tidak boleh kosong.") }
            return
        }
        if (!Validator.isNotEmpty(currentState.password)) {
            _state.update { it.copy(error = "Kata sandi tidak boleh kosong.") }
            return
        }
        // --- AKHIR VALIDASI ---
        
        viewModelScope.launch {
            // 1. Tampilkan loading
            _state.update { it.copy(isLoading = true, error = null) }

            // 2. Simulasi panggilan jaringan
            delay(2000)

            // 3. Logika validasi (nanti akan diganti dengan panggilan ke UseCase/Repository)
            val currentState = _state.value
            if (currentState.emailOrUsername.isNotBlank() && currentState.password.isNotBlank()) {
                // Simulasi login sukses
                _state.update {
                    it.copy(isLoading = false, loginSuccess = true)
                }
            } else {
                // Simulasi login gagal
                _state.update {
                    it.copy(isLoading = false, error = "Email dan kata sandi tidak boleh kosong.")
                }
            }
        }
    }
}
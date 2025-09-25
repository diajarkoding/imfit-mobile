package com.diajarkoding.imfit.presentation.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diajarkoding.imfit.core.data.SessionManager
import com.diajarkoding.imfit.core.utils.Validator
import com.diajarkoding.imfit.data.remote.dto.LoginRequest
import com.diajarkoding.imfit.domain.model.Result
import com.diajarkoding.imfit.domain.usecase.LoginUserUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val loginUserUseCase: LoginUserUseCase,
    private val sessionManager: SessionManager,
) : ViewModel() {

    private val _state = MutableStateFlow(LoginState())
    val state = _state.asStateFlow()

    fun onEvent(event: LoginEvent) {
        when (event) {
            is LoginEvent.EmailOrUsernameChanged -> {
                _state.update {
                    it.copy(
                        emailOrUsername = event.value,
                        emailOrUsernameError = null
                    )
                }
            }

            is LoginEvent.PasswordChanged -> {
                _state.update { it.copy(password = event.value, passwordError = null) }
            }

            is LoginEvent.RememberMeChanged -> _state.update { it.copy(rememberMe = event.value) }
            LoginEvent.LoginButtonPressed -> validateAndLogin()
            LoginEvent.SnackbarDismissed -> _state.update { it.copy(snackbarMessage = null) }
        }
    }

    private fun validateAndLogin() {
        val currentState = _state.value

        // --- TAHAP 1: Validasi Lokal ---
        val isEmailEmpty = !Validator.isNotEmpty(currentState.emailOrUsername)
        val isPasswordEmpty = !Validator.isNotEmpty(currentState.password)

        _state.update {
            it.copy(
                emailOrUsernameError = if (isEmailEmpty) "Email atau nama pengguna tidak boleh kosong." else null,
                passwordError = if (isPasswordEmpty) "Kata sandi tidak boleh kosong." else null
            )
        }

        if (isEmailEmpty || isPasswordEmpty) {
            return // Hentikan jika validasi lokal gagal
        }

        // --- TAHAP 2: Panggil API jika validasi lokal lolos ---
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }

            val loginRequest = LoginRequest(
                login = currentState.emailOrUsername,
                password = currentState.password
            )

            when (val result = loginUserUseCase(loginRequest)) {
                is Result.Success -> {
                    sessionManager.saveAuthToken(result.data)
                    _state.update { it.copy(isLoading = false, loginSuccess = true) }
                }

                is Result.Error -> {
                    // Tampilkan error dari server di Snackbar
                    _state.update { it.copy(isLoading = false, snackbarMessage = result.message) }
                }
            }
        }
    }
}
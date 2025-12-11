package com.diajarkoding.imfit.presentation.ui.auth

import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.data.exception.AuthException
import com.diajarkoding.imfit.domain.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LoginState(
    val email: String = "",
    val password: String = "",
    @StringRes val emailError: Int? = null,
    @StringRes val passwordError: Int? = null,
    val isLoading: Boolean = false,
    val loginSuccess: Boolean = false,
    @StringRes val errorMessage: Int? = null
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _state = MutableStateFlow(LoginState())
    val state = _state.asStateFlow()

    fun onEmailChange(email: String) {
        _state.update { it.copy(email = email, emailError = null) }
    }

    fun onPasswordChange(password: String) {
        _state.update { it.copy(password = password, passwordError = null) }
    }

    fun login() {
        val currentState = _state.value

        var hasError = false
        var emailError: Int? = null
        var passwordError: Int? = null

        if (currentState.email.isBlank()) {
            emailError = R.string.error_email_required
            hasError = true
        } else if (!android.util.Patterns.EMAIL_ADDRESS.matcher(currentState.email).matches()) {
            emailError = R.string.error_email_invalid
            hasError = true
        }

        if (currentState.password.isBlank()) {
            passwordError = R.string.error_password_required
            hasError = true
        } else if (currentState.password.length < 6) {
            passwordError = R.string.error_password_length
            hasError = true
        }

        if (hasError) {
            _state.update { it.copy(emailError = emailError, passwordError = passwordError) }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }

            val result = authRepository.login(currentState.email, currentState.password)

            result.fold(
                onSuccess = { user ->
                    Log.d("LoginViewModel", "Login successful for user: ${user.email}")
                    _state.update { it.copy(isLoading = false, loginSuccess = true) }
                },
                onFailure = { exception ->
                    Log.e("LoginViewModel", "Login failed", exception)

                    val errorMessageRes = when (exception) {
                        is AuthException.InvalidCredentials -> R.string.error_invalid_email_password
                        is AuthException.UserNotFound -> R.string.error_invalid_email_password
                        is AuthException.AccountNotVerified -> R.string.error_login_failed
                        is AuthException.AccountDisabled -> R.string.error_login_failed
                        is AuthException.TooManyAttempts -> R.string.error_login_failed
                        is AuthException.NetworkError -> R.string.error_login_failed
                        is AuthException.RateLimited -> R.string.error_login_failed
                        is AuthException.SessionExpired -> R.string.error_login_failed
                        else -> R.string.error_login_failed
                    }

                    _state.update {
                        it.copy(isLoading = false, errorMessage = errorMessageRes)
                    }
                }
            )
        }
    }

    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }
}

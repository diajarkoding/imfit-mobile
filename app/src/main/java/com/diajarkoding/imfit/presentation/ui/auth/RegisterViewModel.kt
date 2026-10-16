package com.diajarkoding.imfit.presentation.ui.auth

import android.net.Uri
import android.util.Log
import androidx.annotation.StringRes
import com.diajarkoding.imfit.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diajarkoding.imfit.data.exception.AuthException
import com.diajarkoding.imfit.domain.repository.AuthRepository
import com.diajarkoding.imfit.domain.model.RegisterResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RegisterState(
    val name: String = "",
    val email: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    val birthDate: String = "",
    val profilePhotoUri: Uri? = null,
    @StringRes val nameError: Int? = null,
    @StringRes val emailError: Int? = null,
    @StringRes val passwordError: Int? = null,
    @StringRes val confirmPasswordError: Int? = null,
    @StringRes val birthDateError: Int? = null,
    val isLoading: Boolean = false,
    val registerResult: RegisterResult? = null,
    @StringRes val errorMessage: Int? = null
)

@HiltViewModel
class RegisterViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _state = MutableStateFlow(RegisterState())
    val state = _state.asStateFlow()

    fun onNameChange(name: String) {
        _state.update { it.copy(name = name, nameError = null) }
    }

    fun onEmailChange(email: String) {
        _state.update { it.copy(email = email, emailError = null) }
    }

    fun onPasswordChange(password: String) {
        _state.update { it.copy(password = password, passwordError = null) }
    }

    fun onConfirmPasswordChange(confirmPassword: String) {
        _state.update { it.copy(confirmPassword = confirmPassword, confirmPasswordError = null) }
    }

    fun onBirthDateChange(birthDate: String) {
        _state.update { it.copy(birthDate = birthDate, birthDateError = null) }
    }

    fun onProfilePhotoSelected(uri: Uri?) {
        _state.update { it.copy(profilePhotoUri = uri) }
    }

    fun register() {
        val currentState = _state.value
        if (currentState.isLoading || currentState.registerResult != null) return

        var hasError = false
        var nameError: Int? = null
        var emailError: Int? = null
        var passwordError: Int? = null
        var confirmPasswordError: Int? = null
        var birthDateError: Int? = null

        if (currentState.name.isBlank()) {
            nameError = R.string.error_name_required
            hasError = true
        }

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
        } else if (!isStrongPassword(currentState.password)) {
            passwordError = R.string.auth_password_strength
            hasError = true
        }

        if (currentState.confirmPassword.isBlank()) {
            confirmPasswordError = R.string.error_confirm_password_required
            hasError = true
        } else if (currentState.password != currentState.confirmPassword) {
            confirmPasswordError = R.string.error_password_mismatch
            hasError = true
        }

        if (currentState.birthDate.isBlank()) {
            birthDateError = R.string.error_birthdate_required
            hasError = true
        }

        if (hasError) {
            _state.update {
                it.copy(
                    nameError = nameError,
                    emailError = emailError,
                    passwordError = passwordError,
                    confirmPasswordError = confirmPasswordError,
                    birthDateError = birthDateError
                )
            }
            return
        }

        _state.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {

            val result = authRepository.register(
                name = currentState.name,
                email = currentState.email,
                password = currentState.password,
                birthDate = currentState.birthDate,
                profilePhotoUri = currentState.profilePhotoUri?.toString()
            )

            result.fold(
                onSuccess = { registerResult ->
                    _state.update { it.copy(isLoading = false, registerResult = registerResult) }
                },
                onFailure = { exception ->
                    Log.e("RegisterViewModel", "Registration failed", exception)

                    val errorMessage = when (exception) {
                        is AuthException.EmailAlreadyExists -> R.string.error_email_already_registered
                        is AuthException.WeakPassword -> R.string.auth_password_strength
                        is AuthException.InvalidEmail -> R.string.error_email_invalid
                        is AuthException.NetworkError -> R.string.auth_network_error
                        is AuthException.RateLimited -> R.string.auth_rate_limited
                        is AuthException.AccountNotVerified -> R.string.auth_verify_email
                        else -> R.string.auth_registration_failed
                    }

                    _state.update {
                        it.copy(isLoading = false, errorMessage = errorMessage)
                    }
                }
            )
        }
    }

    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }

    fun consumeRegisterResult() {
        _state.update { it.copy(registerResult = null) }
    }

    private fun isStrongPassword(password: String): Boolean =
        password.length >= 8 && password.any(Char::isUpperCase) &&
            password.any(Char::isLowerCase) && password.any(Char::isDigit)
}

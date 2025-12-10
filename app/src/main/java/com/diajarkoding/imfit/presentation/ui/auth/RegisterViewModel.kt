package com.diajarkoding.imfit.presentation.ui.auth

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diajarkoding.imfit.domain.repository.AuthRepository
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
    val nameError: String? = null,
    val emailError: String? = null,
    val passwordError: String? = null,
    val confirmPasswordError: String? = null,
    val birthDateError: String? = null,
    val isLoading: Boolean = false,
    val registerSuccess: Boolean = false,
    val errorMessage: String? = null
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

        var hasError = false
        var nameError: String? = null
        var emailError: String? = null
        var passwordError: String? = null
        var confirmPasswordError: String? = null
        var birthDateError: String? = null

        if (currentState.name.isBlank()) {
            nameError = "Name is required"
            hasError = true
        }

        if (currentState.email.isBlank()) {
            emailError = "Email is required"
            hasError = true
        } else if (!android.util.Patterns.EMAIL_ADDRESS.matcher(currentState.email).matches()) {
            emailError = "Invalid email format"
            hasError = true
        }

        if (currentState.password.isBlank()) {
            passwordError = "Password is required"
            hasError = true
        } else if (currentState.password.length < 6) {
            passwordError = "Password must be at least 6 characters"
            hasError = true
        }

        if (currentState.confirmPassword.isBlank()) {
            confirmPasswordError = "Please confirm your password"
            hasError = true
        } else if (currentState.password != currentState.confirmPassword) {
            confirmPasswordError = "Passwords do not match"
            hasError = true
        }

        if (currentState.birthDate.isBlank()) {
            birthDateError = "Birth date is required"
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

        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }

            val result = authRepository.register(
                name = currentState.name,
                email = currentState.email,
                password = currentState.password,
                birthDate = currentState.birthDate,
                profilePhotoUri = currentState.profilePhotoUri?.toString()
            )

            result.fold(
                onSuccess = {
                    _state.update { it.copy(isLoading = false, registerSuccess = true) }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(isLoading = false, errorMessage = error.message ?: "Registration failed")
                    }
                }
            )
        }
    }

    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }
}

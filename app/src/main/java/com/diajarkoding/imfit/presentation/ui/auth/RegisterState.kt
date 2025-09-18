package com.diajarkoding.imfit.presentation.ui.auth

import android.net.Uri

data class RegisterState(
    val fullname: String = "",
    val username: String = "",
    val email: String = "",
    val password: String = "",
    val dateOfBirth: String = "",
    val profileImageUri: Uri? = null,
    val isLoading: Boolean = false,
    val registerSuccess: Boolean = false,
    val fullnameError: String? = null,
    val usernameError: String? = null,
    val emailError: String? = null,
    val passwordError: String? = null,
    val dateOfBirthError: String? = null
)
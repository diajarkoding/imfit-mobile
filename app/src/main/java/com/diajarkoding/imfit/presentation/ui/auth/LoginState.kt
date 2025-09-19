package com.diajarkoding.imfit.presentation.ui.auth

data class LoginState(
    val emailOrUsername: String = "",
    val password: String = "",
    val rememberMe: Boolean = false,
    val isLoading: Boolean = false,
    val loginSuccess: Boolean = false,
    val emailOrUsernameError: String? = null,
    val passwordError: String? = null,
    val snackbarMessage: String? = null
)
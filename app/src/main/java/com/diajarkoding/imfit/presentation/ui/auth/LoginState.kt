package com.diajarkoding.imfit.presentation.ui.auth

data class LoginState(
    val emailOrUsername: String = "",
    val password: String = "",
    val rememberMe: Boolean = false,
    val isLoading: Boolean = false,
    val loginSuccess: Boolean = false,
    val error: String? = null
)
package com.diajarkoding.imfit.presentation.ui.auth

sealed class LoginEvent {
    data class EmailOrUsernameChanged(val value: String) : LoginEvent()
    data class PasswordChanged(val value: String) : LoginEvent()
    data class RememberMeChanged(val value: Boolean) : LoginEvent()
    object LoginButtonPressed : LoginEvent()
}
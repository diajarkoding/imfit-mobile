package com.diajarkoding.imfit.presentation.ui.auth

import android.net.Uri

sealed class RegisterEvent {
    data class FullnameChanged(val value: String) : RegisterEvent()
    data class UsernameChanged(val value: String) : RegisterEvent()
    data class EmailChanged(val value: String) : RegisterEvent()
    data class PasswordChanged(val value: String) : RegisterEvent()
    data class DateOfBirthChanged(val value: String) : RegisterEvent()
    data class ProfilePictureChanged(val uri: Uri?) : RegisterEvent()
    object RegisterButtonPressed : RegisterEvent()
    object SnackbarDismissed : RegisterEvent()
}
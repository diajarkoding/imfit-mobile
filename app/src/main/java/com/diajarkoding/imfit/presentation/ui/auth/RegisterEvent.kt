package com.diajarkoding.imfit.presentation.ui.auth

import android.net.Uri

sealed class RegisterEvent {
    object ChooseProfilePicture : RegisterEvent()
    data class ProfilePictureChanged(val uri: Uri?) : RegisterEvent()
    data class FullnameChanged(val value: String) : RegisterEvent()
    data class UsernameChanged(val value: String) : RegisterEvent()
    data class EmailChanged(val value: String) : RegisterEvent()
    data class PasswordChanged(val value: String) : RegisterEvent()
    data class DateOfBirthChanged(val value: String) : RegisterEvent()
    object RegisterButtonPressed : RegisterEvent()
}
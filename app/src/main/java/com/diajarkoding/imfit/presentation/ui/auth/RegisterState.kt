package com.diajarkoding.imfit.presentation.ui.auth

import android.net.Uri

data class RegisterState(
    val profileImageUri: Uri? = null,
    val fullname: String = "",
    val username: String = "",
    val email: String = "",
    val password: String = "",
    val dateOfBirth: String = "",
    val isLoading: Boolean = false,
    val registerSuccess: Boolean = false,
    val error: String? = null
)
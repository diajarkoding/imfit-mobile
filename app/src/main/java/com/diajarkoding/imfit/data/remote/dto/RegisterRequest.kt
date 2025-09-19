package com.diajarkoding.imfit.data.remote.dto

import com.squareup.moshi.Json

data class RegisterRequest(
    @field:Json(name = "fullname") val fullname: String,
    @field:Json(name = "username") val username: String,
    @field:Json(name = "email") val email: String,
    @field:Json(name = "password") val password: String,
    @field:Json(name = "password_confirmation") val passwordConfirmation: String,
    @field:Json(name = "date_of_birth") val dateOfBirth: String
)
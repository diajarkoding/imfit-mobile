package com.diajarkoding.imfit.data.remote.dto

import com.squareup.moshi.Json

data class LoginRequest(
    @field:Json(name = "login") val login: String,
    @field:Json(name = "password") val password: String,
)
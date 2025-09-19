package com.diajarkoding.imfit.data.remote.dto

import com.squareup.moshi.Json

data class LoginResponse(
    @field:Json(name = "token") val token: String
)
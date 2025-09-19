package com.diajarkoding.imfit.data.remote.dto

import com.squareup.moshi.Json

data class BaseResponse<T>(
    @field:Json(name = "status") val status: String,
    @field:Json(name = "message") val message: String?,
    @field:Json(name = "data") val data: T?
)
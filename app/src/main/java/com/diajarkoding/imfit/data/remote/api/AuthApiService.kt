package com.diajarkoding.imfit.data.remote.api

import com.diajarkoding.imfit.data.remote.dto.BaseResponse
import com.diajarkoding.imfit.data.remote.dto.LoginRequest
import com.diajarkoding.imfit.data.remote.dto.LoginResponse
import com.diajarkoding.imfit.data.remote.dto.RegisterRequest
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST

interface AuthApiService {

    @Headers("Accept: application/json")
    @POST("register")
    suspend fun register(
        @Body registerRequest: RegisterRequest
    ): BaseResponse<Any?> // 'data' bisa null saat sukses, jadi Unit

    @Headers("Accept: application/json")
    @POST("login")
    suspend fun login(
        @Body loginRequest: LoginRequest
    ): BaseResponse<LoginResponse>

    // 'Authorization' header akan ditambahkan melalui Interceptor nanti
    @POST("logout")
    suspend fun logout(): BaseResponse<Unit>
}
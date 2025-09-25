package com.diajarkoding.imfit.data.remote.api

import com.diajarkoding.imfit.data.remote.dto.BaseResponse
import com.diajarkoding.imfit.data.remote.dto.LoginRequest
import com.diajarkoding.imfit.data.remote.dto.LoginResponse
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

interface AuthApiService {

    /**
     * PENJELASAN PERUBAHAN:
     * 1. @Multipart: Menandakan bahwa request ini akan mengirim file dan data dalam format multipart/form-data.
     * 2. @Part: Setiap field data (teks atau file) sekarang dikirim sebagai bagian ("Part") terpisah.
     * 3. RequestBody: Tipe data untuk field teks dalam request multipart.
     * 4. MultipartBody.Part: Tipe data khusus untuk field file.
     * 5. Nama @Part("profile_picture_url") harus SAMA PERSIS dengan yang diminta oleh server Anda.
     */
    @Multipart
    @POST("register")
    suspend fun register(
        @Part("fullname") fullname: RequestBody,
        @Part("username") username: RequestBody,
        @Part("email") email: RequestBody,
        @Part("password") password: RequestBody,
        @Part("password_confirmation") passwordConfirmation: RequestBody,
        @Part("date_of_birth") dateOfBirth: RequestBody,
        @Part profile_picture_url: MultipartBody.Part? // Ini adalah bagian untuk file gambar
    ): BaseResponse<Any?>

    @Headers("Accept: application/json")
    @POST("login")
    suspend fun login(
        @Body loginRequest: LoginRequest
    ): BaseResponse<LoginResponse>

    @POST("logout")
    suspend fun logout(): BaseResponse<Any?>
}
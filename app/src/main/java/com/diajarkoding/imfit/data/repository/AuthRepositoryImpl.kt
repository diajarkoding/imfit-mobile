package com.diajarkoding.imfit.data.repository

import android.app.Application
import android.net.Uri
import com.diajarkoding.imfit.core.error.ErrorHandler
import com.diajarkoding.imfit.data.remote.api.AuthApiService
import com.diajarkoding.imfit.data.remote.dto.LoginRequest
import com.diajarkoding.imfit.data.remote.dto.RegisterRequest
import com.diajarkoding.imfit.domain.model.Result
import com.diajarkoding.imfit.domain.repository.AuthRepository
import com.squareup.moshi.Moshi
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject


class AuthRepositoryImpl @Inject constructor(
    private val api: AuthApiService,
    private val moshi: Moshi,
    // PENJELASAN #1: Inject Application Context.
    // Ini dibutuhkan untuk mengakses ContentResolver yang akan membaca file dari Uri.
    private val application: Application
) : AuthRepository {


    override suspend fun register(
        registerRequest: RegisterRequest,
        profileImageUri: Uri?
    ): Result<Unit> {
        return try {
            // PENJELASAN #2: Ubah semua data String dari DTO menjadi RequestBody.
            val fullnamePart =
                registerRequest.fullname.toRequestBody("text/plain".toMediaTypeOrNull())
            val usernamePart =
                registerRequest.username.toRequestBody("text/plain".toMediaTypeOrNull())
            val emailPart = registerRequest.email.toRequestBody("text/plain".toMediaTypeOrNull())
            val passwordPart =
                registerRequest.password.toRequestBody("text/plain".toMediaTypeOrNull())
            val passwordConfirmationPart =
                registerRequest.passwordConfirmation.toRequestBody("text/plain".toMediaTypeOrNull())
            val dateOfBirthPart =
                registerRequest.dateOfBirth.toRequestBody("text/plain".toMediaTypeOrNull())

            // PENJELASAN #3: Ubah Uri gambar menjadi file yang bisa dikirim (MultipartBody.Part).
            var imagePart: MultipartBody.Part? = null
            profileImageUri?.let { uri ->
                // Gunakan ContentResolver untuk membuka stream dari Uri
                application.contentResolver.openInputStream(uri)?.use { inputStream ->
                    // Baca file menjadi byte array
                    val fileBytes = inputStream.readBytes()
                    // Buat RequestBody dari byte array gambar
                    val fileRequestBody = fileBytes.toRequestBody(
                        application.contentResolver.getType(uri)?.toMediaTypeOrNull()
                    )
                    // Buat MultipartBody.Part dari RequestBody gambar
                    imagePart = MultipartBody.Part.createFormData(
                        "profile_picture_url", // Nama field ini harus SAMA DENGAN di ApiService
                        "profile_image.jpg",   // Nama file ini bisa bebas
                        fileRequestBody
                    )
                }
            }

            // Panggil API dengan semua "Part" yang sudah disiapkan
            val response = api.register(
                fullname = fullnamePart,
                username = usernamePart,
                email = emailPart,
                password = passwordPart,
                passwordConfirmation = passwordConfirmationPart,
                dateOfBirth = dateOfBirthPart,
                profile_picture_url = imagePart
            )

            if (response.status == "success") {
                Result.Success(Unit)
            } else {
                Result.Error(response.message ?: "Registrasi gagal, silakan coba lagi.")
            }
        } catch (e: Exception) {
            ErrorHandler.handleException(e, moshi)
        }
    }

    override suspend fun login(loginRequest: LoginRequest): Result<String> {
        return try {
            val response = api.login(loginRequest)
            if (response.status == "success" && response.data != null) {
                Result.Success(response.data.token)
            } else {
                Result.Error(response.message ?: "Kredensial tidak valid.")
            }
        } catch (e: Exception) {
            ErrorHandler.handleException(e, moshi)
        }
    }

    override suspend fun logout(): Result<Unit> {
        return try {
            val response = api.logout()
            if (response.status == "success") {
                Result.Success(Unit)
            } else {
                Result.Error(response.message ?: "Logout gagal.")
            }
        } catch (e: Exception) {
            ErrorHandler.handleException(e, moshi)
        }
    }
}
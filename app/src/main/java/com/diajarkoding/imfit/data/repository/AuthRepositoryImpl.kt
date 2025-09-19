package com.diajarkoding.imfit.data.repository

import com.diajarkoding.imfit.data.remote.api.AuthApiService
import com.diajarkoding.imfit.data.remote.dto.LoginRequest
import com.diajarkoding.imfit.data.remote.dto.RegisterRequest
import com.diajarkoding.imfit.domain.model.Result
import com.diajarkoding.imfit.domain.repository.AuthRepository
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject

class AuthRepositoryImpl @Inject constructor(
    private val api: AuthApiService
) : AuthRepository {

    override suspend fun register(registerRequest: RegisterRequest): Result<Unit> {
        return try {
            val response = api.register(registerRequest)
            if (response.status == "success") {
                Result.Success(Unit)
            } else {
                Result.Error(response.message ?: "Registrasi gagal, silakan coba lagi.")
            }
        } catch (e: HttpException) {
            // Error dari server (misal: 422 Unprocessable Entity)
            Result.Error(e.message ?: "Terjadi kesalahan pada server.")
        } catch (e: IOException) {
            // Error koneksi jaringan
            Result.Error("Tidak dapat terhubung ke server. Periksa koneksi internet Anda.")
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
        } catch (e: HttpException) {
            Result.Error(e.message ?: "Terjadi kesalahan pada server.")
        } catch (e: IOException) {
            Result.Error("Tidak dapat terhubung ke server. Periksa koneksi internet Anda.")
        }
    }
}
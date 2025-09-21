// file: core/error/ErrorHandler.kt
package com.diajarkoding.imfit.core.error

import com.diajarkoding.imfit.data.remote.dto.ErrorResponse
import com.diajarkoding.imfit.domain.model.Result
import com.squareup.moshi.Moshi
import retrofit2.HttpException
import java.io.IOException

object ErrorHandler {

    fun handleException(e: Throwable, moshi: Moshi): Result.Error {
        return when (e) {
            is IOException -> {
                Result.Error("Tidak dapat terhubung ke server. Periksa koneksi internet Anda.")
            }
            is HttpException -> {
                handleHttpException(e, moshi)
            }
            else -> {
                Result.Error("Terjadi kesalahan yang tidak diketahui: ${e.message}")
            }
        }
    }

    // --- FUNGSI YANG DIPERBAIKI ---
    private fun handleHttpException(e: HttpException, moshi: Moshi): Result.Error {
        val statusCode = e.code()
        val errorBody = e.response()?.errorBody()?.string()

        // PRIORITAS #1: Selalu coba parse pesan error spesifik dari server
        try {
            if (!errorBody.isNullOrBlank()) {
                val errorAdapter = moshi.adapter(ErrorResponse::class.java)
                val errorResponse = errorAdapter.fromJson(errorBody)

                // Jika ada pesan spesifik dari server, langsung gunakan itu
                if (!errorResponse?.message.isNullOrBlank()) {
                    return Result.Error(errorResponse!!.message)
                }
            }
        } catch (jsonException: Exception) {
            // Jika parsing gagal, tidak apa-apa. Lanjut ke fallback di bawah.
        }

        // PRIORITAS #2: Jika parsing gagal atau tidak ada pesan, gunakan pesan umum berdasarkan status kode
        return when (statusCode) {
//            401, 403 -> Result.Error("Kredensial tidak valid atau sesi Anda telah berakhir.")
            429 -> Result.Error("Terlalu banyak permintaan. Coba lagi nanti.")
            in 500..599 -> Result.Error("Terjadi masalah pada server. Silakan coba beberapa saat lagi.")
            // Fallback terakhir jika tidak ada yang cocok
            else -> Result.Error("Terjadi kesalahan pada server (Kode: $statusCode)")
        }
    }
}
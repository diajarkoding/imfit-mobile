package com.diajarkoding.imfit.data.remote

import com.diajarkoding.imfit.core.data.SessionManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

class AuthInterceptor @Inject constructor(
    private val sessionManager: SessionManager
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        // Ambil token dari DataStore
        val token = runBlocking {
            sessionManager.getAuthToken().first()
        }

        val requestBuilder = originalRequest.newBuilder()
        // Jika token ada, tambahkan ke header
        token?.let {
            requestBuilder.addHeader("Authorization", "Bearer $it")
        }

        return chain.proceed(requestBuilder.build())
    }
}
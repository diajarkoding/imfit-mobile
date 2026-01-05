package com.diajarkoding.imfit.presentation.ui.splash

import androidx.lifecycle.ViewModel
import com.diajarkoding.imfit.domain.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class SplashViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    fun isLoggedIn(): Boolean {
        return try {
            // Since this is called from a LaunchedEffect, we can use runBlocking
            // or better yet, make the repository call non-suspending if possible
            kotlinx.coroutines.runBlocking {
                authRepository.isLoggedIn()
            }
        } catch (e: Exception) {
            false
        }
    }
}

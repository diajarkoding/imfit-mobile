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
        return authRepository.isLoggedIn()
    }
}

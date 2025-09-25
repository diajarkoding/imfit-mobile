// file: MainViewModel.kt
package com.diajarkoding.imfit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diajarkoding.imfit.core.data.SessionManager
import com.diajarkoding.imfit.presentation.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// --- STATE BARU UNTUK MAIN VIEW MODEL ---
data class MainState(
    val startDestination: String? = null,
    val isGlobalLoading: Boolean = false
)

@HiltViewModel
class MainViewModel @Inject constructor(
    sessionManager: SessionManager
) : ViewModel() {

    private val _state = MutableStateFlow(MainState())
    val state = _state.asStateFlow()

    init {
        // Logika penentuan rute awal (startDestination)
        viewModelScope.launch {
            val token = sessionManager.getAuthToken().first()
            val destination = if (!token.isNullOrBlank()) {
                Routes.MAIN_GRAPH
            } else {
                Routes.SPLASH
            }
            _state.update { it.copy(startDestination = destination) }
        }
    }

    // Fungsi untuk mengontrol loading global
    fun showGlobalLoading() {
        _state.update { it.copy(isGlobalLoading = true) }
    }

    fun hideGlobalLoading() {
        _state.update { it.copy(isGlobalLoading = false) }
    }
}
package com.diajarkoding.imfit.theme

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ThemeUiState(
    val isDarkMode: Boolean = false,
    val isInitialized: Boolean = false
)

@HiltViewModel
class ThemeViewModel @Inject constructor(
    private val themeManager: ThemeManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(ThemeUiState())
    val uiState: StateFlow<ThemeUiState> = _uiState.asStateFlow()

    private var pendingDarkMode: Boolean? = null

    init {
        viewModelScope.launch {
            themeManager.isDarkMode.collect { persistedDarkMode ->
                val pendingMode = pendingDarkMode
                if (pendingMode == null || pendingMode == persistedDarkMode) {
                    pendingDarkMode = null
                    _uiState.value = ThemeUiState(
                        isDarkMode = persistedDarkMode,
                        isInitialized = true
                    )
                } else {
                    _uiState.update { it.copy(isInitialized = true) }
                }
            }
        }
    }

    fun toggleTheme() {
        setDarkMode(!_uiState.value.isDarkMode)
    }

    fun setDarkMode(isDarkMode: Boolean) {
        pendingDarkMode = isDarkMode
        _uiState.update { it.copy(isDarkMode = isDarkMode) }

        viewModelScope.launch {
            themeManager.setDarkMode(isDarkMode)
        }
    }
}

package com.diajarkoding.imfit.presentation.ui.home

import androidx.annotation.StringRes

sealed interface SectionLoadState<out T> {
    data object Loading : SectionLoadState<Nothing>

    data class Success<T>(
        val data: T,
        val isRefreshing: Boolean = false,
        /**
         * true jika berasal dari snapshot remote terbaru (RemotePrimary);
         * false jika berasal dari fallback lokal saat remote gagal (LocalFallback).
         */
        val isFromRemote: Boolean = true
    ) : SectionLoadState<T>

    data class Error<T>(
        @StringRes val errorRes: Int,
        val message: String? = null,
        val previousData: T? = null,
        val hasPreviousData: Boolean = false
    ) : SectionLoadState<T>
}

/**
 * Mempertahankan data atau state error sebelumnya saat proses refresh dimulai,
 * sehingga tidak memicu shimmer penuh ulang dan tidak kehilangan previousData bila refresh gagal.
 */
fun <T> SectionLoadState<T>.toRefreshingState(): SectionLoadState<T> = when (this) {
    is SectionLoadState.Loading -> SectionLoadState.Loading
    is SectionLoadState.Success -> copy(isRefreshing = true)
    is SectionLoadState.Error -> this
}

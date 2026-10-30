package com.diajarkoding.imfit.domain.model

sealed interface HomeRemoteReadResult<out T> {
    val data: T
    val isFromRemote: Boolean

    /** Berhasil diambil dari Supabase dan telah digabung dengan Local Pending Overlay. */
    data class RemotePrimary<T>(
        override val data: T
    ) : HomeRemoteReadResult<T> {
        override val isFromRemote: Boolean = true
    }

    /**
     * Request ke Supabase gagal (mis. offline setelah menyelesaikan workout), tetapi Room memiliki
     * data milik userId yang valid / PENDING_SYNC yang dapat ditampilkan sebagai fallback.
     * TIDAK diklaim sebagai snapshot remote terbaru.
     */
    data class LocalFallback<T>(
        override val data: T,
        val cause: Throwable
    ) : HomeRemoteReadResult<T> {
        override val isFromRemote: Boolean = false
    }
}

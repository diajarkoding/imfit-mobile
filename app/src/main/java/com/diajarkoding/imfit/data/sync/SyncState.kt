package com.diajarkoding.imfit.data.sync

/**
 * Represents the current synchronization state.
 * Exposed via StateFlow for UI observation.
 */
data class SyncState(
    val status: SyncStatus = SyncStatus.IDLE,
    val pendingCount: Int = 0,
    val lastSyncTime: Long? = null,
    val errorMessage: String? = null,
    // Progress tracking for UI
    val progress: Float = 0f, // 0.0 to 1.0
    val progressMessage: String? = null, // e.g. "Downloading exercises..."
    val isInitialSync: Boolean = false // True during first-time sync after login
) {
    enum class SyncStatus {
        IDLE,
        SYNCING,
        SYNCED,
        FAILED,
        OFFLINE
    }
}


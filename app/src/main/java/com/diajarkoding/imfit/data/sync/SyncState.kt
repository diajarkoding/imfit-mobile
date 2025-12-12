package com.diajarkoding.imfit.data.sync

/**
 * Represents the current synchronization state.
 * Exposed via StateFlow for UI observation.
 */
data class SyncState(
    val status: SyncStatus = SyncStatus.IDLE,
    val pendingCount: Int = 0,
    val lastSyncTime: Long? = null,
    val errorMessage: String? = null
) {
    enum class SyncStatus {
        IDLE,
        SYNCING,
        SYNCED,
        FAILED,
        OFFLINE
    }
}

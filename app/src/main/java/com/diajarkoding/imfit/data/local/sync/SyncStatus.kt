package com.diajarkoding.imfit.data.local.sync

/**
 * Represents the synchronization status of local data with the remote server.
 */
enum class SyncStatus {
    /**
     * Data is synchronized with the remote server.
     */
    SYNCED,

    /**
     * Data has local changes pending synchronization.
     */
    PENDING_SYNC,

    /**
     * Synchronization failed and needs retry.
     */
    SYNC_FAILED
}

/**
 * Represents the type of pending operation for sync.
 */
enum class PendingOperation {
    CREATE,
    UPDATE,
    DELETE
}

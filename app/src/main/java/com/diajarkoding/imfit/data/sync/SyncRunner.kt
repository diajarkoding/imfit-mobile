package com.diajarkoding.imfit.data.sync

interface SyncRunner {
    suspend fun run(expectedUserId: String?): SyncRunResult
    /** Server-owned catalog must not wait for unrelated pending workout uploads. */
    suspend fun refreshMasterData(): SyncRunResult = run(null)
    /** Explicit user intent only; foreground/reconnect/Worker must never invoke this. */
    suspend fun retryPendingUploads(expectedUserId: String): SyncRunResult = SyncRunResult.RETRYABLE_FAILURE
}

enum class SyncRunResult {
    SUCCESS,
    NO_AUTHENTICATED_USER,
    RETRYABLE_FAILURE
}

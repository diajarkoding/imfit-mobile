package com.diajarkoding.imfit.data.sync

interface SyncRunner {
    suspend fun run(expectedUserId: String?): SyncRunResult
    /** Server-owned catalog must not wait for unrelated pending workout uploads. */
    suspend fun refreshMasterData(): SyncRunResult = run(null)
}

enum class SyncRunResult {
    SUCCESS,
    NO_AUTHENTICATED_USER,
    RETRYABLE_FAILURE
}

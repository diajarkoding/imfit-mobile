package com.diajarkoding.imfit.data.sync

interface SyncRunner {
    suspend fun run(expectedUserId: String?): SyncRunResult
}

enum class SyncRunResult {
    SUCCESS,
    NO_AUTHENTICATED_USER,
    RETRYABLE_FAILURE
}

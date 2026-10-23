package com.diajarkoding.imfit.data.sync

internal enum class RefreshDataset { CATALOG, PROFILE, TEMPLATES, HISTORY }
internal enum class RefreshOrigin { AUTOMATIC, MANUAL }
internal enum class RefreshIssue { SNAPSHOT_UNVERIFIED, PENDING_BLOCKED, CONFLICT_SKIPPED }

/** Read outcomes do not acknowledge outbox writes or imply that all pending data was uploaded. */
internal sealed interface RefreshOutcome {
    data object Success : RefreshOutcome
    data class PartialSuccess(val issues: Set<RefreshIssue>) : RefreshOutcome {
        init { require(issues.isNotEmpty()) }
    }
    data object Offline : RefreshOutcome
    data object NetworkFailure : RefreshOutcome
    data class ServerFailure(val statusCode: Int) : RefreshOutcome {
        init { require(statusCode in 400..599 && statusCode != 401 && statusCode != 403) }
    }
    data object AuthenticationRequired : RefreshOutcome
    data object AuthChanged : RefreshOutcome
    data object Cancelled : RefreshOutcome
    data class Blocked(val reason: RefreshIssue) : RefreshOutcome
}

/** Eligibility only. Scheduling/backoff remains the durable worker's responsibility. */
internal val RefreshOutcome.isRetryable: Boolean
    get() = when (this) {
        RefreshOutcome.Offline, RefreshOutcome.NetworkFailure -> true
        is RefreshOutcome.ServerFailure -> statusCode == 429 || statusCode >= 500
        else -> false
    }

package io.github.jan.supabase.auth

import io.github.jan.supabase.auth.user.UserSession

/** Explicit proof-only capability. An ordinary SessionManager is not recovery evidence. */
interface AtomicSessionStore : SessionManager {
    suspend fun readVerified(): StoreOutcome
    suspend fun readCommitted(): StoreOutcome = readVerified()
    suspend fun prepare(expected: StoreHead?, operationId: String, session: UserSession?): StoreOutcome
    suspend fun verifyPrepared(ticket: StoreTicket): StoreOutcome
    suspend fun commitIfCurrent(ticket: StoreTicket): StoreOutcome
    suspend fun resolve(ticket: StoreTicket): StoreOutcome

    /** Called under the SDK authority; rechecks the exact durable head before publication. */
    suspend fun publishIfCurrent(ticket: StoreTicket, publication: () -> Unit): Boolean
}

/** Contains only integrity metadata, never credentials. Full-record digest includes phase. */
@ConsistentCopyVisibility
data class StoreHead internal constructor(
    val incarnation: String,
    val generation: Long,
    val operationId: String,
    val digest: String,
)

@ConsistentCopyVisibility
data class StoreTicket internal constructor(
    val head: StoreHead,
    val predecessor: String,
    val binding: String,
)

enum class StoreFailure { UNSUPPORTED, IO, INVALID, FUTURE_VERSION, OVERSIZE, OVERFLOW }

sealed interface StoreOutcome {
    data object Absent : StoreOutcome
    data object LegacyUnverified : StoreOutcome
    data class Unavailable(val reason: StoreFailure) : StoreOutcome
    data object Conflict : StoreOutcome
    data class Prepared(val ticket: StoreTicket) : StoreOutcome

    /** No generated toString/equals exposing a session's credentials. */
    class VerifiedCommitted internal constructor(val ticket: StoreTicket, val session: UserSession?) : StoreOutcome {
        override fun toString() = "VerifiedCommitted(${ticket.head.operationId})"
    }
}

class AuthStorageUnavailableException : IllegalStateException("Session storage is unverified or unavailable")

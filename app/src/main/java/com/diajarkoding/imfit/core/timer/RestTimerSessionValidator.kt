package com.diajarkoding.imfit.core.timer

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout

/** False is authoritative absence/mismatch; errors/timeouts propagate and remain retryable. */
class RestTimerSessionValidator(
    private val localUserId: suspend () -> String?,
    private val peekUserId: suspend () -> String?,
    private val authStatus: suspend () -> String = { "UNKNOWN" },
    private val onStatus: (String) -> Unit = {},
    private val sessionExists: suspend (String, String) -> Boolean,
    private val timeoutMs: Long = 2500,
    private val measure: suspend (String, suspend () -> Boolean) -> Boolean = { _, block -> block() }
) {
    private suspend fun snapshotStatus(): String =
        try { authStatus() } catch (e: CancellationException) { throw e }
        catch (e: Exception) { "READ_FAILED" }

    suspend fun valid(state: RestTimerState): Boolean = withTimeout(timeoutMs) {
        // Fast path first: a loaded identity needs no init wait. A miss falls back to the
        // init-waiting read; phases are logged separately so a hang can be attributed.
        val fast = measure("auth_fast_path") { peekUserId() == state.userId }
        if (!fast) {
            onStatus(snapshotStatus())
            try {
                if (!measure("auth_init_wait") { localUserId() == state.userId }) return@withTimeout false
            } catch (e: TimeoutCancellationException) {
                onStatus(snapshotStatus())
                throw e
            }
        }
        if (!measure("session_identity") { sessionExists(state.userId, state.workoutId) }) return@withTimeout false
        // The account can change while Room is suspended. Never trust only the persisted owner.
        measure("auth_recheck") { localUserId() == state.userId }
    }
}

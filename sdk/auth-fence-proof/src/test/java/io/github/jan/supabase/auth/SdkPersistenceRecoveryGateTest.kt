package io.github.jan.supabase.auth

import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Desired recovery gate, not an expected-unsafe characterization. */
class SdkPersistenceRecoveryGateTest {
    @Test fun ambiguousPersistFailureMustNotBecomeTrustedRestoreAfterAuthorityRecreation() = runBlocking<Unit> {
        withTimeout(15_000) {
            val store = object : SessionManager {
                var value: UserSession? = null
                override suspend fun saveSession(session: UserSession) {
                    value = session
                    if (session.user?.id == "B") error("Ambiguous write: committed before I/O error")
                }
                override suspend fun loadSession() = value
                override suspend fun deleteSession() { value = null }
            }
            fun client() = createSupabaseClient("https://fence-proof.invalid", "fixture-key") {
                defaultLogLevel = LogLevel.NONE
                httpEngine = MockEngine { error("Recovery fixture must make no network request") }
                install(Auth) { minimalSettings(sessionManager = store) }
            }
            val first = client()
            try {
                first.auth.awaitInitialization()
                first.auth.config.autoSaveToStorage = true
                first.auth.importSession(UserSession("synthetic-A", "refresh-A", expiresIn = 86_400,
                    tokenType = "bearer", user = UserInfo(aud = "authenticated", id = "A")), autoRefresh = false)
                try {
                    first.auth.importSession(UserSession("synthetic-B", "refresh-B", expiresIn = 86_400,
                        tokenType = "bearer", user = UserInfo(aud = "authenticated", id = "B")), autoRefresh = false)
                    fail("Expected I/O failure")
                } catch (_: IllegalStateException) { }
                assertNull(first.auth.currentUserOrNull())
            } finally { first.close() }
            // Recreate authority/client, retaining only durable SessionManager data (not OS death).
            val restored = client()
            try {
                restored.auth.awaitInitialization()
                restored.auth.loadFromStorage(autoRefresh = false)
                assertNull("Ambiguous persistence requires durable recovery proof, not a fresh in-memory epoch",
                    restored.auth.currentUserOrNull())
            } finally { restored.close() }
        }
    }
}

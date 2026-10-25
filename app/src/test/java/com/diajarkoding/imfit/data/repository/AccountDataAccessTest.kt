package com.diajarkoding.imfit.data.repository

import app.cash.turbine.test
import com.diajarkoding.imfit.domain.repository.AuthRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AccountDataAccessTest {
    private val identity = MutableStateFlow<String?>("A")
    private val auth = mockk<AuthRepository> {
        coEvery { getLocalUserId() } answers { identity.value }
        every { observeLocalUserId() } returns identity
    }
    private val access = AccountDataAccess(auth)

    @Test fun callerCannotReadForeignAccountOrReadAfterLogout() = runTest {
        var queried = false
        assertNull(access.read("B", null as String?) { queried = true; "private-B" })
        identity.value = null
        assertNull(access.read("A", null as String?) { queried = true; "private-A" })
        assertFalse(queried)
    }
    @Test fun identityChangeDuringSuspendedQueryDiscardsOldAccountResult() = runTest {
        assertNull(access.read("A", null as String?) { identity.value = "B"; "private-A" })
    }
    @Test fun retainedDataIsAvailableAgainOnlyToReturningOwner() = runTest {
        identity.value = "B"
        assertEquals(emptyList<String>(), access.read("A", emptyList()) { listOf("pending-A") })
        identity.value = "A"
        assertEquals(listOf("pending-A"), access.read("A", emptyList()) { listOf("pending-A") })
    }
    @Test fun existingObserverClearsOnLogoutAndSwitchAndReattachesOnOwnerLogin() = runTest {
        val rows = MutableStateFlow(listOf("pending-A"))
        access.observe("A", emptyList<String>()) { rows }.test {
            assertEquals(listOf("pending-A"), awaitItem())
            identity.value = null
            assertEquals(emptyList<String>(), awaitItem())
            identity.value = "B"
            assertEquals(emptyList<String>(), awaitItem())
            rows.value = listOf("pending-A-updated")
            expectNoEvents()
            identity.value = "A"
            assertEquals(listOf("pending-A-updated"), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}

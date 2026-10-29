package com.diajarkoding.imfit.data.repository

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoRepositoriesTest {

    @Test
    fun authStartsLoggedInAndSupportsLogoutAndLogin() = runBlocking {
        val repository = DemoAuthRepository()

        assertTrue(repository.isLoggedIn())
        assertEquals(DemoAuthRepository.DEMO_EMAIL, repository.getCurrentUser()?.email)

        repository.logout()
        assertFalse(repository.isLoggedIn())

        val result = repository.login(
            DemoAuthRepository.DEMO_EMAIL,
            DemoAuthRepository.DEMO_PASSWORD
        )
        assertTrue(result.isSuccess)
    }
}

package com.diajarkoding.imfit.core.network

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class RetryUtilsTest {

    @Test
    fun `retryWithExponentialBackoff returns on first success`() = runTest {
        var attempts = 0
        val result = RetryUtils.retryWithExponentialBackoff(
            times = 3,
            initialDelayMs = 10
        ) {
            attempts++
            "success"
        }
        
        assertEquals("success", result)
        assertEquals(1, attempts)
    }

    @Test
    fun `retryWithExponentialBackoff with zero retries executes once`() = runTest {
        var attempts = 0
        val result = RetryUtils.retryWithExponentialBackoff(
            times = 1,
            initialDelayMs = 10
        ) {
            attempts++
            "success"
        }
        
        assertEquals("success", result)
        assertEquals(1, attempts)
    }
}

package com.diajarkoding.imfit.core.result

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class WorkoutResultTest {

    @Test
    fun `Success getOrNull returns data`() {
        val result: WorkoutResult<String> = WorkoutResult.Success("test data")
        assertEquals("test data", result.getOrNull())
    }

    @Test
    fun `Error getOrNull returns null`() {
        val result: WorkoutResult<String> = WorkoutResult.Error(IOException("error"))
        assertNull(result.getOrNull())
    }

    @Test
    fun `Loading getOrNull returns null`() {
        val result: WorkoutResult<String> = WorkoutResult.Loading
        assertNull(result.getOrNull())
    }

    @Test
    fun `Success getOrThrow returns data`() {
        val result: WorkoutResult<String> = WorkoutResult.Success("test data")
        assertEquals("test data", result.getOrThrow())
    }

    @Test(expected = IOException::class)
    fun `Error getOrThrow throws exception`() {
        val result: WorkoutResult<String> = WorkoutResult.Error(IOException("error"))
        result.getOrThrow()
    }

    @Test(expected = IllegalStateException::class)
    fun `Loading getOrThrow throws exception`() {
        val result: WorkoutResult<String> = WorkoutResult.Loading
        result.getOrThrow()
    }

    @Test
    fun `Success getOrDefault returns data`() {
        val result: WorkoutResult<String> = WorkoutResult.Success("test data")
        assertEquals("test data", result.getOrDefault("default"))
    }

    @Test
    fun `Error getOrDefault returns default`() {
        val result: WorkoutResult<String> = WorkoutResult.Error(IOException("error"))
        assertEquals("default", result.getOrDefault("default"))
    }

    @Test
    fun `Loading getOrDefault returns default`() {
        val result: WorkoutResult<String> = WorkoutResult.Loading
        assertEquals("default", result.getOrDefault("default"))
    }

    @Test
    fun `map transforms Success data`() {
        val result: WorkoutResult<Int> = WorkoutResult.Success(5)
        val mapped = result.map { it * 2 }
        assertEquals(10, (mapped as WorkoutResult.Success).data)
    }

    @Test
    fun `map preserves Error`() {
        val exception = IOException("error")
        val result: WorkoutResult<Int> = WorkoutResult.Error(exception)
        val mapped = result.map { it * 2 }
        assertTrue(mapped is WorkoutResult.Error)
        assertEquals(exception, (mapped as WorkoutResult.Error).exception)
    }

    @Test
    fun `map preserves Loading`() {
        val result: WorkoutResult<Int> = WorkoutResult.Loading
        val mapped = result.map { it * 2 }
        assertTrue(mapped is WorkoutResult.Loading)
    }

    @Test
    fun `onSuccess executes action for Success`() {
        var executed = false
        val result: WorkoutResult<String> = WorkoutResult.Success("test")
        result.onSuccess { executed = true }
        assertTrue(executed)
    }

    @Test
    fun `onSuccess does not execute for Error`() {
        var executed = false
        val result: WorkoutResult<String> = WorkoutResult.Error(IOException("error"))
        result.onSuccess { executed = true }
        assertFalse(executed)
    }

    @Test
    fun `onError executes action for Error`() {
        var executed = false
        val result: WorkoutResult<String> = WorkoutResult.Error(IOException("error"))
        result.onError { _, _ -> executed = true }
        assertTrue(executed)
    }

    @Test
    fun `onError does not execute for Success`() {
        var executed = false
        val result: WorkoutResult<String> = WorkoutResult.Success("test")
        result.onError { _, _ -> executed = true }
        assertFalse(executed)
    }

    @Test
    fun `Success with fromCache flag`() {
        val result = WorkoutResult.Success("test", fromCache = true)
        assertTrue(result.fromCache)
    }

    @Test
    fun `Error with custom message`() {
        val result = WorkoutResult.Error(
            IOException("technical error"),
            message = "User friendly message"
        )
        assertEquals("User friendly message", result.message)
    }

    @Test
    fun `getUserMessage returns custom message for Error`() {
        val result = WorkoutResult.Error(
            IOException("technical"),
            message = "Please check your connection"
        )
        assertEquals("Please check your connection", result.getUserMessage())
    }

    @Test
    fun `getUserMessage returns exception message when no custom message`() {
        val result = WorkoutResult.Error(IOException("Network error"))
        assertEquals("Network error", result.getUserMessage())
    }

    @Test
    fun `isSuccess property returns true for Success`() {
        val result: WorkoutResult<String> = WorkoutResult.Success("test")
        assertTrue(result.isSuccess)
    }

    @Test
    fun `isSuccess property returns false for Error`() {
        val result: WorkoutResult<String> = WorkoutResult.Error(IOException("error"))
        assertFalse(result.isSuccess)
    }

    @Test
    fun `isError property returns true for Error`() {
        val result: WorkoutResult<String> = WorkoutResult.Error(IOException("error"))
        assertTrue(result.isError)
    }

    @Test
    fun `isLoading property returns true for Loading`() {
        val result: WorkoutResult<String> = WorkoutResult.Loading
        assertTrue(result.isLoading)
    }
}

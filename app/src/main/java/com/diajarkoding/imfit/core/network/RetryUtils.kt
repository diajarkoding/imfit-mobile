 package com.diajarkoding.imfit.core.network
 
 import kotlinx.coroutines.delay
 import java.io.IOException
 
 /**
  * Retry utility for transient network failures.
  * Implements exponential backoff strategy.
  */
 object RetryUtils {
     
     /**
      * Retries a suspend block with exponential backoff on IOException.
      * 
      * @param times Maximum number of retry attempts
      * @param initialDelayMs Initial delay before first retry in milliseconds
      * @param maxDelayMs Maximum delay between retries in milliseconds
      * @param factor Multiplier for delay after each retry
      * @param shouldRetry Lambda to determine if exception should trigger retry
      * @param block The suspend block to execute
      * @return The result of the block
      * @throws Exception if all retries fail
      * 
      * Example usage:
      * ```
      * val result = retryWithExponentialBackoff(
      *     times = 3,
      *     initialDelayMs = 1000
      * ) {
      *     api.fetchData()
      * }
      * ```
      */
     suspend fun <T> retryWithExponentialBackoff(
         times: Int = 3,
         initialDelayMs: Long = 1000,
         maxDelayMs: Long = 10000,
         factor: Double = 2.0,
         shouldRetry: (Exception) -> Boolean = { it is IOException },
         block: suspend () -> T
     ): T {
         var currentDelay = initialDelayMs
         repeat(times - 1) { attempt ->
             try {
                 return block()
             } catch (e: Exception) {
                 if (!shouldRetry(e)) {
                     throw e
                 }
                 android.util.Log.w(
                     "RetryUtils", 
                     "Attempt ${attempt + 1} failed, retrying in ${currentDelay}ms: ${e.message}"
                 )
                 delay(currentDelay)
                 currentDelay = (currentDelay * factor).toLong().coerceAtMost(maxDelayMs)
             }
         }
         // Last attempt - let exceptions propagate
         return block()
     }
     
     /**
      * Retries a suspend block a fixed number of times with fixed delay.
      * Simpler alternative to exponential backoff.
      * 
      * @param times Maximum number of retry attempts
      * @param delayMs Delay between retries in milliseconds
      * @param shouldRetry Lambda to determine if exception should trigger retry
      * @param block The suspend block to execute
      * @return The result of the block
      */
     suspend fun <T> retryWithFixedDelay(
         times: Int = 3,
         delayMs: Long = 1000,
         shouldRetry: (Exception) -> Boolean = { it is IOException },
         block: suspend () -> T
     ): T {
         repeat(times - 1) { attempt ->
             try {
                 return block()
             } catch (e: Exception) {
                 if (!shouldRetry(e)) {
                     throw e
                 }
                 android.util.Log.w(
                     "RetryUtils",
                     "Attempt ${attempt + 1} failed, retrying in ${delayMs}ms: ${e.message}"
                 )
                 delay(delayMs)
             }
         }
         return block()
     }
 }

package com.diajarkoding.imfit.core.result

/**
 * Sealed class representing the result of an operation.
 * Provides consistent error handling across the application.
 * 
 * Usage:
 * ```
 * when (val result = repository.getData()) {
 *     is WorkoutResult.Success -> handleData(result.data)
 *     is WorkoutResult.Error -> showError(result.exception)
 *     is WorkoutResult.Loading -> showLoading()
 * }
 * ```
 */
sealed class WorkoutResult<out T> {
    
    /**
     * Represents a successful operation with data.
     * @param data The result data
     * @param fromCache Whether data was loaded from local cache (optional metadata)
     */
    data class Success<T>(
        val data: T,
        val fromCache: Boolean = false
    ) : WorkoutResult<T>()
    
    /**
     * Represents a failed operation.
     * @param exception The underlying exception
     * @param message Optional user-friendly error message
     */
    data class Error(
        val exception: Throwable,
        val message: String? = null
    ) : WorkoutResult<Nothing>() {
        
        /**
         * Returns a user-friendly error message.
         */
        fun getUserMessage(): String {
            return message ?: when (exception) {
                is java.net.UnknownHostException -> "No internet connection"
                is java.net.SocketTimeoutException -> "Request timed out"
                is kotlinx.serialization.SerializationException -> "Failed to parse response"
                else -> exception.message ?: "Unknown error occurred"
            }
        }
    }
    
    /**
     * Represents a loading state.
     */
    data object Loading : WorkoutResult<Nothing>()
    
    /**
     * Returns true if this is a success.
     */
    val isSuccess: Boolean get() = this is Success
    
    /**
     * Returns true if this is an error.
     */
    val isError: Boolean get() = this is Error
    
    /**
     * Returns true if this is loading.
     */
    val isLoading: Boolean get() = this is Loading
    
    /**
     * Returns the data if successful, or null otherwise.
     */
    fun getOrNull(): T? = (this as? Success)?.data
    
    /**
     * Returns the data if successful, or throws the exception.
     */
    fun getOrThrow(): T = when (this) {
        is Success -> data
        is Error -> throw exception
        is Loading -> throw IllegalStateException("Result is still loading")
    }
    
    /**
     * Returns the data if successful, or the default value otherwise.
     */
    fun getOrDefault(default: @UnsafeVariance T): T = when (this) {
        is Success -> data
        else -> default
    }
    
    /**
     * Maps the success data to a new type.
     */
    inline fun <R> map(transform: (T) -> R): WorkoutResult<R> = when (this) {
        is Success -> Success(transform(data), fromCache)
        is Error -> this
        is Loading -> Loading
    }
    
    /**
     * Executes the given block if this is a success.
     */
    inline fun onSuccess(action: (T) -> Unit): WorkoutResult<T> {
        if (this is Success) action(data)
        return this
    }
    
    /**
     * Executes the given block if this is an error.
     */
    inline fun onError(action: (Throwable, String?) -> Unit): WorkoutResult<T> {
        if (this is Error) action(exception, message)
        return this
    }
    
    /**
     * Executes the given block if this is loading.
     */
    inline fun onLoading(action: () -> Unit): WorkoutResult<T> {
        if (this is Loading) action()
        return this
    }
    
    companion object {
        /**
         * Creates a Success result.
         */
        fun <T> success(data: T, fromCache: Boolean = false): WorkoutResult<T> = 
            Success(data, fromCache)
        
        /**
         * Creates an Error result.
         */
        fun error(exception: Throwable, message: String? = null): WorkoutResult<Nothing> = 
            Error(exception, message)
        
        /**
         * Creates a Loading result.
         */
        fun loading(): WorkoutResult<Nothing> = Loading
        
        /**
         * Wraps a suspend block in a WorkoutResult, catching any exceptions.
         */
        suspend inline fun <T> runCatching(block: () -> T): WorkoutResult<T> {
            return try {
                Success(block())
            } catch (e: Exception) {
                Error(e)
            }
        }
    }
}

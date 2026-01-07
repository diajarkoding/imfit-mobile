package com.diajarkoding.imfit.core.network

/**
 * Exception thrown when a network operation is attempted without internet connectivity.
 */
class NoNetworkException(
    message: String = "No internet connection. Please check your network."
) : Exception(message)

/**
 * Exception thrown when a network operation fails.
 */
class NetworkOperationException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

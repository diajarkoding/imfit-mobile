package com.diajarkoding.imfit.data.exception

/**
 * Sealed class for authentication-related exceptions
 */
sealed class AuthException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /**
     * Network connectivity issues
     */
    class NetworkError(cause: Throwable? = null) : AuthException(
        "Network connection error. Please check your internet connection and try again.",
        cause
    )

    /**
     * Invalid email or password combination
     */
    class InvalidCredentials : AuthException("Invalid email or password")

    /**
     * User account not found
     */
    class UserNotFound : AuthException("User account not found")

    /**
     * Email already registered
     */
    class EmailAlreadyExists : AuthException("Email is already registered. Please use a different email or try logging in.")

    /**
     * Weak password (doesn't meet requirements)
     */
    class WeakPassword : AuthException("Password is too weak. Please use a stronger password.")

    /**
     * Invalid email format
     */
    class InvalidEmail : AuthException("Invalid email format")

    /**
     * Session has expired
     */
    class SessionExpired : AuthException("Your session has expired. Please log in again.")

    /**
     * Account is not verified
     */
    class AccountNotVerified : AuthException("Please verify your email address before continuing.")

    /**
     * Account is disabled/blocked
     */
    class AccountDisabled : AuthException("Your account has been disabled. Please contact support.")

    /**
     * Too many login attempts
     */
    class TooManyAttempts : AuthException("Too many failed login attempts. Please try again later.")

    /**
     * Rate limited by Supabase
     */
    class RateLimited : AuthException("Too many requests. Please wait a moment and try again.")

    /**
     * Supabase configuration error
     */
    class ConfigurationError(message: String = "Authentication service configuration error") : AuthException(message)

    /**
     * Unknown error during authentication
     */
    class UnknownError(message: String = "An unexpected error occurred", cause: Throwable? = null) : AuthException(message, cause)
}

/**
 * Helper function to convert Supabase exceptions to our custom AuthException types
 */
fun mapSupabaseException(throwable: Throwable): AuthException {
    val message = throwable.message?.lowercase() ?: ""

    return when {
        message.contains("invalid login credentials") -> AuthException.InvalidCredentials()
        message.contains("user not found") -> AuthException.UserNotFound()
        message.contains("user already registered") || message.contains("duplicate key") -> AuthException.EmailAlreadyExists()
        message.contains("weak password") -> AuthException.WeakPassword()
        message.contains("invalid email") -> AuthException.InvalidEmail()
        message.contains("session") && message.contains("expired") -> AuthException.SessionExpired()
        message.contains("email not confirmed") -> AuthException.AccountNotVerified()
        message.contains("too many requests") || message.contains("rate limit") -> AuthException.RateLimited()
        message.contains("network") || message.contains("connection") -> AuthException.NetworkError(throwable)
        message.contains("timeout") -> AuthException.NetworkError(throwable)
        else -> AuthException.UnknownError(throwable.message ?: "Authentication failed", throwable)
    }
}
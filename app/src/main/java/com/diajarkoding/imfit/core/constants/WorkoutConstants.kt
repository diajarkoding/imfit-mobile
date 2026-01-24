package com.diajarkoding.imfit.core.constants

/**
 * Centralized constants for workout-related operations.
 * Eliminates magic numbers scattered across the codebase.
 */
object WorkoutConstants {
    
    // ==================== TIME CONSTANTS ====================
    
    /** Maximum duration for WakeLock in milliseconds (4 hours) */
    const val MAX_WAKELOCK_DURATION_MS = 4 * 60 * 60 * 1000L
    
    /** Default rest time between sets in seconds */
    const val DEFAULT_REST_SECONDS = 60
    
    /** Rest timer update interval in milliseconds */
    const val TIMER_UPDATE_INTERVAL_MS = 1000L
    
    /** Signed URL expiration time in seconds (1 hour) */
    const val SIGNED_URL_EXPIRATION_SECONDS = 3600
    
    // ==================== DATABASE CONSTANTS ====================
    
    /** 
     * Offset for muscle category ID mapping.
     * Database uses 1-based indexing, enum uses 0-based.
     */
    const val MUSCLE_CATEGORY_ID_OFFSET = 1
    
    // ==================== VALIDATION CONSTANTS ====================
    
    /** Minimum password length for registration */
    const val MIN_PASSWORD_LENGTH = 6
    
    /** Maximum template name length */
    const val MAX_TEMPLATE_NAME_LENGTH = 100
    
    /** Maximum sets per exercise */
    const val MAX_SETS_PER_EXERCISE = 20
    
    /** Maximum weight in kg */
    const val MAX_WEIGHT_KG = 1000f
    
    /** Maximum reps per set */
    const val MAX_REPS_PER_SET = 999
    
    // ==================== WORKOUT SESSION CONSTANTS ====================
    
    /** Estimated minutes per exercise for duration calculation */
    const val ESTIMATED_MINUTES_PER_EXERCISE = 8
    
    /** Local user ID when no authenticated user */
    const val LOCAL_USER_ID = "local_user"
    
    // ==================== NOTIFICATION CONSTANTS ====================
    
    /** Time format for elapsed workout time */
    const val TIME_FORMAT_PATTERN = "%02d:%02d:%02d"
    
    // ==================== NETWORK RETRY CONSTANTS ====================
    
    /** Maximum number of retry attempts for network operations */
    const val MAX_RETRY_ATTEMPTS = 3
    
    /** Initial delay for exponential backoff in milliseconds */
    const val INITIAL_RETRY_DELAY_MS = 1000L
    
    /** Maximum delay for exponential backoff in milliseconds */
    const val MAX_RETRY_DELAY_MS = 10000L
}

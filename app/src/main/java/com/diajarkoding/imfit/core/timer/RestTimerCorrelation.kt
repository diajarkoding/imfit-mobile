package com.diajarkoding.imfit.core.timer

/** Opaque diagnostics key, not an auth identity or an authority for timer decisions. */
object RestTimerCorrelation {
    fun instanceId(timerId: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(timerId.toByteArray(Charsets.UTF_8)).take(6).joinToString("") { "%02x".format(it) }
}

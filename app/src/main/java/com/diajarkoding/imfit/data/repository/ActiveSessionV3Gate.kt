package com.diajarkoding.imfit.data.repository

/**
 * Release gate (PLAN §17.9): v3 active sessions may only be created after Room-first persistence (T26–T34),
 * atomic finish (T35), aggregate upload (T36) and the rest-outcome backend contract (T37) are all connected.
 * Production keeps this off until the Closed Beta cutover (T48); tests turn it on explicitly.
 */
internal object ActiveSessionV3Gate {
    @Volatile var enabled: Boolean = false
}

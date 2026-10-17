package com.diajarkoding.imfit.core.timer

import org.junit.Assert.*
import org.junit.Test

class RestTimerRecoveryPolicyTest {
    @Test fun thirdAndSubsequentTransientFailuresStillRetryWithinBudget() {
        for (attempt in 1..5) {
            assertTrue(RestTimerRecoveryPolicy.canRetry(attempt, 1_000, 1_000 + attempt * 30_000L))
        }
    }

    @Test fun workAgeExhaustsBudgetEvenIfOsDelayedTheFirstAttempt() {
        assertFalse(RestTimerRecoveryPolicy.canRetry(1, 1_000, 1_000 + RestTimerRecoveryPolicy.MAX_WORK_AGE_MS))
    }

    @Test fun legacyWorkAndElapsedClockResetHaveFiniteAttemptSafetyBound() {
        for (enqueued in listOf(-1L, 100_000L)) {
            assertTrue(RestTimerRecoveryPolicy.canRetry(3, enqueued, 1_000))
            assertFalse(RestTimerRecoveryPolicy.canRetry(12, enqueued, 1_000))
        }
    }
}

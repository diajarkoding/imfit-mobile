package com.diajarkoding.imfit.presentation.ui.workout

import org.junit.Assert.*
import org.junit.Test

class WorkoutNotificationPermissionTest {
    @Test fun firstStartOnAndroid13OrHigherRequestsConsent() {
        for (api in listOf(33, 34, 35, 37)) assertTrue(shouldRequestWorkoutNotifications(api, false, false, false))
    }
    @Test fun olderAndroidDoesNotRequestRuntimeNotificationPermission() {
        for (api in listOf(24, 29, 30, 31, 32)) assertFalse(shouldRequestWorkoutNotifications(api, false, false, false))
    }
    @Test fun grantedOrPreviouslyAskedNeverPromptsAutomaticallyAgain() {
        assertFalse(shouldRequestWorkoutNotifications(34, true, false, false))
        assertFalse(shouldRequestWorkoutNotifications(34, false, true, false))
    }
    @Test fun resumingNeverInterruptsAnExistingSession() {
        assertFalse(shouldRequestWorkoutNotifications(34, false, false, true))
    }
}

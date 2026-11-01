package com.diajarkoding.imfit.presentation.ui.workout

import org.junit.Assert.*
import org.junit.Test

class DurationInputTest {
    @Test fun acceptsSecondsAndMinutesSecondsWithoutChangingTheirUnits() {
        assertEquals(90, parseSetDurationSeconds("01:30"))
        assertEquals(90, parseSetDurationSeconds("90"))
        assertEquals(1800, parseSetDurationSeconds("30:00"))
        assertEquals(59, parseSetDurationSeconds("00:59"))
    }

    @Test fun incompleteOrInvalidDraftNeverBecomesACompletedMeasurement() {
        for (raw in listOf("1:", ":30", "1:3", "01:60", "1:00:00", "-1", "1.5", "one", "2147483647:59")) {
            assertNull(raw, parseSetDurationSeconds(raw))
        }
        assertEquals(0, parseSetDurationSeconds(""))
        assertEquals(0, parseSetDurationSeconds("00:00"))
        assertEquals(Int.MAX_VALUE, parseSetDurationSeconds(Int.MAX_VALUE.toString()))
    }
}

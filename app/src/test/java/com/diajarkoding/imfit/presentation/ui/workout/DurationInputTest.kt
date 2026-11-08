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

    @Test fun routineDurationRequiresPositiveHhMmSsWithinTwentyFourHours() {
        assertEquals(1, parseRoutineDurationSeconds("00:00:01"))
        assertEquals(60, parseRoutineDurationSeconds("00:01:00"))
        assertEquals(90, parseRoutineDurationSeconds("00:01:30"))
        assertEquals(1800, parseRoutineDurationSeconds("00:30:00"))
        assertEquals(3600, parseRoutineDurationSeconds("01:00:00"))
        assertEquals(86400, parseRoutineDurationSeconds("24:00:00"))
        assertEquals("00:01:00", formatDurationHhMmSs(60))
        assertEquals("00:01:30", formatDurationHhMmSs(90))
        assertEquals("00:30:00", formatDurationHhMmSs(1800))
        assertEquals("24:00:00", formatDurationHhMmSs(86400))

        for (invalid in listOf(
            "", "00:00:00", "90", "01:30", "00:60:00", "00:00:60",
            "24:00:01", "25:00:00", "00:1:00", "00:01:0", "-00:01:00", "bad"
        )) {
            assertNull(invalid, parseRoutineDurationSeconds(invalid))
        }
    }
}

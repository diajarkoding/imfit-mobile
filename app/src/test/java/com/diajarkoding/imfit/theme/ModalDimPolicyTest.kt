package com.diajarkoding.imfit.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModalDimPolicyTest {
    @Test fun allModalSurfacesShareTheApprovedFiftyPercentScrim() {
        assertEquals(0.50f, IMFITOpacity.scrim, 0f)
        assertTrue(IMFITOpacity.scrim in 0f..1f)
    }
}

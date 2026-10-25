package com.diajarkoding.imfit.presentation.ui.exercise

import org.junit.Assert.*
import org.junit.Test

class CategoryGridLayoutTest {
    @Test fun twelveCategoriesFitTheRemainingPhoneViewport() {
        val result = categoryGridLayout(12, 3, 550, 12, 12, 8, 20, 105, 48)
        assertTrue(result.fits)
        assertTrue(result.rowHeightPx * 4 + 8 * 3 + 24 <= 550)
        assertTrue(result.artworkPx in 48..105)
        assertTrue(result.artworkPx + 20 + 8 <= result.rowHeightPx)
    }
    @Test fun narrowScreenCanUseSixRowsWhenThereIsEnoughRoom() {
        val result = categoryGridLayout(12, 2, 550, 12, 12, 8, 20, 112, 48)
        assertTrue(result.fits)
        assertTrue(result.rowHeightPx * 6 + 8 * 5 + 24 <= 550)
    }
    @Test fun largeTextOrShortScreenScrollsInsteadOfClippingNames() {
        val result = categoryGridLayout(12, 2, 350, 12, 12, 8, 48, 112, 48)
        assertFalse(result.fits)
        assertEquals(48, result.artworkPx)
        assertEquals(48 + 8 + 48, result.rowHeightPx)
    }
    @Test fun tallScreensKeepArtworkAspectAndMaximumSize() {
        assertEquals(112, categoryGridLayout(12, 3, 1400, 12, 12, 8, 20, 112, 48).artworkPx)
    }
}

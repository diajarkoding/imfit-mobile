package com.diajarkoding.imfit.data.repository

import org.junit.Assert.*
import org.junit.Test

class ExerciseCatalogPagingConfigTest {
    @Test fun initialAndFollowingLocalPagesUseTenItems() {
        val config = exerciseCatalogPagingConfig()
        assertEquals(10, config.pageSize)
        assertEquals(10, config.initialLoadSize)
        assertEquals(2, config.prefetchDistance)
        assertFalse(config.enablePlaceholders)
    }
}

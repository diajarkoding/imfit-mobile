package com.diajarkoding.imfit.data.repository

import androidx.paging.PagingConfig

/** Same10-item local query batches for production and demo; no remote append. */
internal const val EXERCISE_CATALOG_PAGE_SIZE = 10

internal fun exerciseCatalogPagingConfig() = PagingConfig(
    pageSize = EXERCISE_CATALOG_PAGE_SIZE,
    initialLoadSize = EXERCISE_CATALOG_PAGE_SIZE,
    prefetchDistance = 2,
    enablePlaceholders = false,
)

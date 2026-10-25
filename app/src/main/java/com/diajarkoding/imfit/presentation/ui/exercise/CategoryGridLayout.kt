package com.diajarkoding.imfit.presentation.ui.exercise

internal data class CategoryGridLayout(val fits: Boolean, val rowHeightPx: Int, val artworkPx: Int)

/** All dimensions are measured in the actual Scaffold viewport; never include app bars twice. */
internal fun categoryGridLayout(
    count: Int, columns: Int, heightPx: Int, topPx: Int, bottomPx: Int, gapPx: Int,
    labelHeightPx: Int, preferredArtworkPx: Int, minimumArtworkPx: Int
): CategoryGridLayout {
    require(count > 0 && columns > 0)
    val rows = (count + columns - 1) / columns
    val availableRow = ((heightPx - topPx - bottomPx - gapPx * (rows - 1)) / rows).coerceAtLeast(0)
    val minimumArtwork = minimumArtworkPx.coerceAtMost(preferredArtworkPx).coerceAtLeast(1)
    val minimumRow = minimumArtwork + gapPx + labelHeightPx
    val fits = availableRow >= minimumRow
    val row = if (fits) availableRow else minimumRow
    return CategoryGridLayout(fits, row, (row - gapPx - labelHeightPx).coerceIn(minimumArtwork, preferredArtworkPx.coerceAtLeast(minimumArtwork)))
}

package com.diajarkoding.imfit.presentation.ui.exercise

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.presentation.components.common.*
import com.diajarkoding.imfit.theme.*

@Composable
fun ExerciseBrowserScreen(
    onCategorySelected: (ExerciseCategory) -> Unit
) {
    val gridState = rememberLazyGridState()
    val textMeasurer = rememberTextMeasurer()
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val density = LocalDensity.current
            val columns = if (maxWidth >= IMFITComponentSizes.adaptiveGridWidth && density.fontScale <= IMFITComponentSizes.adaptiveFontScale) 3 else 2
            val labels = ExerciseCategory.entries.map { stringResource(it.labelRes) }
            val cellWidth = (maxWidth - IMFITSpacing.screenHorizontal * 2 - IMFITSpacing.lg * (columns - 1)) / columns
            val labelHeightPx = labels.maxOf {
                textMeasurer.measure(it, style = IMFITTypography.categoryLabel,
                    constraints = Constraints(maxWidth = with(density) { cellWidth.roundToPx().coerceAtLeast(1) })).size.height
            }
            val layout = with(density) {
                categoryGridLayout(ExerciseCategory.entries.size, columns, maxHeight.roundToPx(),
                    IMFITSpacing.headerContentGap.roundToPx(), IMFITSpacing.md.roundToPx(), IMFITSpacing.sm.roundToPx(),
                    labelHeightPx, minOf(IMFITComponentSizes.categoryArtwork, cellWidth).roundToPx(), 48.dp.roundToPx())
            }
            val rowHeight = with(density) { layout.rowHeightPx.toDp() }
            val artwork = with(density) { layout.artworkPx.toDp() }
            val labelHeight = with(density) { labelHeightPx.toDp() }
            LaunchedEffect(layout.fits, columns) { if (layout.fits) gridState.scrollToItem(0) }
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = gridState,
                modifier = Modifier.fillMaxSize().testTag("exercise_category_grid"),
                userScrollEnabled = !layout.fits,
                contentPadding = PaddingValues(start = IMFITSpacing.screenHorizontal, top = IMFITSpacing.headerContentGap, end = IMFITSpacing.screenHorizontal, bottom = IMFITSpacing.md),
                horizontalArrangement = Arrangement.spacedBy(IMFITSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(IMFITSpacing.sm)
            ) {
                items(ExerciseCategory.entries, key = { it.name }) { category ->
                    Column(
                        modifier = Modifier.fillMaxWidth()
                             .height(rowHeight).testTag("category_${category.name}")
                            .clip(RoundedCornerShape(IMFITCornerRadius.template))
                             .clickable(role = Role.Button) { onCategorySelected(category) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(IMFITSpacing.sm, Alignment.Top)
                    ) {
                        MuscleCategoryArtwork(
                            asset = category.artwork,
                            modifier = Modifier.size(artwork)
                        )
                        Text(
                            text = stringResource(category.labelRes),
                            modifier = Modifier.fillMaxWidth().heightIn(min = labelHeight),
                            style = IMFITTypography.categoryLabel,
                            color = MaterialTheme.colorScheme.onBackground,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

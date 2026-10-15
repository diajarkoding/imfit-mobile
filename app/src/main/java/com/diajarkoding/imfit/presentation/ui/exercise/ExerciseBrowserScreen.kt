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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.presentation.components.common.*
import com.diajarkoding.imfit.theme.*

@Composable
fun ExerciseBrowserScreen(
    onCategorySelected: (ExerciseCategory) -> Unit
) {
    val gridState = rememberLazyGridState()
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val columns = if (maxWidth >= IMFITComponentSizes.adaptiveGridWidth && LocalDensity.current.fontScale <= IMFITComponentSizes.adaptiveFontScale) 3 else 2
            val rows = (ExerciseCategory.entries.size + columns - 1) / columns
            val rowHeight = maxOf(IMFITComponentSizes.categoryRowMinHeight, (maxHeight - IMFITSpacing.xxxl - IMFITSpacing.lg * (rows - 1)) / rows)
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = IMFITSpacing.screenHorizontal, top = IMFITSpacing.md, end = IMFITSpacing.screenHorizontal, bottom = IMFITSpacing.xl),
                horizontalArrangement = Arrangement.spacedBy(IMFITSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(IMFITSpacing.lg)
            ) {
                items(ExerciseCategory.entries, key = { it.name }) { category ->
                    Column(
                        modifier = Modifier.fillMaxWidth()
                            .heightIn(min = rowHeight)
                            .clip(RoundedCornerShape(IMFITCornerRadius.template))
                            .clickable(role = Role.Button) { onCategorySelected(category) }
                            .padding(vertical = IMFITSpacing.md),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)
                    ) {
                        MuscleCategoryArtwork(
                            asset = category.artwork,
                            modifier = Modifier.widthIn(max = IMFITComponentSizes.categoryArtwork).fillMaxWidth().aspectRatio(1f)
                        )
                        Text(
                            text = stringResource(category.labelRes),
                            modifier = Modifier.fillMaxWidth(),
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

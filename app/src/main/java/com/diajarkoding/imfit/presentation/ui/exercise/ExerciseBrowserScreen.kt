package com.diajarkoding.imfit.presentation.ui.exercise

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.ui.tooling.preview.Preview
import com.diajarkoding.imfit.presentation.components.common.FitnessCenter
import com.diajarkoding.imfit.presentation.components.common.Symbols
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.diajarkoding.imfit.domain.model.MuscleCategory
import com.diajarkoding.imfit.theme.IMFITShapes
import com.diajarkoding.imfit.theme.IMFITSizes
import com.diajarkoding.imfit.theme.IMFITSpacing
import com.diajarkoding.imfit.theme.Primary
import com.diajarkoding.imfit.theme.PrimaryLight

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseBrowserScreen(
    onNavigateBack: () -> Unit,
    onCategorySelected: (MuscleCategory) -> Unit
) {
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(IMFITShapes.IconContainer)
                                .background(
                                    Brush.linearGradient(listOf(Primary, PrimaryLight))
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Symbols.Default.FitnessCenter,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(IMFITSizes.iconSm)
                            )
                        }
                        Spacer(modifier = Modifier.width(IMFITSpacing.md))
                        Text(
                            text = stringResource(id = com.diajarkoding.imfit.R.string.nav_exercises_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                windowInsets = WindowInsets(0)
            )
        }
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            val gridColumns = if (maxWidth < 600.dp) {
                GridCells.Fixed(2)
            } else {
                GridCells.Adaptive(minSize = 160.dp)
            }

            LazyVerticalGrid(
                columns = gridColumns,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    horizontal = IMFITSpacing.screenHorizontal,
                    vertical = IMFITSpacing.screenVertical
                ),
                horizontalArrangement = Arrangement.spacedBy(IMFITSpacing.md),
                verticalArrangement = Arrangement.spacedBy(IMFITSpacing.md)
            ) {
                items(MuscleCategory.entries, key = { it.name }) { category ->
                    MuscleCategoryCard(
                        category = category,
                        onClick = { onCategorySelected(category) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1.15f)
                    )
                }
            }
        }
    }
}

@Composable
private fun MuscleCategoryCard(
    category: MuscleCategory,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = IMFITShapes.Card,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Primary.copy(alpha = 0.08f),
                            Primary.copy(alpha = 0.02f)
                        )
                    )
                )
                .padding(IMFITSpacing.cardPadding),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(IMFITShapes.IconContainer)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Symbols.Default.FitnessCenter,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(IMFITSizes.iconMd)
                    )
                }
                Spacer(modifier = Modifier.size(IMFITSpacing.md))
                Text(
                    text = stringResource(id = category.stringResourceId),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 2
                )
            }
        }
    }
}

@Preview(
    name = "Compact phone",
    widthDp = 360,
    heightDp = 800,
    showBackground = true,
    showSystemUi = true
)
@Composable
private fun ExerciseBrowserScreenPreview() {
    com.diajarkoding.imfit.theme.IMFITTheme(darkTheme = false) {
        ExerciseBrowserScreen(
            onNavigateBack = {},
            onCategorySelected = {}
        )
    }
}

@Preview(
    name = "Compact phone dark",
    widthDp = 360,
    heightDp = 800,
    showBackground = true,
    showSystemUi = true
)
@Composable
private fun ExerciseBrowserScreenPreviewDark() {
    com.diajarkoding.imfit.theme.IMFITTheme(darkTheme = true) {
        ExerciseBrowserScreen(
            onNavigateBack = {},
            onCategorySelected = {}
        )
    }
}

@Preview(
    name = "Expanded screen",
    widthDp = 800,
    heightDp = 1280,
    showBackground = true
)
@Composable
private fun ExerciseBrowserScreenExpandedPreview() {
    com.diajarkoding.imfit.theme.IMFITTheme(darkTheme = false) {
        ExerciseBrowserScreen(
            onNavigateBack = {},
            onCategorySelected = {}
        )
    }
}

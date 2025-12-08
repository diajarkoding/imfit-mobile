package com.diajarkoding.imfit.presentation.ui.exercise

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitnessCenter
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
                                imageVector = Icons.Default.FitnessCenter,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(IMFITSizes.iconSm)
                            )
                        }
                        Spacer(modifier = Modifier.width(IMFITSpacing.md))
                        Text(
                            text = "Exercises",
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
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(IMFITSpacing.screenHorizontal),
            horizontalArrangement = Arrangement.spacedBy(IMFITSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(IMFITSpacing.sm)
        ) {
            items(MuscleCategory.entries) { category ->
                MuscleCategoryCard(
                    category = category,
                    onClick = { onCategorySelected(category) }
                )
            }
        }
    }
}

@Composable
private fun MuscleCategoryCard(
    category: MuscleCategory,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.85f)
            .clickable { onClick() },
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
                .padding(IMFITSpacing.md),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(IMFITShapes.IconContainer)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.FitnessCenter,
                        contentDescription = null,
                        tint = Primary,
                        modifier = Modifier.size(IMFITSizes.iconSm)
                    )
                }
                Spacer(modifier = Modifier.size(IMFITSpacing.sm))
                Text(
                    text = category.displayName,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 2
                )
            }
        }
    }
}

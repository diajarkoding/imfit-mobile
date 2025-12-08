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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.diajarkoding.imfit.domain.model.Exercise
import com.diajarkoding.imfit.domain.model.MuscleCategory
import com.diajarkoding.imfit.presentation.components.common.IMFITButton
import com.diajarkoding.imfit.theme.IMFITShapes
import com.diajarkoding.imfit.theme.IMFITSizes
import com.diajarkoding.imfit.theme.IMFITSpacing
import com.diajarkoding.imfit.theme.Primary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseSelectionScreen(
    templateId: String,
    onNavigateBack: () -> Unit,
    onExercisesSelected: (List<Exercise>) -> Unit,
    viewModel: ExerciseSelectionViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars),
                title = {
                    Text(
                        text = "Select Exercises",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                windowInsets = WindowInsets(0)
            )
        },
        bottomBar = {
            if (state.selectedExercises.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(8.dp, spotColor = Color.Black.copy(alpha = 0.1f))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(IMFITSpacing.screenHorizontal)
                        .padding(vertical = IMFITSpacing.lg)
                ) {
                    IMFITButton(
                        text = "Add ${state.selectedExercises.size} Exercise(s)",
                        onClick = { onExercisesSelected(state.selectedExercises) },
                        icon = Icons.Default.Check
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Search Bar
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = { viewModel.onSearchQueryChange(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = IMFITSpacing.screenHorizontal)
                    .padding(top = IMFITSpacing.sm),
                placeholder = { Text("Search exercises...") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                trailingIcon = {
                    if (state.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onSearchQueryChange("") }) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Clear",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                singleLine = true,
                shape = IMFITShapes.TextField,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                )
            )

            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(
                    horizontal = IMFITSpacing.screenHorizontal,
                    vertical = IMFITSpacing.sm
                ),
                horizontalArrangement = Arrangement.spacedBy(IMFITSpacing.sm)
            ) {
                item {
                    FilterChip(
                        selected = state.selectedCategory == null,
                        onClick = { viewModel.selectCategory(null) },
                        label = { Text("All") },
                        leadingIcon = if (state.selectedCategory == null) {
                            {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        } else null,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Primary.copy(alpha = 0.15f),
                            selectedLabelColor = Primary
                        )
                    )
                }

                items(MuscleCategory.entries.toList()) { category ->
                    FilterChip(
                        selected = state.selectedCategory == category,
                        onClick = { viewModel.selectCategory(category) },
                        label = { Text(category.displayName) },
                        leadingIcon = if (state.selectedCategory == category) {
                            {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        } else null,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Primary.copy(alpha = 0.15f),
                            selectedLabelColor = Primary
                        )
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(IMFITSpacing.screenHorizontal),
                verticalArrangement = Arrangement.spacedBy(IMFITSpacing.md)
            ) {
                item { Spacer(modifier = Modifier.height(IMFITSpacing.xs)) }

                items(state.filteredExercises) { exercise ->
                    SelectableExerciseCard(
                        exercise = exercise,
                        isSelected = state.selectedExercises.contains(exercise),
                        onToggle = { viewModel.toggleExercise(exercise) }
                    )
                }

                item { Spacer(modifier = Modifier.height(100.dp)) }
            }
        }
    }
}

@Composable
private fun SelectableExerciseCard(
    exercise: Exercise,
    isSelected: Boolean,
    onToggle: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() },
        shape = IMFITShapes.Card,
        colors = CardDefaults.cardColors(
            containerColor =
                MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(IMFITSpacing.cardPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(IMFITShapes.IconContainer)
                    .background(
                        MaterialTheme.colorScheme.primaryContainer
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.FitnessCenter,
                    contentDescription = null,
                    tint = Primary,
                    modifier = Modifier.size(IMFITSizes.iconMd)
                )
            }

            Spacer(modifier = Modifier.width(IMFITSpacing.lg))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = exercise.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(IMFITSpacing.xs))
                Text(
                    text = exercise.muscleCategory.displayName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggle() },
                colors = CheckboxDefaults.colors(
                    checkedColor = Primary,
                    checkmarkColor = Color.White
                )
            )
        }
    }
}

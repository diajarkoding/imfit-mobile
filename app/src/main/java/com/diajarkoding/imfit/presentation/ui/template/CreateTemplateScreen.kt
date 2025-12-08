package com.diajarkoding.imfit.presentation.ui.template

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.diajarkoding.imfit.domain.model.TemplateExercise
import com.diajarkoding.imfit.presentation.components.common.IMFITButton
import com.diajarkoding.imfit.presentation.components.common.IMFITOutlinedButton
import com.diajarkoding.imfit.presentation.components.common.IMFITTextField
import com.diajarkoding.imfit.theme.DeletePink
import com.diajarkoding.imfit.theme.IMFITShapes
import com.diajarkoding.imfit.theme.IMFITSizes
import com.diajarkoding.imfit.theme.IMFITSpacing
import com.diajarkoding.imfit.theme.Primary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateTemplateScreen(
    onNavigateBack: () -> Unit,
    onNavigateToExerciseSelection: (String) -> Unit,
    onTemplateSaved: () -> Unit,
    viewModel: CreateTemplateViewModel = hiltViewModel(),
    selectedExercises: List<Exercise>? = null
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(selectedExercises) {
        selectedExercises?.let { exercises ->
            viewModel.addExercises(exercises)
        }
    }

    LaunchedEffect(state.isSaved) {
        if (state.isSaved) {
            onTemplateSaved()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars),
                title = {
                    Text(
                        text = "Create Template",
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
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(IMFITSpacing.screenHorizontal),
                verticalArrangement = Arrangement.spacedBy(IMFITSpacing.lg)
            ) {
                item {
                    Spacer(modifier = Modifier.height(IMFITSpacing.sm))
                    IMFITTextField(
                        value = state.templateName,
                        onValueChange = { viewModel.onNameChange(it) },
                        label = "Template Name",
                        placeholder = "e.g., Push Day, Leg Day",
                        error = state.nameError
                    )
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Exercises (${state.selectedExercises.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                item {
                    IMFITOutlinedButton(
                        text = "Add Exercise",
                        onClick = { onNavigateToExerciseSelection(state.tempTemplateId) },
                        icon = Icons.Default.Add
                    )
                }

                if (state.selectedExercises.isEmpty()) {
                    item { EmptyExercisePrompt() }
                } else {
                    items(state.selectedExercises) { templateExercise ->
                        ExerciseItem(
                            templateExercise = templateExercise,
                            onRemove = { viewModel.removeExercise(templateExercise) }
                        )
                    }
                }

                item { Spacer(modifier = Modifier.height(100.dp)) }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(8.dp, spotColor = Color.Black.copy(alpha = 0.1f))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(IMFITSpacing.screenHorizontal)
                    .padding(vertical = IMFITSpacing.lg)
            ) {
                IMFITButton(
                    text = "Save Template",
                    onClick = { viewModel.saveTemplate() },
                    enabled = state.templateName.isNotBlank() && state.selectedExercises.isNotEmpty(),
                    isLoading = state.isLoading
                )
            }
        }
    }
}

@Composable
private fun EmptyExercisePrompt() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = IMFITShapes.Card,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(IMFITSpacing.xxxl),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(IMFITShapes.IconContainer)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(IMFITSizes.iconLg)
                )
            }
            Spacer(modifier = Modifier.height(IMFITSpacing.lg))
            Text(
                text = "No exercises added yet",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(IMFITSpacing.xs))
            Text(
                text = "Add exercises to your template",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun ExerciseItem(
    templateExercise: TemplateExercise,
    onRemove: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = IMFITShapes.Card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(IMFITSpacing.cardPadding),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
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
                Spacer(modifier = Modifier.width(IMFITSpacing.md))
                Column {
                    Text(
                        text = templateExercise.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(IMFITSpacing.xs))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = templateExercise.muscleCategory.displayName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = " • ${templateExercise.sets} x ${templateExercise.reps} reps",
                            style = MaterialTheme.typography.bodySmall,
                            color = Primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Remove",
                    tint = DeletePink,
                    modifier = Modifier.size(IMFITSizes.iconMd)
                )
            }
        }
    }
}

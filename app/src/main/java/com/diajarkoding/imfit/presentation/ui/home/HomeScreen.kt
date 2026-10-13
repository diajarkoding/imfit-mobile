package com.diajarkoding.imfit.presentation.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.data.sync.SyncState
import com.diajarkoding.imfit.presentation.components.common.*
import com.diajarkoding.imfit.theme.IMFITSpacing
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun HomeScreen(
    onNavigateToWorkoutDetail: (String) -> Unit,
    onNavigateToActiveWorkout: (String) -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
    onNavigateToProfile: () -> Unit = {}
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()
    var showDialog by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var previousSync by remember { mutableStateOf(syncState.status) }
    LaunchedEffect(Unit) { viewModel.refresh() }
    LaunchedEffect(syncState.status) {
        if (previousSync == SyncState.SyncStatus.SYNCING && syncState.status == SyncState.SyncStatus.SYNCED) viewModel.refresh()
        previousSync = syncState.status
    }
    LaunchedEffect(state.newlyCreatedWorkoutId) {
        state.newlyCreatedWorkoutId?.let {
            viewModel.clearNewlyCreatedWorkoutId()
            onNavigateToWorkoutDetail(it)
        }
    }
    if (showDialog) IMFITInputDialog(
        onDismissRequest = { showDialog = false; name = "" },
        title = stringResource(R.string.home_create_workout_title),
        message = stringResource(R.string.home_create_workout_message),
        icon = Symbols.Default.Add,
        confirmText = stringResource(R.string.action_create),
        dismissText = stringResource(R.string.action_cancel),
        confirmEnabled = name.isNotBlank(),
        onConfirm = { if (name.isNotBlank()) { viewModel.createWorkout(name); showDialog = false; name = "" } }
    ) {
        OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), placeholder = { Text(stringResource(R.string.home_workout_placeholder)) })
    }
    LazyColumn(contentPadding = PaddingValues(IMFITSpacing.screenHorizontal), verticalArrangement = Arrangement.spacedBy(IMFITSpacing.lg), modifier = Modifier.fillMaxSize()) {
        item { ImfitPageHeader(stringResource(R.string.app_name), onNavigateToProfile, brand = true, profilePhoto = state.userProfilePhotoUri) }
        item { SyncIndicator(syncState = syncState, showText = false) }
        if (state.isLoading) {
            item { ShimmerWelcomeSection() }
            item { ShimmerStatCard() }
            items(3) { ShimmerWorkoutCard() }
        } else {
            item {
                Column {
                    Text(if (state.userName.isBlank()) stringResource(R.string.redesign_hello) else stringResource(R.string.redesign_greeting, state.userName), style = MaterialTheme.typography.headlineMedium)
                    Text(stringResource(R.string.redesign_ready), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                val log = state.lastWorkout
                ImfitHeroSummaryCard(ImfitHeroKind.HOME, stringResource(R.string.redesign_latest), log?.templateName ?: stringResource(R.string.redesign_no_workout),
                    log?.let { stringResource(R.string.redesign_workout_meta, redesignDate(it.startTime), it.exerciseLogs.size) } ?: stringResource(R.string.redesign_start_hint)) {
                    if (log != null) {
                        Spacer(Modifier.height(IMFITSpacing.lg))
                        Row(horizontalArrangement = Arrangement.spacedBy(IMFITSpacing.md)) {
                            ImfitStatTile(log.formattedDuration, stringResource(R.string.label_duration), Modifier.weight(1f), Symbols.Default.Schedule, compact = true)
                            ImfitStatTile(log.formattedVolume, stringResource(R.string.redesign_session_volume), Modifier.weight(1f), compact = true)
                        }
                    }
                }
            }
            item { ImfitSectionHeader(stringResource(R.string.home_title_my_workouts)) }
            if (state.templates.isEmpty()) item { ImfitEmptyMessage(stringResource(R.string.redesign_no_templates)) }
            items(state.templates, key = { it.id }) { template ->
                val active = state.activeWorkoutTemplateId == template.id
                ImfitWorkoutRow(template.name, stringResource(R.string.redesign_exercise_count, template.exercises.size) + if (active) " · " + stringResource(R.string.redesign_resume) else "", template.exercises.firstOrNull()?.exercise?.muscleCategory) {
                    if (active) onNavigateToActiveWorkout(template.id) else onNavigateToWorkoutDetail(template.id)
                }
            }
            item { IMFITButton(text = stringResource(R.string.action_add_workout), onClick = { showDialog = true }, icon = Symbols.Default.Add) }
        }
        item { Spacer(Modifier.height(IMFITSpacing.lg)) }
    }
}

internal fun redesignDate(epoch: Long): String = Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.getDefault()))

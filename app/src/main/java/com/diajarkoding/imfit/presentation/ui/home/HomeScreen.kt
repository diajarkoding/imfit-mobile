package com.diajarkoding.imfit.presentation.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
        confirmText = stringResource(R.string.routine_create_action),
        dismissText = stringResource(R.string.action_cancel),
        confirmEnabled = name.isNotBlank(),
        onConfirm = { if (name.isNotBlank()) { viewModel.createWorkout(name); showDialog = false; name = "" } }
    ) {
        OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), placeholder = { Text(stringResource(R.string.home_workout_placeholder)) })
    }
    Column(Modifier.fillMaxSize().background(homePalette().background)) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(start = IMFITSpacing.screenHorizontal, top = IMFITSpacing.md,
                end = IMFITSpacing.screenHorizontal, bottom = IMFITSpacing.screenVertical),
            verticalArrangement = Arrangement.spacedBy(IMFITSpacing.itemSpacing)
        ) {
            item(key = "greeting") {
                Box(Modifier.padding(bottom = IMFITSpacing.sm)) {
                    if (state.isLoading) ShimmerWelcomeSection() else HomeGreeting(state.userName)
                }
            }
            item(key = "strength_banner") {
                Box(Modifier.padding(bottom = IMFITSpacing.sm)) { HomeStrengthBanner() }
            }
            item(key = "last_workout") {
                Box(Modifier.padding(bottom = IMFITSpacing.sm)) {
                    if (state.isLoading) ShimmerStatCard() else HomeLastWorkout(state.lastWorkout)
                }
            }
            if (syncState.pendingCount > 0 ||
                syncState.status == SyncState.SyncStatus.SYNCING ||
                syncState.status == SyncState.SyncStatus.FAILED
            ) {
                item(key = "sync_status") {
                    HomeSyncStatusCard(syncState, Modifier.padding(bottom = IMFITSpacing.sm))
                }
            }
            item(key = "workout_section") {
                HomeWorkoutSectionHeader(enabled = !state.isLoading, onAdd = { showDialog = true })
            }
            if (state.isLoading) {
                items(3, key = { "loading_$it" }) { ShimmerWorkoutCard() }
            } else {
                if (state.templates.isEmpty()) {
                    item(key = "empty_templates") { ImfitEmptyMessage(stringResource(R.string.home_empty_templates)) }
                }
                items(state.templates, key = { "template_${it.id}" }) { template ->
                    val active = state.activeWorkoutTemplateId == template.id
                    HomeWorkoutCard(template, active) {
                        if (active) onNavigateToActiveWorkout(template.id) else onNavigateToWorkoutDetail(template.id)
                    }
                }
            }
        }
    }
}

internal fun redesignDate(epoch: Long): String = Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.getDefault()))

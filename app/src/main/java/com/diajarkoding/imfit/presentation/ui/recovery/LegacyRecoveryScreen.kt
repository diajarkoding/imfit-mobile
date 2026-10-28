package com.diajarkoding.imfit.presentation.ui.recovery

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.data.legacy.*
import com.diajarkoding.imfit.presentation.components.common.*
import com.diajarkoding.imfit.theme.IMFITColors
import com.diajarkoding.imfit.theme.IMFITSpacing

@Composable
fun LegacyRecoveryScreen(onBack: () -> Unit, viewModel: LegacyRecoveryViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LegacyRecoveryContent(state, onBack, viewModel::refresh, viewModel::inspect, viewModel::closeDetails, viewModel::keepHistory)
}

@Composable
internal fun LegacyRecoveryContent(state: LegacyRecoveryState, onBack: () -> Unit, onRetry: () -> Unit,
    onInspect: (LegacyItem) -> Unit, onCloseDetails: () -> Unit, onKeepHistory: () -> Unit) {
    var confirmation by rememberSaveable { mutableStateOf(false) }
    val present = state.inventory as? LegacyInventory.Present
    Scaffold(containerColor = IMFITColors.training.background,
        topBar = { RoutineAppBar(stringResource(R.string.legacy_recovery_title), onBack) },
        contentWindowInsets = WindowInsets.navigationBars) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).testTag("legacy_recovery_list"),
            contentPadding = PaddingValues(horizontal = IMFITSpacing.screenHorizontal, vertical = IMFITSpacing.headerContentGap),
            verticalArrangement = Arrangement.spacedBy(IMFITSpacing.md)) {
            item("explanation") { Text(stringResource(R.string.legacy_recovery_explanation), style = MaterialTheme.typography.bodyMedium) }
            if (state.loading) item("loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (state.failed || state.inventory is LegacyInventory.Unknown) item("error") {
                Text(stringResource(R.string.legacy_recovery_failed), color = MaterialTheme.colorScheme.error)
            }
            item("retry") { RoutineButton(stringResource(R.string.redesign_retry), onRetry, enabled = !state.loading, outlined = true) }
            if (state.inventory is LegacyInventory.Absent || present?.items?.isEmpty() == true) item("empty") {
                Text(stringResource(R.string.legacy_recovery_empty))
            }
            if (present != null) {
                item("schema") { Text(stringResource(R.string.legacy_recovery_schema, present.schemaVersion), style = MaterialTheme.typography.labelMedium) }
                if (present.items.any { it.kind in LegacyArchiveReview.historicalKinds && state.archive?.covers(it) != true } ||
                    (present.schemaVersion != 9 && state.archive?.schemaVersion != present.schemaVersion)) item("archive") {
                    RoutineButton(stringResource(R.string.legacy_keep_history), { confirmation = true },
                        enabled = !state.loading && present.items.none { it.disposition == LegacyDisposition.PROBLEM }, outlined = true)
                }
                items(present.items, key = { "${it.kind}:${it.id}" }) { item ->
                    OutlinedCard(onClick = { onInspect(item) }, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(IMFITSpacing.cardPadding), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(kindLabel(item.kind)), style = MaterialTheme.typography.titleSmall)
                            Text(item.id, style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(if (state.archive?.covers(item) == true) R.string.legacy_kept_not_uploaded else R.string.legacy_review_pending),
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            if (state.selected != null) item("details") {
                Text(stringResource(R.string.legacy_record_details), style = MaterialTheme.typography.titleSmall)
                SelectionContainer {
                    Text(state.fields?.toSortedMap()?.entries?.joinToString("\n\n") { (key, value) -> "$key\n${value ?: "null"}" }.orEmpty(),
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("legacy_record_fields"))
                }
                RoutineButton(stringResource(R.string.action_back), onCloseDetails, enabled = !state.loading, outlined = true)
            }
            item("bottom") { Spacer(Modifier.height(IMFITSpacing.xl)) }
        }
    }
    if (confirmation) AlertDialog(onDismissRequest = { if (!state.loading) confirmation = false },
        title = { Text(stringResource(R.string.legacy_keep_history)) },
        text = { Text(stringResource(R.string.legacy_keep_confirmation)) },
        confirmButton = { TextButton(onClick = { confirmation = false; onKeepHistory() }, enabled = !state.loading) {
            Text(stringResource(R.string.legacy_keep_confirm))
        } },
        dismissButton = { TextButton(onClick = { confirmation = false }) { Text(stringResource(R.string.action_back)) } })
}

private fun kindLabel(kind: LegacyKind): Int = when (kind) {
    LegacyKind.PROFILE -> R.string.legacy_kind_profile
    LegacyKind.TEMPLATE -> R.string.legacy_kind_template
    LegacyKind.TEMPLATE_EXERCISE -> R.string.legacy_kind_template_exercise
    LegacyKind.WORKOUT -> R.string.legacy_kind_workout
    LegacyKind.EXERCISE_LOG -> R.string.legacy_kind_exercise_log
    LegacyKind.SET -> R.string.legacy_kind_set
    LegacyKind.ACTIVE_SESSION -> R.string.legacy_kind_active
    LegacyKind.TEMPLATE_INTENT -> R.string.legacy_kind_intent
}

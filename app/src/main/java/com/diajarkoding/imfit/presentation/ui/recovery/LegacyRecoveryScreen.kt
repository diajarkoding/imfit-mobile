package com.diajarkoding.imfit.presentation.ui.recovery

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.data.legacy.*
import com.diajarkoding.imfit.data.legacy.LegacyRecoveryState as RecoveryActionState
import com.diajarkoding.imfit.data.remote.online.AuthIdentity
import com.diajarkoding.imfit.data.remote.online.LegacyRecoveryItem
import com.diajarkoding.imfit.data.remote.online.SessionStatus
import com.diajarkoding.imfit.presentation.components.common.*
import com.diajarkoding.imfit.theme.IMFITColors
import com.diajarkoding.imfit.theme.IMFITSpacing

private data class RecoveryConfirmation(
    val identity: AuthIdentity?, val title: Int, val message: Int, val action: () -> Unit,
)

@Composable
fun LegacyRecoveryScreen(
    onBack: () -> Unit,
    viewModel: LegacyRecoveryViewModel = hiltViewModel(),
    onResumeSession: (String, String) -> Unit = { _, _ -> },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmation by remember(state.identity) { mutableStateOf<RecoveryConfirmation?>(null) }
    var selectedServer by remember(state.identity) { mutableStateOf<LegacyRecoveryItem?>(null) }
    fun confirm(title: Int, message: Int = R.string.legacy_action_confirmation, action: () -> Unit) {
        confirmation = RecoveryConfirmation(state.identity, title, message, action)
    }
    val present = state.inventory as? LegacyInventory.Present
    val remote = state.review?.remoteCurrent?.session
    Scaffold(
        containerColor = IMFITColors.training.background,
        topBar = { RoutineAppBar(stringResource(R.string.legacy_recovery_title), onBack) },
        contentWindowInsets = WindowInsets.navigationBars,
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).testTag("legacy_recovery_list"),
            contentPadding = PaddingValues(horizontal = IMFITSpacing.screenHorizontal, vertical = IMFITSpacing.headerContentGap),
            verticalArrangement = Arrangement.spacedBy(IMFITSpacing.md),
        ) {
            item("explanation") {
                Text(stringResource(R.string.legacy_recovery_explanation), style = MaterialTheme.typography.bodyMedium)
                state.identity?.let { Text(stringResource(R.string.legacy_account, it.ownerId), style = MaterialTheme.typography.bodySmall) }
            }
            if (state.loading) item("loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (state.failed || state.inventory is LegacyInventory.Unknown) item("error") {
                Text(stringResource(R.string.legacy_recovery_failed), color = MaterialTheme.colorScheme.error)
                (state.inventory as? LegacyInventory.Unknown)?.let { Text(it.failure.name, style = MaterialTheme.typography.bodySmall) }
                state.review?.failures?.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            item("retry") {
                RoutineButton(stringResource(R.string.redesign_retry), viewModel::refresh, enabled = !state.loading, outlined = true)
            }
            state.result?.let { result -> item("result") {
                val label = when (result) {
                    is LegacyRecoveryResult.Confirmed -> R.string.legacy_receipt_confirmed
                    is LegacyRecoveryResult.RetryOriginalRequired -> R.string.legacy_retry_required
                    is LegacyRecoveryResult.Unresolved -> R.string.legacy_outcome_unknown
                    is LegacyRecoveryResult.Conflict -> R.string.legacy_conflict
                    is LegacyRecoveryResult.Blocked -> R.string.legacy_blocked
                }
                Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
            } }
            if (state.inventory is LegacyInventory.Absent || present?.items?.isEmpty() == true) item("empty") {
                Text(stringResource(R.string.legacy_recovery_empty))
            }
            if (present != null) {
                item("schema") { Text(stringResource(R.string.legacy_recovery_schema, present.schemaVersion), style = MaterialTheme.typography.labelMedium) }
                if (present.items.any { it.kind in LegacyArchiveReview.historicalKinds && it !in state.historical }) item("archive") {
                    RoutineButton(stringResource(R.string.legacy_keep_history), {
                        confirm(R.string.legacy_keep_history, R.string.legacy_keep_confirmation, viewModel::keepHistory)
                    }, enabled = !state.loading && present.items.none { it.disposition == LegacyDisposition.PROBLEM }, outlined = true)
                }
                items(present.items, key = { "${it.kind}:${it.id}" }) { item ->
                    OutlinedCard(onClick = { viewModel.inspect(item) }, enabled = !state.loading,
                        modifier = Modifier.fillMaxWidth().testTag("legacy_item_${item.kind}:${item.id}")) {
                        Column(Modifier.padding(IMFITSpacing.cardPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(kindLabel(item.kind)), style = MaterialTheme.typography.titleSmall)
                            Text(item.id, style = MaterialTheme.typography.bodySmall)
                            val status = when {
                                item in state.covered && item.kind == LegacyKind.ACTIVE_SESSION -> R.string.legacy_active_archived
                                item in state.covered -> R.string.legacy_receipt_confirmed
                                item in state.historical -> R.string.legacy_kept_not_uploaded
                                item.disposition == LegacyDisposition.PROBLEM -> R.string.legacy_blocked
                                else -> R.string.legacy_review_pending
                            }
                            Text(stringResource(status), style = MaterialTheme.typography.bodyMedium)
                            if (item !in state.covered && item.kind == LegacyKind.WORKOUT && item.disposition == LegacyDisposition.PENDING_CREATE) {
                                RoutineButton(stringResource(R.string.legacy_import_selected), {
                                    confirm(R.string.legacy_import_selected, R.string.legacy_import_confirmation) { viewModel.importWorkout(item) }
                                }, modifier = Modifier.testTag("legacy_import_${item.id}"), enabled = !state.loading)
                            }
                            if (item !in state.covered && item.kind == LegacyKind.TEMPLATE_INTENT && item.disposition != LegacyDisposition.PROBLEM) {
                                RoutineButton(stringResource(R.string.legacy_retry_original), {
                                    confirm(R.string.legacy_retry_original) { viewModel.replayTemplateIntent(item) }
                                }, enabled = !state.loading, outlined = true)
                            }
                            if (item !in state.covered && item.kind == LegacyKind.ACTIVE_SESSION && item.disposition != LegacyDisposition.PROBLEM) {
                                RoutineButton(stringResource(R.string.legacy_abandon_active), {
                                    confirm(R.string.legacy_abandon_active, R.string.legacy_abandon_confirmation) { viewModel.abandonActive(item) }
                                }, modifier = Modifier.testTag("legacy_abandon_${item.id}"), enabled = !state.loading, outlined = true)
                            }
                        }
                    }
                }
                if (present.dependencies.isNotEmpty()) item("dependencies") {
                    Text(stringResource(R.string.legacy_dependencies), style = MaterialTheme.typography.titleSmall)
                    present.dependencies.forEach { dependency ->
                        Text("${dependency.kind} · ${dependency.id}\n${stringResource(if (dependency.available)
                            R.string.legacy_dependency_available else R.string.legacy_dependency_missing)}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            items(state.review?.actions.orEmpty(), key = { "action:${it.actionId}" }) { action ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(IMFITSpacing.cardPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${action.operation}\n${action.sourceId}", style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(actionLabel(action.state)), style = MaterialTheme.typography.bodyMedium)
                        action.failure?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        if (action.state == RecoveryActionState.PREPARED || action.state == RecoveryActionState.SUBMITTED) {
                            RoutineButton(stringResource(R.string.legacy_resolve), {
                                confirm(R.string.legacy_resolve) { viewModel.resolveAction(action.actionId) }
                            }, modifier = Modifier.testTag("legacy_resolve_${action.actionId}"), enabled = !state.loading, outlined = true)
                            RoutineButton(stringResource(R.string.legacy_retry_original), {
                                confirm(R.string.legacy_retry_original) { viewModel.retryOriginal(action.actionId) }
                            }, modifier = Modifier.testTag("legacy_retry_${action.actionId}"), enabled = !state.loading, outlined = true)
                        }
                    }
                }
            }
            state.review?.readiness?.let { readiness -> item("readiness") {
                Text(stringResource(if (readiness.canCreate) R.string.legacy_start_ready else R.string.legacy_start_blocked))
                readiness.blockers.forEach { blocker ->
                    Text("${blocker.kind} · ${blocker.resourceId}", style = MaterialTheme.typography.bodySmall)
                }
                if (readiness.hasMoreBlockers) Text(stringResource(R.string.legacy_more_blockers))
            } }
            if (remote != null) item("remote_session") {
                Text(stringResource(R.string.legacy_remote_session), style = MaterialTheme.typography.titleSmall)
                Text("${remote.id} · ${remote.effectiveStatus}", style = MaterialTheme.typography.bodySmall)
                if (!remote.reviewRequired && remote.effectiveStatus in setOf(SessionStatus.ACTIVE, SessionStatus.PAUSED)) {
                    RoutineButton(stringResource(R.string.action_resume), {
                        confirm(R.string.action_resume) { viewModel.resumeRemote(onResumeSession) }
                    }, enabled = !state.loading)
                    RoutineButton(stringResource(R.string.action_cancel_workout), {
                        confirm(R.string.action_cancel_workout) { viewModel.cancelRemote() }
                    }, enabled = !state.loading, outlined = true)
                } else if (remote.effectiveStatus == SessionStatus.EXPIRED && remote.reviewRequired) {
                    RoutineButton(stringResource(R.string.legacy_acknowledge_expiry), {
                        confirm(R.string.legacy_acknowledge_expiry, R.string.legacy_expiry_confirmation, viewModel::acknowledgeExpiry)
                    }, enabled = !state.loading)
                }
            }
            item("server_page") {
                RoutineButton(stringResource(if (!state.legacyPageLoaded) R.string.legacy_load_server else R.string.legacy_load_more), viewModel::loadLegacyPage,
                    modifier = Modifier.testTag("legacy_server_page"), enabled = !state.loading && (!state.legacyPageLoaded || state.hasMoreLegacy), outlined = true)
            }
            items(state.legacySessions, key = { "server:${it.sessionId}" }) { item ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(IMFITSpacing.cardPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(item.sessionId, style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(if (item.resolution == null) R.string.legacy_review_pending else R.string.legacy_server_archived))
                        RoutineButton(stringResource(R.string.legacy_inspect), { selectedServer = item }, enabled = !state.loading, outlined = true)
                        if (item.resolution == null) RoutineButton(stringResource(R.string.legacy_keep_server), {
                            confirm(R.string.legacy_keep_server) { selectedServer = null; viewModel.archiveServerSession(item) }
                        }, enabled = !state.loading)
                    }
                }
            }
            if (state.selected != null || selectedServer != null) item("details") {
                Text(stringResource(R.string.legacy_record_details), style = MaterialTheme.typography.titleSmall)
                SelectionContainer {
                    Text(selectedServer?.rawSource?.toString() ?: state.fields?.toSortedMap()?.entries?.joinToString("\n\n") { (key, value) ->
                        "$key\n${value ?: "null"}"
                    }.orEmpty(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("legacy_record_fields"))
                }
                RoutineButton(stringResource(R.string.action_back), { selectedServer = null; viewModel.closeDetails() }, enabled = !state.loading, outlined = true)
            }
            item("bottom") { Spacer(Modifier.height(IMFITSpacing.xl)) }
        }
    }
    confirmation?.let { pending ->
        AlertDialog(
            onDismissRequest = { if (!state.loading) confirmation = null },
            title = { Text(stringResource(pending.title)) },
            text = { Text(stringResource(pending.message)) },
            confirmButton = { TextButton(onClick = {
                confirmation = null
                viewModel.confirm(pending.identity, pending.action)
            }, enabled = !state.loading) { Text(stringResource(R.string.action_continue)) } },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text(stringResource(R.string.action_back)) } },
        )
    }
}

private fun actionLabel(state: RecoveryActionState): Int = when (state) {
    RecoveryActionState.AWAITING_REVIEW -> R.string.legacy_review_pending
    RecoveryActionState.PREPARED -> R.string.legacy_prepared
    RecoveryActionState.SUBMITTED -> R.string.legacy_outcome_unknown
    RecoveryActionState.CONFIRMED -> R.string.legacy_receipt_confirmed
    RecoveryActionState.CONFLICT -> R.string.legacy_conflict
    RecoveryActionState.BLOCKED -> R.string.legacy_blocked
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

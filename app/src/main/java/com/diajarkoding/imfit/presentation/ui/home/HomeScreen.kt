package com.diajarkoding.imfit.presentation.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.presentation.components.common.*
import com.diajarkoding.imfit.theme.IMFITSpacing
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToWorkoutDetail: (String) -> Unit,
    onNavigateToActiveWorkout: (String) -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
    onNavigateToProfile: () -> Unit = {}
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showDialog by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { viewModel.refreshOnEntry() }
    LaunchedEffect(state.newlyCreatedWorkoutId) {
        state.newlyCreatedWorkoutId?.let {
            showDialog = false
            name = ""
            viewModel.clearNewlyCreatedWorkoutId()
            onNavigateToWorkoutDetail(it)
        }
    }
    if (showDialog) RoutineDialog(
        onDismiss = { showDialog = false; name = ""; viewModel.clearCreateError() },
        title = stringResource(R.string.home_create_workout_title),
        message = stringResource(R.string.home_create_workout_message),
        icon = Symbols.Default.Add,
        confirmText = stringResource(if (state.isCreating) R.string.routine_creating else R.string.routine_create_action),
        processing = state.isCreating,
        errorMessage = state.createErrorRes?.let { stringResource(it) },
        onConfirm = { viewModel.createWorkout(name) }
    ) {
        RoutineNameField(name, { name = it; viewModel.clearCreateError() }, enabled = !state.isCreating,
            errorMessage = state.createNameErrorRes?.let { stringResource(it) }, onDone = { viewModel.createWorkout(name) }, requestFocus = true)
    }
    Column(Modifier.fillMaxSize().background(homePalette().background)) {
        PullToRefreshBox(
            isRefreshing = state.isPullRefreshing,
            onRefresh = viewModel::pullToRefresh,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .testTag("home_pull_to_refresh")
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = IMFITSpacing.screenHorizontal, top = IMFITSpacing.headerContentGap,
                    end = IMFITSpacing.screenHorizontal, bottom = IMFITSpacing.screenVertical),
                verticalArrangement = Arrangement.spacedBy(IMFITSpacing.itemSpacing)
            ) {
                item(key = "greeting") {
                Box(Modifier.padding(bottom = IMFITSpacing.sm).testTag("main_first_content")) {
                    if (state.isProfileLoading) ShimmerWelcomeSection() else HomeGreeting(state.userName)
                }
            }
            item(key = "strength_banner") {
                Box(Modifier.padding(bottom = IMFITSpacing.sm)) { HomeStrengthBanner() }
            }
            item(key = "last_workout") {
                Box(Modifier.padding(bottom = IMFITSpacing.sm)) {
                    when (val lastState = state.lastWorkoutState) {
                        is SectionLoadState.Loading -> ShimmerStatCard()
                        is SectionLoadState.Success -> HomeLastWorkout(lastState.data)
                        is SectionLoadState.Error -> {
                            if (lastState.hasPreviousData) {
                                HomeLastWorkout(lastState.previousData)
                            } else {
                                HomeSectionErrorCard(
                                    message = stringResource(R.string.home_last_workout_error),
                                    onRetry = viewModel::refresh,
                                    testTag = "home_last_workout_error"
                                )
                            }
                        }
                    }
                }
            }
            item(key = "workout_section") {
                HomeWorkoutSectionHeader(
                    enabled = !state.isTemplatesLoading && !state.isCreating,
                    onAdd = { viewModel.clearCreateError(); showDialog = true }
                )
            }
            when (val tplState = state.templatesState) {
                is SectionLoadState.Loading -> {
                    items(3, key = { "loading_$it" }) { ShimmerWorkoutCard() }
                }
                is SectionLoadState.Success -> {
                    if (tplState.data.isEmpty()) {
                        item(key = "empty_templates") { ImfitEmptyMessage(stringResource(R.string.home_empty_templates)) }
                    } else {
                        items(tplState.data, key = { "template_${it.id}" }) { template ->
                            val active = state.activeWorkoutTemplateId == template.id
                            HomeWorkoutCard(template, active) {
                                if (active) onNavigateToActiveWorkout(template.id) else onNavigateToWorkoutDetail(template.id)
                            }
                        }
                    }
                }
                is SectionLoadState.Error -> {
                    if (tplState.hasPreviousData && !tplState.previousData.isNullOrEmpty()) {
                        items(tplState.previousData, key = { "template_${it.id}" }) { template ->
                            val active = state.activeWorkoutTemplateId == template.id
                            HomeWorkoutCard(template, active) {
                                if (active) onNavigateToActiveWorkout(template.id) else onNavigateToWorkoutDetail(template.id)
                            }
                        }
                    } else {
                        item(key = "templates_error") {
                            HomeSectionErrorCard(
                                message = stringResource(R.string.home_templates_error),
                                onRetry = viewModel::refresh,
                                testTag = "home_templates_error"
                            )
                        }
                    }
                }
            }
        }
    }
    }
}

internal fun redesignDate(epoch: Long): String = Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.getDefault()))

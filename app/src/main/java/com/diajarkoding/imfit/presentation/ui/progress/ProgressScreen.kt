package com.diajarkoding.imfit.presentation.ui.progress

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.presentation.components.common.ShimmerStatCard
import com.diajarkoding.imfit.presentation.components.common.ShimmerWelcomeSection
import com.diajarkoding.imfit.theme.*
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.delay
import com.diajarkoding.imfit.presentation.components.common.*

@Composable
fun ProgressScreen(
    onNavigateToWorkoutHistory: (LocalDate) -> Unit = {},
    onNavigateToYearlyCalendar: (YearMonth) -> Unit = {},
    onNavigateToProfile: () -> Unit = {},
    refreshKey: Long? = null,
    viewModel: ProgressViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val now by produceState(initialValue = Instant.now()) {
        while (true) { value = Instant.now(); delay(60_000) }
    }
    val month = YearMonth.from(now.atZone(ZoneId.systemDefault()))
    LaunchedEffect(month) { viewModel.selectMonth(month); viewModel.selectDay(now.atZone(ZoneId.systemDefault()).toLocalDate()) }
    ProgressOverview(state, now, onNavigateToWorkoutHistory, onNavigateToYearlyCalendar,
        onNavigateToProfile, viewModel::loadData, onDay = viewModel::selectDay)
}

@Composable
internal fun ProgressOverview(
    state: ProgressState,
    now: Instant,
    onWorkoutHistory: (LocalDate) -> Unit,
    onYearlyCalendar: (YearMonth) -> Unit,
    onProfile: () -> Unit,
    onRetry: () -> Unit,
    onDay: (LocalDate) -> Unit = {}
) {
    val colors = IMFITColors.training
    val today = now.atZone(ZoneId.systemDefault()).toLocalDate()
    val month = YearMonth.from(today)
    var selectedText by rememberSaveable(month.toString()) { mutableStateOf<String?>(null) }
    val selectedDate = selectedText?.let(LocalDate::parse) ?: today
    val logs = state.workoutLogsByDate.values.flatten().distinctBy { it.id }
    val metrics = progressMetrics(logs, now, ZoneId.systemDefault(), month)
    RemoteRefreshBox(state.refreshing, onRetry, Modifier.fillMaxSize().background(colors.background)) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = IMFITSpacing.screenHorizontal, top = IMFITSpacing.headerContentGap,
                end = IMFITSpacing.screenHorizontal, bottom = IMFITSpacing.screenVertical),
            verticalArrangement = Arrangement.spacedBy(IMFITSpacing.xl)
        ) {
            item(key = "profile") {
                Box(Modifier.testTag("main_first_content")) {
                    if (state.isLoading && state.userName.isBlank()) ShimmerWelcomeSection()
                    else ProgressProfileRow(state, onProfile)
                }
            }
            when {
                state.isLoading -> {
                    item(key = "loading_metrics") { ShimmerStatCard() }
                    item(key = "loading_calendar") { ShimmerStatCard() }
                }
                (state.hasError || state.remoteMode) && state.aggregate == null -> item(key = "load_error") {
                    Column(verticalArrangement = Arrangement.spacedBy(IMFITSpacing.sm)) {
                        Text(stringResource(R.string.progress_overview_load_error), color = colors.text,
                            style = IMFITTypography.bodyMedium)
                        TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = IMFITSizes.buttonHeight)) {
                            Text(stringResource(R.string.redesign_retry))
                        }
                    }
                }
                else -> {
                    if (state.hasError) item("stale") { RemoteReadFailure(state.stale, onRetry) }
                    item(key = "metrics") {
                        val summary = state.aggregate
                        if (summary == null) ProgressMetricOverview(metrics) // Preview/legacy renderer only; production VM never fabricates totals.
                        else RemoteProgressMetrics(summary, month)
                    }
                    item(key = "calendar") {
                        if (!state.remoteMode || state.markersLoaded) {
                        ProgressMonthCalendar(month, today, selectedDate, state.workoutLogsByDate,
                            onSelect = { date ->
                                selectedText = date.toString()
                                 onDay(date)
                                 if ((state.markerCounts[date] ?: state.workoutLogsByDate[date].orEmpty().size.toLong()) > 0) onWorkoutHistory(date)
                            },
                             onYearlyCalendar = { onYearlyCalendar(month) }, markerCounts = state.markerCounts.takeIf { state.aggregate != null })
                        }
                    }
                    item(key = "selected_day") {
                        if (state.dayLoading && state.dayAggregate == null) ShimmerStatCard()
                        if (state.dayError) RemoteReadFailure(state.dayStale, onRetry)
                        if (state.dayAggregate != null && state.dayAggregate.startDate == selectedDate.toString()) RemoteSelectedDay(selectedDate, state.dayAggregate) { onWorkoutHistory(selectedDate) }
                        else if (!state.remoteMode) ProgressSelectedDay(selectedDate, state.workoutLogsByDate[selectedDate].orEmpty()) {
                            onWorkoutHistory(selectedDate)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RemoteProgressMetrics(summary: com.diajarkoding.imfit.data.remote.online.ProgressSummary, month: YearMonth) {
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val volume = java.text.NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 2 }.format(summary.totalVolume.toBigDecimal())
    val period = month.format(java.time.format.DateTimeFormatter.ofPattern("LLLL yyyy", locale))
    Column(verticalArrangement = Arrangement.spacedBy(IMFITSpacing.md)) {
        ProgressMetricCard(stringResource(R.string.redesign_kg, volume), stringResource(R.string.progress_overview_volume), period, Symbols.Default.FitnessCenter)
        ProgressMetricCard(stringResource(R.string.remote_read_minutes, summary.durationSeconds / 60), stringResource(R.string.label_duration), period, Symbols.Default.Timer)
    }
}

@Composable
private fun RemoteSelectedDay(date: LocalDate, summary: com.diajarkoding.imfit.data.remote.online.ProgressSummary, onHistory: () -> Unit) {
    Column {
        Text(date.toString(), style = IMFITTypography.compactTitle)
        RemotePeriodTotals(summary)
        if (summary.workoutCount > 0) TextButton(onHistory) { Text(stringResource(R.string.workout_day_card_view_details)) }
    }
}

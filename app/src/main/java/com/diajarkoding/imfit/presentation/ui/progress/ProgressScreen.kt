package com.diajarkoding.imfit.presentation.ui.progress

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
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

@Composable
fun ProgressScreen(
    onNavigateToWorkoutHistory: (LocalDate) -> Unit = {},
    onNavigateToYearlyCalendar: (YearMonth) -> Unit = {},
    onNavigateToProfile: () -> Unit = {},
    refreshKey: Long? = null,
    viewModel: ProgressViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(refreshKey) { viewModel.loadData() }
    val now by produceState(initialValue = Instant.now()) {
        while (true) { value = Instant.now(); delay(60_000) }
    }
    ProgressOverview(state, now, onNavigateToWorkoutHistory, onNavigateToYearlyCalendar,
        onNavigateToProfile, viewModel::loadData)
}

@Composable
internal fun ProgressOverview(
    state: ProgressState,
    now: Instant,
    onWorkoutHistory: (LocalDate) -> Unit,
    onYearlyCalendar: (YearMonth) -> Unit,
    onProfile: () -> Unit,
    onRetry: () -> Unit
) {
    val colors = IMFITColors.training
    val today = now.atZone(ZoneId.systemDefault()).toLocalDate()
    val month = YearMonth.from(today)
    var selectedText by rememberSaveable(month.toString()) { mutableStateOf<String?>(null) }
    val selectedDate = selectedText?.let(LocalDate::parse) ?: today
    val logs = state.workoutLogsByDate.values.flatten().distinctBy { it.id }
    val metrics = progressMetrics(logs, now, ZoneId.systemDefault(), month)
    Column(Modifier.fillMaxSize().background(colors.background)) {
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(start = IMFITSpacing.screenHorizontal, top = IMFITSpacing.sm,
                end = IMFITSpacing.screenHorizontal, bottom = IMFITSpacing.screenVertical),
            verticalArrangement = Arrangement.spacedBy(IMFITSpacing.xl)
        ) {
            item(key = "profile") {
                if (state.isLoading && state.userName.isBlank()) ShimmerWelcomeSection()
                else ProgressProfileRow(state, onProfile)
            }
            when {
                state.isLoading -> {
                    item(key = "loading_metrics") { ShimmerStatCard() }
                    item(key = "loading_calendar") { ShimmerStatCard() }
                }
                state.hasError -> item(key = "load_error") {
                    Column(verticalArrangement = Arrangement.spacedBy(IMFITSpacing.sm)) {
                        Text(stringResource(R.string.progress_overview_load_error), color = colors.text,
                            style = IMFITTypography.bodyMedium)
                        TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = IMFITSizes.buttonHeight)) {
                            Text(stringResource(R.string.redesign_retry))
                        }
                    }
                }
                else -> {
                    item(key = "metrics") { ProgressMetricOverview(metrics) }
                    item(key = "calendar") {
                        ProgressMonthCalendar(month, today, selectedDate, state.workoutLogsByDate,
                            onSelect = { date ->
                                selectedText = date.toString()
                                if (state.workoutLogsByDate[date].orEmpty().isNotEmpty()) onWorkoutHistory(date)
                            },
                            onYearlyCalendar = { onYearlyCalendar(month) })
                    }
                    item(key = "selected_day") {
                        ProgressSelectedDay(selectedDate, state.workoutLogsByDate[selectedDate].orEmpty()) {
                            onWorkoutHistory(selectedDate)
                        }
                    }
                }
            }
        }
    }
}

package com.diajarkoding.imfit.presentation.ui.progress

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.domain.model.Exercise
import com.diajarkoding.imfit.domain.model.ExerciseLog
import com.diajarkoding.imfit.domain.model.MuscleCategory
import com.diajarkoding.imfit.domain.model.WorkoutLog
import com.diajarkoding.imfit.domain.model.WorkoutSet
import com.diajarkoding.imfit.presentation.components.common.RoutineAppBar
import com.diajarkoding.imfit.theme.IMFITColors
import com.diajarkoding.imfit.theme.IMFITSpacing
import com.diajarkoding.imfit.theme.IMFITTheme
import java.time.LocalDate
import java.util.Locale
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import com.diajarkoding.imfit.presentation.components.common.*

@Composable
fun WorkoutHistoryDetailScreen(
    date: String,
    onNavigateBack: () -> Unit,
    viewModel: RemoteHistoryViewModel = hiltViewModel()
) {
    val state by viewModel.page.collectAsStateWithLifecycle()
    val totals by viewModel.totals.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val localDate = runCatching { LocalDate.parse(date) }.getOrDefault(LocalDate.now())

    LaunchedEffect(localDate) {
        viewModel.initialize(localDate)
    }

    val list = rememberLazyListState()
    RemotePagingViewport(list, state.items.associate { it.id to it.id }, viewModel::access,
        enabled = !state.refreshing && !state.stale)
    Scaffold(topBar = { RoutineAppBar(stringResource(R.string.workout_history_app_bar_title), onNavigateBack) }) { padding ->
        RemoteRefreshBox(state.refreshing, viewModel::refresh, Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(state = list, modifier = Modifier.fillMaxSize().testTag("workout_history_list"),
                contentPadding = PaddingValues(IMFITSpacing.screenHorizontal), verticalArrangement = Arrangement.spacedBy(IMFITSpacing.md)) {
                item("date") { WorkoutHistoryDateHeader(localDate, Locale.getDefault()) }
                if (totals.data != null) item("totals") { RemotePeriodTotals(totals.data!!) }
                if (totals.error != null) item("totals_error") { RemoteReadFailure(totals.stale, viewModel::refresh) }
                if (state.refreshing && !state.hasLoaded) item("loading") { WorkoutHistoryLoadingSkeleton() }
                if (state.refreshError != null) item("error") { RemoteReadFailure(state.stale, viewModel::refresh) }
                if (state.hasLoaded && state.items.isEmpty()) item("empty") { WorkoutHistoryEmptyState() }
                itemsIndexed(state.items, key = { _, row -> row.id }) { _, row ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(IMFITSpacing.cardPadding), verticalArrangement = Arrangement.spacedBy(IMFITSpacing.sm)) {
                            Text(row.templateName, style = MaterialTheme.typography.titleMedium)
                            Text(java.time.OffsetDateTime.parse(row.startTime).atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalTime().toString())
                            Text(stringResource(R.string.remote_read_session_totals, row.totalVolume, row.durationSeconds / 60, row.totalSets))
                            TextButton({ viewModel.open(row.id) }) { Text(stringResource(R.string.workout_day_card_view_details)) }
                            if (selected.key == row.id) {
                                if (selected.loading) CircularProgressIndicator()
                                if (selected.error != null) RemoteReadFailure(selected.stale) { viewModel.open(row.id, true) }
                                selected.data?.exerciseLogs?.forEach { exercise ->
                                    Text(exercise.exerciseName, style = MaterialTheme.typography.titleSmall)
                                    exercise.sets.forEach { set -> Text(stringResource(R.string.remote_read_set, set.setNumber, set.weight, set.reps)) }
                                }
                            }
                        }
                    }
                }
                item("footer") { RemotePageFooter(state.appending, state.appendError != null, state.endReached, viewModel::retry, viewModel::more) }
            }
        }
    }
}

@Composable
internal fun RemotePeriodTotals(summary: com.diajarkoding.imfit.data.remote.online.ProgressSummary) {
    Column {
        Text(stringResource(R.string.remote_read_workouts, summary.workoutCount), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.remote_read_session_totals, summary.totalVolume, summary.durationSeconds / 60, summary.totalSets))
    }
}

/** Stateless renderer shared by route, previews and synthetic UI tests. */
@Composable
internal fun WorkoutHistoryDetailScreenContent(
    localDate: LocalDate,
    state: ProgressState,
    onNavigateBack: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = IMFITColors.training
    val locale = LocalConfiguration.current.locales[0] ?: Locale.ROOT
    val workouts = state.workoutLogsByDate[localDate] ?: emptyList()
    val presentation = workoutHistoryPresentation(
        workoutsForDate = workouts,
        isLoading = state.isLoading,
        hasError = state.hasError
    )

    Scaffold(
        modifier = modifier,
        containerColor = colors.background,
        contentWindowInsets = WindowInsets.navigationBars,
        topBar = {
            RoutineAppBar(
                title = stringResource(R.string.workout_history_app_bar_title),
                onBack = onNavigateBack
            )
        }
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
        ) {
            val stacked = maxWidth <= 340.dp || LocalDensity.current.fontScale >= 1.25f

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("workout_history_list"),
                contentPadding = PaddingValues(
                    start = IMFITSpacing.screenHorizontal,
                    end = IMFITSpacing.screenHorizontal,
                    top = IMFITSpacing.headerContentGap,
                    bottom = IMFITSpacing.xxxl
                ),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                item(key = "date_header") {
                    WorkoutHistoryDateHeader(localDate = localDate, locale = locale)
                }

                when (presentation) {
                    is WorkoutHistoryPresentation.Loading -> {
                        item(key = "loading") {
                            WorkoutHistoryLoadingSkeleton()
                        }
                    }

                    is WorkoutHistoryPresentation.Empty -> {
                        item(key = "empty") {
                            WorkoutHistoryEmptyState()
                        }
                    }

                    is WorkoutHistoryPresentation.FullError -> {
                        item(key = "error") {
                            WorkoutHistoryErrorState(onRetry = onRetry)
                        }
                    }

                    is WorkoutHistoryPresentation.Content -> {
                        if (presentation.showUpdateFailureBanner) {
                            item(key = "update_banner") {
                                WorkoutHistoryUpdateFailureBanner(onRetry = onRetry)
                            }
                        }

                        item(key = "daily_totals") {
                            val totals = calculateWorkoutHistoryTotals(presentation.workouts)
                            WorkoutHistoryDailyTotals(
                                totals = totals,
                                locale = locale,
                                stacked = stacked
                            )
                        }

                        item(key = "sessions_title") {
                            Text(
                                text = stringResource(R.string.workout_history_sessions_section_title),
                                color = colors.text,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        itemsIndexed(
                            items = presentation.workouts,
                            key = { index, workout -> workout.id.ifBlank { "workout_$index" } }
                        ) { index, workoutLog ->
                            WorkoutHistorySessionCard(
                                workoutLog = workoutLog,
                                workoutNumber = index + 1,
                                totalWorkouts = presentation.workouts.size,
                                locale = locale,
                                stacked = stacked
                            )
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun WorkoutHistoryDetailScreenLightPreview() {
    IMFITTheme(darkTheme = false) {
        WorkoutHistoryDetailScreenContent(
            localDate = LocalDate.of(2026, 10, 16),
            state = ProgressState(
                isLoading = false,
                hasError = false,
                workoutLogsByDate = mapOf(
                    LocalDate.of(2026, 10, 16) to listOf(
                        WorkoutLog(
                            id = "1",
                            userId = "user",
                            templateName = "Upper Body",
                            date = 1792137600000L,
                            startTime = 1792137600000L,
                            endTime = 1792140300000L,
                            totalVolume = 3600f,
                            exerciseLogs = listOf(
                                ExerciseLog(
                                    exercise = Exercise("1", "Barbell Bench Press", MuscleCategory.CHEST, ""),
                                    sets = listOf(
                                        WorkoutSet(1, 100f, 6, true),
                                        WorkoutSet(2, 100f, 6, true),
                                        WorkoutSet(3, 100f, 6, true)
                                    )
                                )
                            )
                        )
                    )
                )
            ),
            onNavigateBack = {},
            onRetry = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun WorkoutHistoryDetailScreenDarkPreview() {
    IMFITTheme(darkTheme = true) {
        WorkoutHistoryDetailScreenContent(
            localDate = LocalDate.of(2026, 10, 16),
            state = ProgressState(
                isLoading = false,
                hasError = false,
                workoutLogsByDate = mapOf(
                    LocalDate.of(2026, 10, 16) to listOf(
                        WorkoutLog(
                            id = "1",
                            userId = "user",
                            templateName = "Upper Body",
                            date = 1792137600000L,
                            startTime = 1792137600000L,
                            endTime = 1792140300000L,
                            totalVolume = 3600f,
                            exerciseLogs = emptyList()
                        )
                    )
                )
            ),
            onNavigateBack = {},
            onRetry = {}
        )
    }
}

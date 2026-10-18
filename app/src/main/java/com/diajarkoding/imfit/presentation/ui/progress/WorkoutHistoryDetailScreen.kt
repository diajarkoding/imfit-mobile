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

@Composable
fun WorkoutHistoryDetailScreen(
    date: String,
    onNavigateBack: () -> Unit,
    viewModel: ProgressViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val localDate = runCatching { LocalDate.parse(date) }.getOrDefault(LocalDate.now())

    LaunchedEffect(Unit) {
        viewModel.loadData()
    }

    WorkoutHistoryDetailScreenContent(
        localDate = localDate,
        state = state,
        onNavigateBack = onNavigateBack,
        onRetry = viewModel::loadData
    )
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

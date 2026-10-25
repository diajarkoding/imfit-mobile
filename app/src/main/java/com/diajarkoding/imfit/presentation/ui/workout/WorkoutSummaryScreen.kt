package com.diajarkoding.imfit.presentation.ui.workout

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
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
import com.diajarkoding.imfit.theme.IMFITColors
import com.diajarkoding.imfit.theme.IMFITSpacing
import com.diajarkoding.imfit.theme.IMFITTheme
import java.util.Locale

@Composable
fun WorkoutSummaryScreen(
    workoutLogId: String,
    onNavigateToHome: () -> Unit,
    viewModel: WorkoutSummaryViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(workoutLogId) {
        viewModel.loadWorkoutLog(workoutLogId)
    }

    val presentation = workoutSummaryPresentation(
        log = state.workoutLog,
        isLoading = state.isLoading
    )

    WorkoutSummaryScreenContent(
        presentation = presentation,
        onNavigateToHome = onNavigateToHome
    )
}

@Composable
internal fun WorkoutSummaryScreenContent(
    presentation: WorkoutSummaryPresentation,
    onNavigateToHome: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = IMFITColors.training
    val configuration = LocalConfiguration.current
    val locale = configuration.locales[0] ?: Locale.US
    val fontScale = configuration.fontScale
    val screenWidthDp = configuration.screenWidthDp.dp
    val stacked = screenWidthDp <= 340.dp || fontScale >= 1.25f

    Scaffold(
        containerColor = colors.background,
        modifier = modifier
            .fillMaxSize()
            .testTag("workout_summary_screen"),
        topBar = {
            WorkoutSummaryTopBar()
        },
        bottomBar = {
            if (presentation is WorkoutSummaryPresentation.Content) {
                WorkoutSummaryFooter(onDoneClick = onNavigateToHome)
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .padding(padding)
                .padding(top = IMFITSpacing.headerContentGap)
        ) {
            when (presentation) {
                is WorkoutSummaryPresentation.Loading -> {
                    WorkoutSummaryLoadingState(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = IMFITSpacing.screenHorizontal)
                    )
                }

                is WorkoutSummaryPresentation.Unavailable -> {
                    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(horizontal = IMFITSpacing.screenHorizontal), contentAlignment = Alignment.TopCenter) {
                    WorkoutSummaryUnavailableState(
                        onNavigateToHome = onNavigateToHome,
                        modifier = Modifier.fillMaxWidth()
                    )
                    }
                }

                is WorkoutSummaryPresentation.Content -> {
                    val log = presentation.log
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().testTag("workout_summary_list"),
                        contentPadding = PaddingValues(
                            start = IMFITSpacing.screenHorizontal,
                            end = IMFITSpacing.screenHorizontal,
                            top = 0.dp,
                            bottom = 24.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // 1. Saved Outcome Banner
                        item(key = "outcome") {
                            WorkoutSummaryOutcome(templateName = log.templateName)
                        }

                        // 2. Metrics (Duration and Volume)
                        item(key = "metrics") {
                            WorkoutSummaryMetrics(
                                formattedDuration = formatSummaryDuration(log.durationMinutes, locale),
                                formattedVolume = formatSummaryVolume(log.totalVolume, locale),
                                stacked = stacked
                            )
                        }

                        // 3. Exercise Breakdown Sub-header
                        item(key = "breakdown_title") {
                            Text(
                                text = stringResource(R.string.summary_exercise_breakdown_title),
                                color = colors.text,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }

                        // 4. Exercise Breakdown Cards
                        items(
                            items = log.exerciseLogs,
                            key = { it.exercise.id }
                        ) { exerciseLog ->
                            WorkoutSummaryExerciseCard(
                                exerciseLog = exerciseLog,
                                locale = locale
                            )
                        }

                        item(key = "bottom_spacer") {
                            Spacer(Modifier.height(16.dp))
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun WorkoutSummaryScreenPreview() {
    val sampleExercises = listOf(
        ExerciseLog(
            exercise = Exercise("1", "Barbell Bench Press", MuscleCategory.CHEST, ""),
            sets = listOf(
                WorkoutSet(1, 60f, 10, isCompleted = true),
                WorkoutSet(2, 60f, 10, isCompleted = true),
                WorkoutSet(3, 60f, 10, isCompleted = true)
            )
        ),
        ExerciseLog(
            exercise = Exercise("2", "Dumbbell Shoulder Press", MuscleCategory.SHOULDERS, ""),
            sets = listOf(
                WorkoutSet(1, 20f, 12, isCompleted = true),
                WorkoutSet(2, 20f, 12, isCompleted = true),
                WorkoutSet(3, 20f, 12, isCompleted = true)
            )
        )
    )
    val log = WorkoutLog(
        id = "log_preview",
        userId = "usr_1",
        templateName = "Upper Body",
        date = System.currentTimeMillis(),
        startTime = System.currentTimeMillis() - 38 * 60 * 1000L,
        endTime = System.currentTimeMillis(),
        totalVolume = 2520f,
        exerciseLogs = sampleExercises
    )

    IMFITTheme(darkTheme = false) {
        WorkoutSummaryScreenContent(
            presentation = WorkoutSummaryPresentation.Content(log),
            onNavigateToHome = {}
        )
    }
}

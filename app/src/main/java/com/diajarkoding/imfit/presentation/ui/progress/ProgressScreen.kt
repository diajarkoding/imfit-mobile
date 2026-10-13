package com.diajarkoding.imfit.presentation.ui.progress

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.presentation.components.common.*
import com.diajarkoding.imfit.presentation.ui.home.redesignDate
import com.diajarkoding.imfit.theme.*
import java.text.NumberFormat
import java.time.*
import java.time.format.TextStyle
import kotlinx.coroutines.delay

@Composable
fun ProgressScreen(
    onNavigateToWorkoutHistory: (LocalDate) -> Unit = {},
    onNavigateToYearlyCalendar: () -> Unit = {},
    onNavigateToProfile: () -> Unit = {},
    refreshKey: Long? = null,
    viewModel: ProgressViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(refreshKey) { viewModel.loadData() }
    val logs = state.workoutLogsByDate.values.flatten().sortedByDescending { it.startTime }
    val zone = ZoneId.systemDefault()
    val now by produceState(initialValue = Instant.now()) {
        while (true) { value = Instant.now(); delay(60_000) }
    }
    val metrics = progressMetrics(logs, now, zone)
    val locale = LocalConfiguration.current.locales[0]
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(IMFITSpacing.screenHorizontal), verticalArrangement = Arrangement.spacedBy(IMFITSpacing.lg)) {
        item { ImfitPageHeader(stringResource(R.string.redesign_progress), onNavigateToProfile, profilePhoto = state.userProfilePhotoUri) }
        if (state.isLoading) {
            item { ShimmerStatCard() }
        } else {
            item { ImfitHeroSummaryCard(ImfitHeroKind.PROGRESS, stringResource(R.string.redesign_month_summary),
                stringResource(R.string.redesign_month_count, metrics.monthlyWorkouts),
                if (metrics.monthlyWorkouts == 0) stringResource(R.string.redesign_start_hint) else YearMonth.now().month.getDisplayName(TextStyle.FULL, locale)) }
            item {
                ImfitStatGrid(listOf(
                    stringResource(R.string.redesign_kg, NumberFormat.getNumberInstance(locale).format(metrics.totalVolume)) to stringResource(R.string.redesign_volume),
                    stringResource(R.string.redesign_hours_minutes, metrics.weeklyMinutes / 60, metrics.weeklyMinutes % 60) to stringResource(R.string.redesign_week_time),
                    metrics.monthlyWorkouts.toString() to stringResource(R.string.redesign_month_workouts),
                    metrics.monthlyCompletedSets.toString() to stringResource(R.string.redesign_month_sets)),
                    listOf(Symbols.Default.FitnessCenter, Symbols.Default.Schedule, Symbols.Default.FitnessCenter, Symbols.Default.CheckCircle))
            }
            item { ImfitMonthCalendar(state.workoutLogsByDate.mapValues { it.value.size }, onNavigateToWorkoutHistory) }
            item { ImfitSectionHeader(stringResource(R.string.redesign_history), stringResource(R.string.redesign_calendar), onNavigateToYearlyCalendar) }
            if (logs.isEmpty()) item { ImfitEmptyMessage(stringResource(R.string.redesign_no_workout)) }
            items(logs.take(5), key = { it.id }) { log ->
                ImfitWorkoutRow(log.templateName, redesignDate(log.startTime) + "\n" + log.formattedDuration + " · " + log.formattedVolume + " · " + stringResource(R.string.redesign_exercise_count, log.exerciseLogs.size),
                    log.exerciseLogs.firstOrNull()?.exercise?.muscleCategory) {
                    onNavigateToWorkoutHistory(Instant.ofEpochMilli(log.startTime).atZone(zone).toLocalDate())
                }
            }
        }
        item { Spacer(Modifier.height(IMFITSpacing.lg)) }
    }
}

@Composable
private fun ImfitMonthCalendar(workouts: Map<LocalDate, Int>, onDate: (LocalDate) -> Unit) {
    var monthText by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    var selectedText by rememberSaveable { mutableStateOf<String?>(null) }
    val month = YearMonth.parse(monthText)
    val today = LocalDate.now()
    val locale = LocalConfiguration.current.locales[0]
    Card(shape = IMFITShapes.Card, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(2.dp)) {
        Column(Modifier.fillMaxWidth().padding(IMFITSpacing.sm)) {
            Text(month.month.getDisplayName(TextStyle.FULL, locale) + " " + month.year, style = MaterialTheme.typography.titleLarge)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { monthText = month.minusMonths(1).toString() }) { Icon(Symbols.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.redesign_previous)) }
                TextButton(onClick = { monthText = YearMonth.from(today).toString(); selectedText = today.toString() }) { Text(stringResource(R.string.redesign_today)) }
                IconButton(onClick = { monthText = month.plusMonths(1).toString() }) { Icon(Symbols.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.redesign_next)) }
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
            val gridWidth = maxOf(maxWidth, 336.dp)
            Column(Modifier.horizontalScroll(rememberScrollState())) {
            Column(Modifier.width(gridWidth)) {
            Row {
                DayOfWeek.entries.forEach { day -> Text(day.getDisplayName(TextStyle.SHORT, locale), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center) }
            }
            monthDays(month).chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth()) {
                    week.forEach { day ->
                        if (day == null) Spacer(Modifier.weight(1f).height(48.dp)) else {
                            val date = month.atDay(day)
                            val count = workouts[date] ?: 0
                            val isSelected = date.toString() == selectedText
                            val description = stringResource(R.string.redesign_day_description, date.toString(), count, if (date == today) stringResource(R.string.redesign_today) else "")
                            val color = when { isSelected -> MaterialTheme.colorScheme.primaryContainer; count > 0 -> MaterialTheme.colorScheme.primary; else -> Color.Transparent }
                            Box(Modifier.weight(1f).heightIn(min = 48.dp)
                                .background(color, CircleShape)
                                .then(if (date == today) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier)
                                .clickable { selectedText = date.toString(); if (count > 0) onDate(date) }
                                .semantics { contentDescription = description; selected = isSelected }, contentAlignment = Alignment.Center) {
                                Text(day.toString(), color = when { isSelected -> MaterialTheme.colorScheme.onPrimaryContainer; count > 0 -> MaterialTheme.colorScheme.onPrimary; else -> MaterialTheme.colorScheme.onSurface })
                            }
                        }
                    }
                }
            }
            }
            }
            }
        }
    }
}

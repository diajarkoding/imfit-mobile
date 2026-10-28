package com.diajarkoding.imfit.presentation.ui.progress

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.presentation.components.common.*
import com.diajarkoding.imfit.theme.*
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YearlyCalendarScreen(
    onNavigateBack: () -> Unit,
    onDateSelected: (LocalDate) -> Unit,
    initialYear: Int = LocalDate.now().year,
    initialMonth: Int = LocalDate.now().monthValue,
    viewModel: ProgressViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val today by produceState(LocalDate.now()) {
        while (true) { value = LocalDate.now(); delay(60_000) }
    }
    val colors = IMFITColors.training
    var year by rememberSaveable { mutableIntStateOf(initialYear.coerceIn(1, today.year)) }
    LaunchedEffect(year) { viewModel.selectYear(year) }
    var selectedText by rememberSaveable { mutableStateOf(today.toString()) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialMonth.coerceIn(1, 12) - 1)
    val months = remember(year) { (1..12).map { YearMonth.of(year, it) } }
    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                modifier = Modifier.testTag("screen_header"),
                title = {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        IconButton(onClick = { year-- }, enabled = year > 1) {
                            Icon(Symbols.AutoMirrored.Filled.KeyboardArrowLeft, stringResource(R.string.calendar_previous_year))
                        }
                        Text(year.toString(), style = IMFITTypography.titleLarge)
                        IconButton(onClick = { year++ }, enabled = year < today.year) {
                            Icon(Symbols.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.calendar_next_year))
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = onNavigateBack) {
                    Icon(Symbols.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background, titleContentColor = colors.text)
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).background(colors.background)
            .padding(top = IMFITSpacing.headerContentGap).testTag("year_calendar_content")) {
            if (state.isLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.hasError) {
                Text(stringResource(R.string.progress_overview_load_error), Modifier.padding(horizontal = IMFITSpacing.lg).padding(bottom = IMFITSpacing.lg), color = colors.text)
                TextButton(onClick = viewModel::loadData) { Text(stringResource(R.string.redesign_retry)) }
            }
            RemoteRefreshBox(state.refreshing, viewModel::loadData, Modifier.fillMaxWidth().weight(1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = IMFITSpacing.screenHorizontal, end = IMFITSpacing.screenHorizontal, bottom = IMFITSpacing.screenHorizontal),
                verticalArrangement = Arrangement.spacedBy(IMFITSpacing.xl)
            ) {
                if (state.markersLoaded) items(months, key = { it.toString() }) { month ->
                    ProgressMonthCalendar(month, today, LocalDate.parse(selectedText), state.workoutLogsByDate,
                        onSelect = { date ->
                            selectedText = date.toString()
                             if ((state.markerCounts[date] ?: 0) > 0) onDateSelected(date)
                         }, enabled = !state.isLoading, markerCounts = state.markerCounts)
                }
            }
            }
        }
    }
}

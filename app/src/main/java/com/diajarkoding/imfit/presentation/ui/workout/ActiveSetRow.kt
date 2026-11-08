package com.diajarkoding.imfit.presentation.ui.workout

import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.diajarkoding.imfit.core.timer.AndroidMonotonicClock
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.domain.model.CardioMetric
import com.diajarkoding.imfit.domain.model.ExerciseModality
import com.diajarkoding.imfit.domain.model.SetMeasurement
import com.diajarkoding.imfit.domain.model.TrackingProfile
import com.diajarkoding.imfit.domain.model.TrackingType
import com.diajarkoding.imfit.domain.model.WorkoutSet
import com.diajarkoding.imfit.domain.model.isTwoDecimalIn
import com.diajarkoding.imfit.presentation.components.common.Delete
import com.diajarkoding.imfit.presentation.components.common.Symbols
import com.diajarkoding.imfit.presentation.components.common.Pause
import com.diajarkoding.imfit.presentation.components.common.PlayArrow
import com.diajarkoding.imfit.theme.IMFITColors
import kotlinx.coroutines.delay
import kotlin.math.round

private val canonicalCardioMetrics = listOf(
    CardioMetric.CALORIES,
    CardioMetric.DISTANCE,
    CardioMetric.SPEED,
    CardioMetric.LAPS,
    CardioMetric.INCLINE
)

@Composable
internal fun ActiveSetRow(
    set: WorkoutSet,
    exerciseName: String,
    mutationEnabled: Boolean,
    canDelete: Boolean,
    isPaused: Boolean,
    onComplete: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    trackingType: TrackingType = TrackingType.WEIGHT_REPS,
    onMeasurementUpdate: (SetMeasurement) -> Unit,
    onStopwatchToggle: () -> Unit,
    modality: ExerciseModality = ExerciseModality.STRENGTH,
    trackingProfile: TrackingProfile? = null,
    supportedMetrics: List<CardioMetric> = emptyList(),
    restTargetSeconds: Int? = null,
    onUndo: () -> Unit = {}
) {
    val colors = IMFITColors.training
    val configuration = LocalConfiguration.current
    val compact = configuration.screenWidthDp <= 340 || configuration.fontScale >= 1.25f
    val locale = configuration.locales[0]
    val editable = mutationEnabled && !set.isCompleted
    val isV3 = set.measurementVersion == 3
    val rowKey = if (isV3) set.id else "${exerciseName}_${set.setNumber}"

    var weightText by rememberSaveable(rowKey, locale) {
        mutableStateOf(
            if (isV3) set.weightKg?.let { formatSetWeight(it, locale) } ?: ""
            else set.weightKg?.takeIf { it > 0.0 }?.let { formatSetWeight(it, locale) } ?: ""
        )
    }
    var lastPublishedWeight by rememberSaveable(rowKey) { mutableStateOf(set.weightKg) }
    LaunchedEffect(set.weightKg, isV3) {
        if (!isV3) {
            weightText = set.weightKg?.takeIf { it > 0.0 }?.let { formatSetWeight(it, locale) } ?: ""
        } else if (set.weightKg != lastPublishedWeight) {
            weightText = set.weightKg?.let { formatSetWeight(it, locale) } ?: ""
            lastPublishedWeight = set.weightKg
        }
    }

    var repsText by rememberSaveable(rowKey) {
        mutableStateOf(
            if (isV3) set.reps?.toString() ?: ""
            else if ((set.reps ?: 0) > 0) set.reps.toString() else ""
        )
    }
    var lastPublishedReps by rememberSaveable(rowKey) { mutableStateOf(set.reps) }
    LaunchedEffect(set.reps, isV3) {
        if (!isV3) {
            repsText = if ((set.reps ?: 0) > 0) set.reps.toString() else ""
        } else if (set.reps != lastPublishedReps) {
            repsText = set.reps?.toString() ?: ""
            lastPublishedReps = set.reps
        }
    }

    var additionalWeightText by rememberSaveable(set.additionalWeight, locale) {
        mutableStateOf(if ((set.additionalWeight ?: 0f) > 0f) formatSetWeight((set.additionalWeight ?: 0f).toDouble(), locale) else "")
    }
    var assistanceWeightText by rememberSaveable(set.assistanceWeight, locale) {
        mutableStateOf(if ((set.assistanceWeight ?: 0f) > 0f) formatSetWeight((set.assistanceWeight ?: 0f).toDouble(), locale) else "")
    }
    var additionalWeightExpanded by rememberSaveable(exerciseName, set.setNumber) { mutableStateOf(false) }
    var durationText by rememberSaveable(rowKey) {
        mutableStateOf(
            set.durationSeconds?.takeIf { it > 0 }?.let { if (isV3) formatDurationHhMmSs(it) else it.toString() } ?: ""
        )
    }
    var lastPublishedDuration by rememberSaveable(rowKey) { mutableStateOf(set.durationSeconds) }
    LaunchedEffect(set.durationSeconds, isV3) {
        if (set.durationSeconds != lastPublishedDuration) {
            durationText = set.durationSeconds?.takeIf { it > 0 }?.let { if (isV3) formatDurationHhMmSs(it) else it.toString() } ?: ""
            lastPublishedDuration = set.durationSeconds
        }
    }
    var distanceText by rememberSaveable(set.distanceMeters) {
        mutableStateOf(set.distanceMeters?.takeIf { it > 0.0 }?.let { if (set.measurementVersion == 3) it.toString() else it.toInt().toString() } ?: "")
    }

    var caloriesText by rememberSaveable(rowKey, locale) {
        mutableStateOf(set.caloriesKcal?.let { formatSetWeight(it, locale) } ?: "")
    }
    var lastPublishedCalories by rememberSaveable(rowKey) { mutableStateOf(set.caloriesKcal) }
    LaunchedEffect(set.caloriesKcal) {
        if (set.caloriesKcal != lastPublishedCalories) {
            caloriesText = set.caloriesKcal?.let { formatSetWeight(it, locale) } ?: ""
            lastPublishedCalories = set.caloriesKcal
        }
    }

    var distanceKmText by rememberSaveable(rowKey, locale) {
        mutableStateOf(set.distanceMeters?.let { formatSetWeight(it / 1000.0, locale) } ?: "")
    }
    var lastPublishedDistanceMeters by rememberSaveable(rowKey) { mutableStateOf(set.distanceMeters) }
    LaunchedEffect(set.distanceMeters) {
        if (set.distanceMeters != lastPublishedDistanceMeters) {
            distanceKmText = set.distanceMeters?.let { formatSetWeight(it / 1000.0, locale) } ?: ""
            lastPublishedDistanceMeters = set.distanceMeters
        }
    }

    var speedText by rememberSaveable(rowKey, locale) {
        mutableStateOf(set.speedKph?.let { formatSetWeight(it, locale) } ?: "")
    }
    var lastPublishedSpeed by rememberSaveable(rowKey) { mutableStateOf(set.speedKph) }
    LaunchedEffect(set.speedKph) {
        if (set.speedKph != lastPublishedSpeed) {
            speedText = set.speedKph?.let { formatSetWeight(it, locale) } ?: ""
            lastPublishedSpeed = set.speedKph
        }
    }

    var lapsText by rememberSaveable(rowKey) {
        mutableStateOf(set.lapReps?.toString() ?: "")
    }
    var lastPublishedLaps by rememberSaveable(rowKey) { mutableStateOf(set.lapReps) }
    LaunchedEffect(set.lapReps) {
        if (set.lapReps != lastPublishedLaps) {
            lapsText = set.lapReps?.toString() ?: ""
            lastPublishedLaps = set.lapReps
        }
    }

    var inclineText by rememberSaveable(rowKey, locale) {
        mutableStateOf(set.inclinePercent?.let { formatSetWeight(it, locale) } ?: "")
    }
    var lastPublishedIncline by rememberSaveable(rowKey) { mutableStateOf(set.inclinePercent) }
    LaunchedEffect(set.inclinePercent) {
        if (set.inclinePercent != lastPublishedIncline) {
            inclineText = set.inclinePercent?.let { formatSetWeight(it, locale) } ?: ""
            lastPublishedIncline = set.inclinePercent
        }
    }

    val context = LocalContext.current
    val stopwatch = set.executionStopwatch
    val isStopwatchRunning = stopwatch?.isRunning == true
    val bootIdentity = remember(context, isStopwatchRunning) {
        if (isStopwatchRunning) AndroidMonotonicClock(context.applicationContext).bootIdentity() else null
    }
    var nowElapsedMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(stopwatch?.anchorElapsedMs, isPaused, set.isCompleted) {
        while (isStopwatchRunning && !isPaused && !set.isCompleted) {
            nowElapsedMs = SystemClock.elapsedRealtime()
            delay(200)
        }
    }
    val displayedSeconds = if (isStopwatchRunning && !isPaused && !set.isCompleted) {
        (stopwatch!!.elapsedMillis(nowElapsedMs, bootIdentity) / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    } else set.durationSeconds
    val displayedDuration = if (isStopwatchRunning || set.isCompleted) {
        displayedSeconds?.let { if (isV3) formatDurationHhMmSs(it) else it.toString() } ?: ""
    } else durationText
    val updateDuration: (String) -> Unit = { raw ->
        if (isV3) {
            if (raw.all { it.isDigit() || it == ':' } && raw.count { it == ':' } <= 2) {
                durationText = raw
                val seconds = parseRoutineDurationSeconds(raw)
                    ?: raw.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull()?.takeIf { it in 1..86400 }
                lastPublishedDuration = seconds
                onMeasurementUpdate(set.measurement.copy(durationSeconds = seconds))
            }
        } else if (raw.all { it.isDigit() || it == ':' } && raw.count { it == ':' } <= 1) {
            durationText = raw
            val seconds = parseSetDurationSeconds(raw) ?: 0
            lastPublishedDuration = seconds
            onMeasurementUpdate(set.measurement.copy(durationSeconds = seconds))
        }
    }

    val completeLabel = stringResource(R.string.active_session_complete_set, exerciseName, set.setNumber)
    val deleteLabel = stringResource(R.string.active_session_delete_set, exerciseName, set.setNumber)
    val undoLabel = stringResource(R.string.active_session_undo_set, exerciseName, set.setNumber)
    // v3: a completed result is reopened as a DRAFT (inputs kept) instead of being locked.
    val canUndo = isV3 && set.isCompleted && mutationEnabled && !isPaused

    val currentMeasurement = set.measurement.copy(durationSeconds = displayedSeconds)
    val isCompleteEligible = if (isV3) {
        currentMeasurement.isCompleteEligible(trackingType, modality, trackingProfile, supportedMetrics)
    } else {
        currentMeasurement.isCompleteEligible(trackingType)
    }

    val inputs: @Composable () -> Unit = {
        when (trackingType) {
            TrackingType.WEIGHT_REPS -> {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (compact) Text(stringResource(R.string.active_session_column_kg), color = colors.muted, style = MaterialTheme.typography.labelSmall)
                        SetNumberInput(weightText, { raw ->
                            if (raw.matches(Regex("\\d*([.,]\\d*)?"))) {
                                val value = raw.replace(',', '.').toDoubleOrNull() ?: 0.0
                                if (value.isFinite()) {
                                    weightText = raw
                                    onMeasurementUpdate(set.measurement.copy(weightKg = value))
                                }
                            }
                        }, stringResource(R.string.active_session_weight_input, exerciseName, set.setNumber), editable, set.isCompleted, KeyboardType.Decimal)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (compact) Text(stringResource(R.string.active_session_column_reps), color = colors.muted, style = MaterialTheme.typography.labelSmall)
                        SetNumberInput(repsText, { raw ->
                            if (raw.all(Char::isDigit) && (raw.isEmpty() || raw.toIntOrNull() != null)) {
                                repsText = raw
                                onMeasurementUpdate(set.measurement.copy(reps = raw.toIntOrNull() ?: 0))
                            }
                        }, stringResource(R.string.active_session_reps_input, exerciseName, set.setNumber), editable, set.isCompleted, KeyboardType.Number)
                    }
                }
            }

            TrackingType.BODYWEIGHT_REPS -> {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.active_session_column_reps), color = colors.muted, style = MaterialTheme.typography.labelSmall)
                        SetNumberInput(repsText, { raw ->
                            if (raw.all(Char::isDigit) && (raw.isEmpty() || raw.toIntOrNull() != null)) {
                                repsText = raw
                                onMeasurementUpdate(set.measurement.copy(reps = raw.toIntOrNull() ?: 0))
                            }
                        }, stringResource(R.string.active_session_reps_input, exerciseName, set.setNumber), editable, set.isCompleted, KeyboardType.Number)
                    }
                    if (additionalWeightExpanded || set.additionalWeight != null) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.active_set_additional_weight), color = colors.muted, style = MaterialTheme.typography.labelSmall)
                            SetNumberInput(additionalWeightText, { raw ->
                                if (raw.matches(Regex("\\d*([.,]\\d*)?"))) {
                                    val value = raw.replace(',', '.').toFloatOrNull()
                                    if (value == null || value.isFinite()) {
                                        additionalWeightText = raw
                                        onMeasurementUpdate(set.measurement.copy(additionalWeight = value))
                                    }
                                }
                            }, stringResource(R.string.active_set_additional_weight), editable, set.isCompleted, KeyboardType.Decimal)
                        }
                    } else if (editable) {
                        TextButton(onClick = { additionalWeightExpanded = true }, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.active_set_add_load))
                        }
                    }
                }
            }

            TrackingType.ASSISTED_REPS -> {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (compact) Text(stringResource(R.string.active_set_assistance_weight), color = colors.muted, style = MaterialTheme.typography.labelSmall)
                        SetNumberInput(assistanceWeightText, { raw ->
                            if (raw.matches(Regex("\\d*([.,]\\d*)?"))) {
                                val value = raw.replace(',', '.').toFloatOrNull() ?: 0f
                                if (value.isFinite()) {
                                    assistanceWeightText = raw
                                    onMeasurementUpdate(set.measurement.copy(assistanceWeight = value))
                                }
                            }
                        }, stringResource(R.string.active_set_assistance_weight), editable, set.isCompleted, KeyboardType.Decimal)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (compact) Text(stringResource(R.string.active_session_column_reps), color = colors.muted, style = MaterialTheme.typography.labelSmall)
                        SetNumberInput(repsText, { raw ->
                            if (raw.all(Char::isDigit) && (raw.isEmpty() || raw.toIntOrNull() != null)) {
                                repsText = raw
                                onMeasurementUpdate(set.measurement.copy(reps = raw.toIntOrNull() ?: 0))
                            }
                        }, stringResource(R.string.active_session_reps_input, exerciseName, set.setNumber), editable, set.isCompleted, KeyboardType.Number)
                    }
                }
            }

            TrackingType.REPS_ONLY -> {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (compact) Text(stringResource(R.string.active_session_column_reps), color = colors.muted, style = MaterialTheme.typography.labelSmall)
                    SetNumberInput(repsText, { raw ->
                        if (raw.all(Char::isDigit) && (raw.isEmpty() || raw.toIntOrNull() != null)) {
                            repsText = raw
                            onMeasurementUpdate(set.measurement.copy(reps = raw.toIntOrNull() ?: 0))
                        }
                    }, stringResource(R.string.active_session_reps_input, exerciseName, set.setNumber), editable, set.isCompleted, KeyboardType.Number)
                }
            }

            TrackingType.DURATION -> Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (editable && !isPaused) {
                        IconButton(
                            onClick = onStopwatchToggle,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                if (isStopwatchRunning) Symbols.Filled.Pause else Symbols.Filled.PlayArrow,
                                contentDescription = if (isStopwatchRunning) "Pause Stopwatch" else "Start Stopwatch",
                                tint = colors.primary
                            )
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (compact) Text("Durasi (detik)", color = colors.muted, style = MaterialTheme.typography.labelSmall)
                        SetNumberInput(displayedDuration, updateDuration,
                            "Durasi dalam detik", editable && !isStopwatchRunning, set.isCompleted, KeyboardType.Text)
                    }
                }
                // T40: only metrics the frozen exercise snapshot supports, in canonical order; all optional.
                val shownMetrics = if (isV3 && modality == ExerciseModality.CARDIO) canonicalCardioMetrics.filter { it in supportedMetrics } else emptyList()
                if (shownMetrics.isNotEmpty()) {
                    Text(stringResource(R.string.active_metrics_hint), color = colors.muted, style = MaterialTheme.typography.labelSmall)
                    shownMetrics.chunked(if (compact) 2 else 3).forEach { rowMetrics ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            rowMetrics.forEach { metric ->
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    when (metric) {
                                        CardioMetric.CALORIES -> MetricField(R.string.active_metric_calories, caloriesText, editable, set.isCompleted, "calories", KeyboardType.Decimal) { raw ->
                                            parseDecimalMetric(raw, 0.0, 9_999_999_999.99)?.let { (text, value) ->
                                                caloriesText = text; onMeasurementUpdate(set.measurement.copy(caloriesKcal = value))
                                            }
                                        }
                                        CardioMetric.DISTANCE -> MetricField(R.string.active_metric_distance, distanceKmText, editable, set.isCompleted, "distance", KeyboardType.Decimal) { raw ->
                                            parseDecimalMetric(raw, 0.0, 9_999_999.99)?.let { (text, km) ->
                                                distanceKmText = text
                                                onMeasurementUpdate(set.measurement.copy(distanceMeters = km?.let { round(it * 1000.0 * 100.0) / 100.0 }))
                                            }
                                        }
                                        CardioMetric.SPEED -> MetricField(R.string.active_metric_speed, speedText, editable, set.isCompleted, "speed", KeyboardType.Decimal) { raw ->
                                            parseDecimalMetric(raw, 0.0, 9_999_999_999.99)?.let { (text, value) ->
                                                speedText = text; onMeasurementUpdate(set.measurement.copy(speedKph = value))
                                            }
                                        }
                                        CardioMetric.LAPS -> MetricField(R.string.active_metric_laps, lapsText, editable, set.isCompleted, "laps", KeyboardType.Number) { raw ->
                                            if (raw.all(Char::isDigit) && (raw.isEmpty() || raw.toIntOrNull() != null)) {
                                                lapsText = raw; onMeasurementUpdate(set.measurement.copy(lapReps = raw.toIntOrNull()))
                                            }
                                        }
                                        CardioMetric.INCLINE -> MetricField(R.string.active_metric_incline, inclineText, editable, set.isCompleted, "incline", KeyboardType.Decimal) { raw ->
                                            parseDecimalMetric(raw, -999.99, 999.99, allowNegative = true)?.let { (text, value) ->
                                                inclineText = text; onMeasurementUpdate(set.measurement.copy(inclinePercent = value))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            TrackingType.DISTANCE_DURATION -> {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (compact) Text("Jarak (meter)", color = colors.muted, style = MaterialTheme.typography.labelSmall)
                        SetNumberInput(distanceText, { raw ->
                            if (raw.all(Char::isDigit) && (raw.isEmpty() || raw.toIntOrNull() != null)) {
                                distanceText = raw
                                onMeasurementUpdate(set.measurement.copy(distanceMeters = raw.toIntOrNull()?.takeIf { it > 0 }?.toDouble()))
                            }
                        }, "Jarak dalam meter", editable, set.isCompleted, KeyboardType.Number)
                    }
                    if (editable && !isPaused) {
                        IconButton(onClick = onStopwatchToggle, modifier = Modifier.size(40.dp)) {
                            Icon(if (isStopwatchRunning) Symbols.Filled.Pause else Symbols.Filled.PlayArrow,
                                contentDescription = if (isStopwatchRunning) "Pause Stopwatch" else "Start Stopwatch",
                                tint = colors.primary)
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (compact) Text("Durasi (detik)", color = colors.muted, style = MaterialTheme.typography.labelSmall)
                        SetNumberInput(displayedDuration, updateDuration,
                            "Durasi dalam detik", editable && !isStopwatchRunning, set.isCompleted, KeyboardType.Text)
                        set.paceSecondsPerKilometer?.let { pace ->
                            Text(stringResource(R.string.active_set_pace, formatPace(pace, locale)),
                                color = colors.muted, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }

    val actions: @Composable () -> Unit = {
        IconButton(onClick = onDelete, enabled = canDelete && mutationEnabled, modifier = Modifier.size(48.dp)) {
            Icon(Symbols.Filled.Delete, deleteLabel, tint = if (canDelete && mutationEnabled) colors.error else colors.muted.copy(alpha = 0.4f),
                modifier = Modifier.size(18.dp))
        }
        Checkbox(
            checked = set.isCompleted,
            onCheckedChange = { if (set.isCompleted) onUndo() else onComplete() },
            enabled = (editable && !isPaused && isCompleteEligible) || canUndo,
            modifier = Modifier.size(48.dp).semantics { contentDescription = if (canUndo) undoLabel else completeLabel },
            colors = CheckboxDefaults.colors(
                checkedColor = colors.success,
                checkmarkColor = colors.surface,
                disabledCheckedColor = colors.success,
                disabledUncheckedColor = colors.muted.copy(alpha = 0.4f)
            )
        )
    }

    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
        .background(if (set.isCompleted) colors.successContainer else Color.Transparent)
        .padding(vertical = 4.dp).testTag("active_set_${set.setNumber}")) {
        if (stopwatch?.recoveryRequired == true) {
            Text(stringResource(R.string.execution_stopwatch_review), color = colors.error, style = MaterialTheme.typography.bodySmall)
        }
        if (compact) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.active_workout_set_number, set.setNumber),
                    Modifier.weight(1f), color = if (set.isCompleted) colors.success else colors.muted,
                    style = MaterialTheme.typography.labelLarge)
                actions()
            }
            inputs()
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(set.setNumber.toString(), Modifier.width(28.dp),
                    textAlign = TextAlign.Center, color = if (set.isCompleted) colors.success else colors.muted,
                    style = MaterialTheme.typography.labelSmall)
                Box(Modifier.weight(1f).padding(start = 8.dp, end = 4.dp)) { inputs() }
                actions()
            }
        }
        // Read-only target rest from the frozen snapshot row; never an input and never the live countdown.
        if (isV3 && restTargetSeconds != null) {
            Text(stringResource(R.string.active_set_rest_target, restTargetSeconds),
                Modifier.padding(start = if (compact) 0.dp else 36.dp, top = 2.dp).testTag("active_set_rest_target_${set.setNumber}"),
                color = colors.muted, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** Raw text → (kept text, value). Empty clears (null); malformed/out-of-range input is rejected without publishing. */
private fun parseDecimalMetric(raw: String, min: Double, max: Double, allowNegative: Boolean = false): Pair<String, Double?>? {
    val pattern = if (allowNegative) "-?\\d*([.,]\\d{0,2})?" else "\\d*([.,]\\d{0,2})?"
    if (!raw.matches(Regex(pattern))) return null
    if (raw.isEmpty()) return raw to null
    val value = raw.replace(',', '.').toDoubleOrNull() ?: return raw to null // "-" or "1." stay as typed, nothing published
    return if (value.isFinite() && value >= min && value <= max) raw to value else null
}

@Composable
private fun MetricField(
    labelRes: Int,
    value: String,
    editable: Boolean,
    completed: Boolean,
    tag: String,
    keyboardType: KeyboardType,
    onChange: (String) -> Unit
) {
    val label = stringResource(labelRes)
    Column(Modifier.testTag("metric_$tag"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = IMFITColors.training.muted, style = MaterialTheme.typography.labelSmall)
        SetNumberInput(value, onChange, label, editable, completed, keyboardType)
    }
}

@Composable
private fun SetNumberInput(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    completed: Boolean,
    keyboardType: KeyboardType
) {
    val colors = IMFITColors.training
    BasicTextField(
        value = value,
        onValueChange = onChange,
        enabled = enabled,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.text,
            textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
        cursorBrush = SolidColor(colors.primary),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = label },
        decorationBox = { innerField ->
            Box(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .background(if (completed) colors.successContainer else colors.raised, RoundedCornerShape(8.dp))
                .border(BorderStroke(1.dp, if (completed) colors.success.copy(alpha = 0.35f) else colors.border), RoundedCornerShape(8.dp))
                .padding(horizontal = 6.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                if (value.isEmpty()) Text("0", color = colors.muted, style = MaterialTheme.typography.bodyLarge)
                innerField()
            }
        }
    )
}

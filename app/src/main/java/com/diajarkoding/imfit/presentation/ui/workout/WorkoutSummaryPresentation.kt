package com.diajarkoding.imfit.presentation.ui.workout

import com.diajarkoding.imfit.domain.model.WorkoutLog
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

sealed interface WorkoutSummaryPresentation {
    data object Loading : WorkoutSummaryPresentation
    data object Unavailable : WorkoutSummaryPresentation
    data class Content(val log: WorkoutLog) : WorkoutSummaryPresentation
}

internal fun workoutSummaryPresentation(
    log: WorkoutLog?,
    isLoading: Boolean
): WorkoutSummaryPresentation {
    return when {
        isLoading -> WorkoutSummaryPresentation.Loading
        log != null -> WorkoutSummaryPresentation.Content(log)
        else -> WorkoutSummaryPresentation.Unavailable
    }
}

internal fun formatSummaryDuration(durationMinutes: Int, locale: Locale): String {
    val isIndonesian = locale.language.equals("in", ignoreCase = true) || locale.language.equals("id", ignoreCase = true)
    val hours = durationMinutes / 60
    val minutes = durationMinutes % 60
    return if (isIndonesian) {
        if (hours > 0 && minutes > 0) {
            "$hours jam $minutes mnt"
        } else if (hours > 0) {
            "$hours jam"
        } else {
            "$minutes menit"
        }
    } else {
        if (hours > 0 && minutes > 0) {
            "${hours}h ${minutes}m"
        } else if (hours > 0) {
            "${hours}h"
        } else {
            "${minutes}m"
        }
    }
}

internal fun formatSummaryVolume(volume: Float, locale: Locale): String {
    val formatter = NumberFormat.getIntegerInstance(locale).apply {
        isGroupingUsed = true
    }
    return "${formatter.format(volume.roundToInt())} kg"
}

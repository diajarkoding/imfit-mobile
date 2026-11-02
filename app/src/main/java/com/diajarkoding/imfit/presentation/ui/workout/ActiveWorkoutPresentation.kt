package com.diajarkoding.imfit.presentation.ui.workout

import androidx.annotation.StringRes
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.domain.model.ExerciseLog
import com.diajarkoding.imfit.domain.model.ExerciseModality
import com.diajarkoding.imfit.domain.model.TrackingType
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToLong

data class CancelDialogMessageSpec(
    @param:StringRes val stringRes: Int,
    val formatArgs: List<Any> = emptyList()
)

internal fun resolveCancelDialogMessage(
    completedSets: Int,
    totalSets: Int
): CancelDialogMessageSpec {
    return when {
        completedSets <= 0 -> CancelDialogMessageSpec(R.string.active_session_cancel_message_zero)
        completedSets >= totalSets && totalSets > 0 -> CancelDialogMessageSpec(
            R.string.active_session_cancel_message_all,
            listOf(totalSets)
        )
        completedSets == 1 -> CancelDialogMessageSpec(
            R.string.active_session_cancel_message_one,
            listOf(totalSets)
        )
        else -> CancelDialogMessageSpec(
            R.string.active_session_cancel_message_some,
            listOf(completedSets, totalSets)
        )
    }
}

internal fun formatElapsedTime(seconds: Long): String {
    val nonNegative = seconds.coerceAtLeast(0)
    val hh = nonNegative / 3600
    val mm = (nonNegative % 3600) / 60
    val ss = nonNegative % 60
    return String.format(Locale.US, "%02d:%02d:%02d", hh, mm, ss)
}


internal fun restCountdownProgress(remainingSeconds: Int, totalSeconds: Float): Float =
    if (totalSeconds > 0f) (remainingSeconds / totalSeconds).coerceIn(0f, 1f) else 0f

internal fun formatSetWeight(weight: Float, locale: Locale): String =
    NumberFormat.getNumberInstance(locale).apply {
        isGroupingUsed = false
        maximumFractionDigits = 3
    }.format(weight)

internal fun formatPace(secondsPerKilometer: Double, locale: Locale): String {
    val seconds = secondsPerKilometer.roundToLong()
    return String.format(locale, "%d:%02d", seconds / 60, seconds % 60)
}

internal data class WorkoutMeasurements(
    val externalVolume: Double,
    val additionalVolume: Double,
    val repetitions: Int,
    val holdSeconds: Int,
    val cardioSeconds: Int,
    val otherDurationSeconds: Int,
    val distanceMeters: Int
)

internal fun workoutMeasurements(logs: List<ExerciseLog>, verifiedOnly: Boolean = false): WorkoutMeasurements {
    var external = 0.0
    var additional = 0.0
    var reps = 0
    var hold = 0
    var cardio = 0
    var other = 0
    var distance = 0
    for (log in logs) {
        if (verifiedOnly && log.metricQuality != "VERIFIED") continue
        for (set in log.sets) {
            if (!set.isCompleted) continue
            when (log.trackingType) {
                TrackingType.WEIGHT_REPS -> { external += set.weight.toDouble() * set.reps; reps += set.reps }
                TrackingType.BODYWEIGHT_REPS -> { additional += (set.additionalWeight ?: 0f).toDouble() * set.reps; reps += set.reps }
                TrackingType.ASSISTED_REPS, TrackingType.REPS_ONLY -> reps += set.reps
                TrackingType.DURATION, TrackingType.DISTANCE_DURATION -> {
                    val seconds = set.durationSeconds ?: 0
                    when (log.modality) {
                        ExerciseModality.ISOMETRIC -> hold += seconds
                        ExerciseModality.CARDIO, ExerciseModality.CONDITIONING -> cardio += seconds
                        else -> other += seconds
                    }
                    if (log.trackingType == TrackingType.DISTANCE_DURATION) distance += set.distanceMeters ?: 0
                }
            }
        }
    }
    return WorkoutMeasurements(external, additional, reps, hold, cardio, other, distance)
}

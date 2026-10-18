package com.diajarkoding.imfit.presentation.ui.workout

import androidx.annotation.StringRes
import com.diajarkoding.imfit.R
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

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

internal fun formatVolume(volume: Float, locale: Locale): String {
    val formatter = NumberFormat.getIntegerInstance(locale).apply {
        isGroupingUsed = true
    }
    return "${formatter.format(volume.roundToInt())} kg"
}

internal fun restCountdownProgress(remainingSeconds: Int, totalSeconds: Float): Float =
    if (totalSeconds > 0f) (remainingSeconds / totalSeconds).coerceIn(0f, 1f) else 0f

internal fun formatSetWeight(weight: Float, locale: Locale): String =
    NumberFormat.getNumberInstance(locale).apply {
        isGroupingUsed = false
        maximumFractionDigits = 3
    }.format(weight)

package com.diajarkoding.imfit.presentation.ui.workout

import java.util.Locale

/** Seconds or MM:SS. Null is an incomplete/invalid draft, not a completed measurement. */
internal fun parseSetDurationSeconds(raw: String): Int? {
    if (raw.isEmpty()) return 0
    val separator = raw.indexOf(':')
    if (separator < 0) return raw.takeIf { it.all(Char::isDigit) }?.toIntOrNull()
    if (raw.indexOf(':', separator + 1) >= 0) return null
    val minutes = raw.substring(0, separator)
    val seconds = raw.substring(separator + 1)
    if (minutes.isEmpty() || !minutes.all(Char::isDigit) || seconds.length != 2 || !seconds.all(Char::isDigit)) return null
    val minuteValue = minutes.toIntOrNull() ?: return null
    val secondValue = seconds.toIntOrNull()?.takeIf { it in 0..59 } ?: return null
    if (minuteValue > (Int.MAX_VALUE - secondValue) / 60) return null
    return minuteValue * 60 + secondValue
}

/**
 * Positive HH:MM:SS duration (1..86400 seconds, minutes/seconds 00..59).
 * Rejects MM:SS (such as "01:30") so 90 seconds is never misinterpreted as 90 minutes.
 */
internal fun parseRoutineDurationSeconds(raw: String): Int? {
    val trimmed = raw.trim()
    val firstColon = trimmed.indexOf(':')
    if (firstColon <= 0) return null
    val secondColon = trimmed.indexOf(':', firstColon + 1)
    if (secondColon <= firstColon + 1 || trimmed.indexOf(':', secondColon + 1) >= 0) return null
    val hours = trimmed.substring(0, firstColon)
    val minutes = trimmed.substring(firstColon + 1, secondColon)
    val seconds = trimmed.substring(secondColon + 1)
    if (hours.length !in 1..2 || !hours.all(Char::isDigit)) return null
    if (minutes.length != 2 || !minutes.all(Char::isDigit)) return null
    if (seconds.length != 2 || !seconds.all(Char::isDigit)) return null
    val hourValue = hours.toIntOrNull() ?: return null
    val minuteValue = minutes.toIntOrNull()?.takeIf { it in 0..59 } ?: return null
    val secondValue = seconds.toIntOrNull()?.takeIf { it in 0..59 } ?: return null
    val total = hourValue * 3600 + minuteValue * 60 + secondValue
    return total.takeIf { it in 1..86400 }
}

internal fun formatDurationHhMmSs(seconds: Int): String {
    val clamped = seconds.coerceAtLeast(0)
    val h = clamped / 3600
    val m = (clamped % 3600) / 60
    val s = clamped % 60
    return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
}

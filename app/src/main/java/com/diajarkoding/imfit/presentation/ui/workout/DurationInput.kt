package com.diajarkoding.imfit.presentation.ui.workout

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

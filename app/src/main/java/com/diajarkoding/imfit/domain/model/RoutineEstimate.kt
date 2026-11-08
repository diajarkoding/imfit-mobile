package com.diajarkoding.imfit.domain.model

/**
 * T43: estimated routine length in minutes, from TARGETS only (never from actual rest/active results).
 * Defined only when every exercise is time-based with an explicit duration on every row:
 * `E = Σ row durations + Σ rest of every row that is followed by another row` (the last row's rest is excluded;
 * rest runs across exercise boundaries), `minutes = ceil(E / 60)`.
 * Strength / mixed routines return null (shown as "—") until the product defines an execution-time formula;
 * the former flat 8 minutes per exercise is intentionally not kept as an exact figure.
 */
fun estimateRoutineDurationMinutes(exercises: List<TemplateExercise>): Int? {
    if (exercises.isEmpty()) return null
    var seconds = 0L
    var rows = 0
    var lastRest = 0
    for (exercise in exercises) {
        if (exercise.trackingType != TrackingType.DURATION && exercise.trackingType != TrackingType.DISTANCE_DURATION) return null
        val targets: List<Pair<Int, Int>> = if (exercise.measurementVersion == 3) {
            exercise.setTargets.orEmpty().map { (it.durationSeconds ?: return null) to it.restSeconds }
        } else {
            val duration = exercise.targetDurationSeconds ?: return null
            List(exercise.sets) { duration to exercise.restSeconds }
        }
        for ((duration, rest) in targets) {
            if (duration <= 0 || rest < 0) return null
            seconds += duration
            if (rows > 0) seconds += lastRest
            rows++
            lastRest = rest
        }
    }
    if (rows == 0) return null
    return ((seconds + 59) / 60).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}

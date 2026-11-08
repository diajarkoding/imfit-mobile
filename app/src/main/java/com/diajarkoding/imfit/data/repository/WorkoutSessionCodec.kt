package com.diajarkoding.imfit.data.repository

import com.diajarkoding.imfit.domain.model.CardioMetric
import com.diajarkoding.imfit.domain.model.Exercise
import com.diajarkoding.imfit.domain.model.ExerciseInputDefaults
import com.diajarkoding.imfit.domain.model.ExerciseLog
import com.diajarkoding.imfit.domain.model.ExerciseModality
import com.diajarkoding.imfit.domain.model.ExecutionStopwatch
import com.diajarkoding.imfit.domain.model.MuscleCategory
import com.diajarkoding.imfit.domain.model.RestOutcome
import com.diajarkoding.imfit.domain.model.RestTimerTrigger
import com.diajarkoding.imfit.domain.model.TrackingProfile
import com.diajarkoding.imfit.domain.model.TrackingType
import com.diajarkoding.imfit.domain.model.WorkoutSession
import com.diajarkoding.imfit.domain.model.WorkoutSet
import com.diajarkoding.imfit.domain.model.isTwoDecimalIn
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The caller must retain the Room row when recovery fails. */
internal class SessionRecoveryException(cause: Exception) :
    IllegalStateException("Unable to recover saved workout session; saved data was retained", cause)

/** Local persistence envelope. Schema 1/2 retain their historical measurement semantics. */
internal object WorkoutSessionCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(session: WorkoutSession): String {
        validate(session)
        return json.encodeToString(SessionData.fromDomain(session))
    }

    fun decode(raw: String): WorkoutSession = try {
        val root = json.parseToJsonElement(raw).jsonObject
        val version = root["schemaVersion"]?.let {
            val value = it.jsonPrimitive
            require(!value.isString) { "Invalid local session schema" }
            requireNotNull(value.intOrNull) { "Invalid local session schema" }
        } ?: 1
        require(version in 1..3) { "Unsupported local session schema $version" }
        if (version == 3) requireV3Fields(root)
        json.decodeFromJsonElement(SessionData.serializer(), root).toDomain().also(::validate)
    } catch (e: Exception) {
        throw SessionRecoveryException(e)
    }

    private fun validate(session: WorkoutSession) {
        require(session.schemaVersion in 1..3) { "Unsupported local session schema ${session.schemaVersion}" }
        val v3 = session.schemaVersion == 3
        if (v3) {
            require(session.exerciseLogs.isNotEmpty()) { "Missing v3 exercises" }
            // templateRevision is written by T26 starts; T15-era v3 fixtures/records without it stay readable.
            require(session.templateRevision == null || session.templateRevision >= 0) { "Invalid routine revision" }
        } else require(session.templateRevision == null) { "Routine revision requires session schema 3" }
        val resultIds = if (v3) HashSet<UUID>() else null
        for (log in session.exerciseLogs) {
            if (!v3) {
                require(log.measurementVersion == null && log.inputDefaults?.schemaVersion != 3) { "V3 measurements require session schema 3" }
                require(log.sets.all { it.measurementVersion == null && it.targetRowId == null &&
                    it.caloriesKcal == null && it.speedKph == null && it.lapReps == null && it.inclinePercent == null &&
                    it.restOutcome == null }) {
                    "V3 result fields in legacy session"
                }
                continue
            }
            require(log.measurementVersion == 3) { "Missing v3 exercise measurement version" }
            val config = requireNotNull(log.inputDefaults) { "Missing v3 frozen configuration" }
            require(config.schemaVersion == 3) { "Missing v3 frozen configuration version" }
            require(log.trackingType.name == config.trackingType && log.modality == config.modality) { "Inconsistent v3 frozen metadata" }
            require(log.sets.size in 1..20) { "Invalid v3 result count" }
            val targetIds = HashSet<String>(log.sets.size)
            for (set in log.sets) {
                require(set.measurementVersion == 3 && !set.targetRowId.isNullOrBlank()) { "Missing v3 result identity" }
                val resultId = UUID.fromString(set.id)
                require(resultId.toString().equals(set.id, ignoreCase = true)) { "Invalid v3 result UUID" }
                require(resultIds!!.add(resultId)) { "Duplicate result IDs" }
                require(set.setNumber in 1..20) { "Invalid v3 result order" }
                require(set.setNumber == targetIds.size + 1 && targetIds.add(requireNotNull(set.targetRowId))) {
                    "Invalid v3 result order or duplicate target association"
                }
                require(config.setTargets.orEmpty().any { it.rowId == set.targetRowId } ||
                    config.extraSetTargets.any { it.rowId == set.targetRowId }) { "Result target is missing from snapshot" }
                require(set.additionalWeight == null && set.assistanceWeight == null) { "Legacy load fields in v3 result" }
                require(set.restOutcome == null || (set.isCompleted && set.restOutcome.isValid())) { "Rest outcome requires a completed set" }
                if (log.trackingType == TrackingType.WEIGHT_REPS) {
                    require(set.weightKg == null || set.weightKg.isTwoDecimalIn(0.0, 1000.0)) { "Invalid v3 weight" }
                    require(set.reps == null || set.reps in 0..1000) { "Invalid v3 reps" }
                    require(set.durationSeconds == null) { "Duration in strength result" }
                    if (set.isCompleted) require(set.weightKg != null && set.reps != null && set.reps > 0) { "Incomplete strength result" }
                } else {
                    require(set.weightKg == null && set.reps == null) { "Load/reps in duration result" }
                    require(set.durationSeconds == null || set.durationSeconds in 0..86400) { "Invalid v3 duration" }
                    if (set.isCompleted) require(set.durationSeconds != null && set.durationSeconds > 0) { "Incomplete duration result" }
                }
                fun metric(value: Double?, capability: CardioMetric, min: Double = 0.0, max: Double = 9_999_999_999.99) =
                    value == null || (log.modality == ExerciseModality.CARDIO && capability in config.supportedMetrics && value.isTwoDecimalIn(min, max))
                require(metric(set.distanceMeters, CardioMetric.DISTANCE) && metric(set.caloriesKcal, CardioMetric.CALORIES) &&
                    metric(set.speedKph, CardioMetric.SPEED) && metric(set.inclinePercent, CardioMetric.INCLINE, -999.99, 999.99)) { "Invalid/unsupported v3 metric" }
                require(set.lapReps == null || (log.modality == ExerciseModality.CARDIO &&
                    CardioMetric.LAPS in config.supportedMetrics && set.lapReps >= 0)) { "Invalid/unsupported v3 laps" }
            }
        }
        val trigger = session.pendingRestTrigger ?: return
        require(v3 && trigger.sessionId == session.id) { "Invalid pending rest session" }
        val log = requireNotNull(session.exerciseLogs.singleOrNull { it.exercise.id == trigger.exerciseId }) { "Pending rest exercise is missing" }
        val set = log.sets.singleOrNull { it.id == trigger.setId }
        require(set != null && set.isCompleted && set.targetRowId == trigger.targetRowId) { "Invalid pending rest result" }
        val config = requireNotNull(log.inputDefaults)
        val target = config.setTargets.orEmpty().find { it.rowId == trigger.targetRowId }
            ?: config.extraSetTargets.singleOrNull { it.rowId == trigger.targetRowId }
        require(target != null && trigger.restSeconds == target.restSeconds && trigger.restSeconds > 0) { "Invalid pending rest configuration" }
        require(trigger.deadlineElapsedMs >= 0 && trigger.bootId.isNotBlank()) { "Invalid pending rest deadline" }
    }

    private fun requireV3Fields(root: JsonObject) {
        root.requireFields("id", "templateId", "templateName", "startTime", "exerciseLogs", "schemaVersion")
        root.getValue("exerciseLogs").jsonArray.forEach { element ->
            val log = element.jsonObject
            log.requireFields("exercise", "sets", "inputDefaults", "trackingType", "modality", "measurementVersion")
            log.getValue("sets").jsonArray.forEach { set ->
                set.jsonObject.requireFields("id", "targetRowId", "setNumber", "weight", "reps",
                    "durationSeconds", "isCompleted", "measurementVersion")
            }
        }
    }

    private fun JsonObject.requireFields(vararg fields: String) {
        require(fields.all { it in this }) { "Missing required v3 session fields: ${fields.filterNot { it in this }}" }
    }
}

@Serializable
private data class SessionData(
    val id: String,
    val templateId: String,
    val templateName: String,
    val startTime: Long,
    val exerciseLogs: List<ExerciseLogData>,
    val currentExerciseIndex: Int = 0,
    val isPaused: Boolean = false,
    val totalPausedTimeMs: Long = 0,
    val lastPauseTime: Long? = null,
    val schemaVersion: Int = 1,
    val pendingRestTrigger: RestTimerTrigger? = null,
    val templateRevision: Long? = null,
    val sessionStopwatch: ExecutionStopwatch? = null,
    val pauseStopwatch: ExecutionStopwatch? = null
) {
    fun toDomain(): WorkoutSession {
        require(schemaVersion in 1..3) { "Unsupported local session schema $schemaVersion" }
        val logs = exerciseLogs.mapIndexed { index, log -> log.toDomain(id, index, schemaVersion) }
        return WorkoutSession(id = id, templateId = templateId, templateName = templateName,
            startTime = startTime, exerciseLogs = logs, currentExerciseIndex = currentExerciseIndex,
            isPaused = isPaused, totalPausedTimeMs = totalPausedTimeMs, lastPauseTime = lastPauseTime,
            schemaVersion = schemaVersion, pendingRestTrigger = pendingRestTrigger, templateRevision = templateRevision,
            sessionStopwatch = sessionStopwatch, pauseStopwatch = pauseStopwatch)
    }

    companion object {
        fun fromDomain(session: WorkoutSession) = SessionData(
            id = session.id, templateId = session.templateId, templateName = session.templateName,
            startTime = session.startTime, exerciseLogs = session.exerciseLogs.map { ExerciseLogData.fromDomain(it) },
            currentExerciseIndex = session.currentExerciseIndex, isPaused = session.isPaused,
            totalPausedTimeMs = session.totalPausedTimeMs, lastPauseTime = session.lastPauseTime,
            schemaVersion = session.schemaVersion, pendingRestTrigger = session.pendingRestTrigger,
            templateRevision = session.templateRevision, sessionStopwatch = session.sessionStopwatch,
            pauseStopwatch = session.pauseStopwatch)
    }
}

@Serializable
private data class ExerciseLogData(
    val exercise: ExerciseData,
    val sets: List<SetData>,
    val restSeconds: Int = 60,
    val inputDefaults: JsonElement? = null,
    val trackingType: TrackingType? = null,
    val modality: ExerciseModality? = null,
    val metricQuality: String? = null,
    val measurementVersion: Int? = null
) {
    fun toDomain(sessionId: String, index: Int, version: Int): ExerciseLog {
        val defaults = inputDefaults?.let { ExerciseInputDefaults.fromSnapshotJson(it) }
        require(measurementVersion == null || measurementVersion == 3) { "Unknown exercise measurement version" }
        val v3 = version == 3
        require(v3 || (measurementVersion != 3 && defaults?.schemaVersion != 3 && sets.none { it.measurementVersion == 3 })) {
            "V3 measurements require session schema 3"
        }
        if (v3) {
            val config = requireNotNull(defaults) { "Missing v3 frozen configuration" }
            require(measurementVersion == 3 && config.schemaVersion == 3 && config.setTargets != null) { "Missing v3 frozen configuration" }
            require(trackingType != null && modality != null) { "Missing v3 execution metadata" }
            require(config.trackingType == trackingType.name && config.modality == modality) { "Inconsistent v3 frozen metadata" }
        }
        val frozenExercise = exercise.toDomain(v3, defaults)
        val results = sets.mapIndexed { setIndex, set -> set.toDomain(sessionId, index, exercise.id, setIndex, v3) }
        return ExerciseLog(exercise = frozenExercise, sets = results, restSeconds = restSeconds,
            inputDefaults = defaults, trackingType = trackingType ?: TrackingType.WEIGHT_REPS,
            modality = modality ?: ExerciseModality.STRENGTH,
            metricQuality = if (trackingType == null) "LEGACY_UNVERIFIED" else metricQuality ?: "LEGACY_UNVERIFIED",
            measurementVersion = measurementVersion)
    }

    companion object {
        fun fromDomain(log: ExerciseLog) = ExerciseLogData(
            exercise = ExerciseData.fromDomain(log.exercise), sets = log.sets.map { SetData.fromDomain(it) },
            restSeconds = log.restSeconds, inputDefaults = log.inputDefaults?.toSnapshotJson(),
            trackingType = log.trackingType, modality = log.modality, metricQuality = log.metricQuality,
            measurementVersion = log.measurementVersion)
    }
}

@Serializable
private data class ExerciseData(
    val id: String,
    val name: String,
    val muscleCategory: String,
    val description: String,
    val imageUrl: String? = null,
    val isActive: Boolean = true,
    val defaultTrackingType: TrackingType? = null,
    val modality: ExerciseModality? = null,
    val allowedTrackingTypes: List<TrackingType>? = null,
    val trackingProfile: TrackingProfile? = null,
    val supportedMetrics: List<CardioMetric> = emptyList()
) {
    fun toDomain(v3: Boolean, snapshot: ExerciseInputDefaults?): Exercise {
        // V3 execution metadata comes only from its frozen snapshot, never today's catalog.
        val format = if (v3) TrackingType.valueOf(requireNotNull(snapshot?.trackingType)) else
            defaultTrackingType ?: TrackingType.WEIGHT_REPS
        return Exercise(id = id, name = name,
            muscleCategory = if (v3) MuscleCategory.valueOf(muscleCategory) else
                MuscleCategory.entries.firstOrNull { it.name == muscleCategory } ?: MuscleCategory.CHEST,
            description = description, imageUrl = imageUrl, isActive = isActive,
            defaultTrackingType = format,
            modality = if (v3) requireNotNull(snapshot?.modality) else modality ?: ExerciseModality.STRENGTH,
            allowedTrackingTypes = if (v3) listOf(format) else allowedTrackingTypes ?: listOf(format),
            trackingProfile = if (v3) snapshot?.trackingProfile else trackingProfile,
            supportedMetrics = if (v3) requireNotNull(snapshot).supportedMetrics else supportedMetrics)
    }

    companion object {
        fun fromDomain(exercise: Exercise) = ExerciseData(exercise.id, exercise.name,
            exercise.muscleCategory.name, exercise.description, exercise.imageUrl, exercise.isActive,
            exercise.defaultTrackingType, exercise.modality, exercise.allowedTrackingTypes,
            exercise.trackingProfile, exercise.supportedMetrics)
    }
}

@Serializable
private data class SetData(
    val setNumber: Int,
    val weight: Double? = 0.0,
    val reps: Int? = 0,
    val isCompleted: Boolean = false,
    val durationSeconds: Int? = null,
    val distanceMeters: Double? = null,
    val additionalWeight: Float? = null,
    val assistanceWeight: Float? = null,
    val executionStopwatch: ExecutionStopwatch? = null,
    val id: String? = null,
    val targetRowId: String? = null,
    val measurementVersion: Int? = null,
    val caloriesKcal: Double? = null,
    val speedKph: Double? = null,
    val lapReps: Int? = null,
    val inclinePercent: Double? = null,
    val restOutcome: RestOutcome? = null
) {
    fun toDomain(sessionId: String, exerciseIndex: Int, exerciseId: String, setIndex: Int, v3: Boolean): WorkoutSet {
        require(measurementVersion == null || measurementVersion == 3) { "Unknown result measurement version" }
        if (v3) {
            require(measurementVersion == 3 && !id.isNullOrBlank() && !targetRowId.isNullOrBlank()) { "Missing v3 result identity" }
        }
        val resultId = id?.takeIf { it.isNotBlank() } ?: UUID.nameUUIDFromBytes(
            listOf("local-session-result", sessionId, exerciseIndex, exerciseId, setIndex, setNumber)
                .joinToString("\u001F").toByteArray(StandardCharsets.UTF_8)).toString()
        return WorkoutSet(setNumber = setNumber, weightKg = weight, reps = reps, isCompleted = isCompleted,
            durationSeconds = durationSeconds, distanceMeters = distanceMeters, additionalWeight = additionalWeight,
            assistanceWeight = assistanceWeight, executionStopwatch = executionStopwatch, id = resultId,
            targetRowId = targetRowId, measurementVersion = measurementVersion, caloriesKcal = caloriesKcal,
            speedKph = speedKph, lapReps = lapReps, inclinePercent = inclinePercent, restOutcome = restOutcome)
    }

    companion object {
        fun fromDomain(set: WorkoutSet) = SetData(setNumber = set.setNumber, weight = set.weightKg,
            reps = set.reps, isCompleted = set.isCompleted, durationSeconds = set.durationSeconds,
            distanceMeters = set.distanceMeters, additionalWeight = set.additionalWeight,
            assistanceWeight = set.assistanceWeight, executionStopwatch = set.executionStopwatch,
            id = set.id, targetRowId = set.targetRowId, measurementVersion = set.measurementVersion,
            caloriesKcal = set.caloriesKcal, speedKph = set.speedKph, lapReps = set.lapReps,
            inclinePercent = set.inclinePercent, restOutcome = set.restOutcome)
    }
}

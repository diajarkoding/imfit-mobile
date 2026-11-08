package com.diajarkoding.imfit.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** Configuration captured before prefill. Scalar fields are legacy read/replay data only. */
@Serializable
data class ExerciseInputDefaults(
    val sets: Int,
    val reps: Int,
    val trackingType: String? = null,
    val targetDurationSeconds: Int? = null,
    val targetDistanceMeters: Int? = null,
    val schemaVersion: Int? = null,
    val setTargets: List<SetTarget>? = null,
    val extraSetTargets: List<SetTarget> = emptyList(),
    val modality: ExerciseModality? = null,
    val trackingProfile: TrackingProfile? = null,
    val supportedMetrics: List<CardioMetric> = emptyList()
) {
    val key: String get() = if (schemaVersion == 3) {
        snapshotObject(includeExtraTargets = false).toString()
    } else if (trackingType != null) {
        "$sets:$reps:$trackingType:${targetDurationSeconds ?: 0}:${targetDistanceMeters ?: 0}"
    } else "$sets:$reps"

    fun requireV3Snapshot() {
        require(schemaVersion == 3) { "Not a v3 configuration snapshot" }
        val type = TrackingType.valueOf(requireNotNull(trackingType))
        require(validTrackingMetadata(requireNotNull(modality), type, trackingProfile, supportedMetrics)) { "Invalid v3 tracking metadata" }
        val initial = requireNotNull(setTargets) { "Missing initial targets" }
        fun validRows(rows: List<SetTarget>) = rows.size <= 20 &&
            rows.withIndex().all { (index, row) -> row.setNumber == index + 1 && row.isValidFor(type) }
        require(initial.isNotEmpty() && validRows(initial) && validRows(extraSetTargets)) { "Invalid v3 targets" }
        val identities = HashSet<String>(initial.size + extraSetTargets.size)
        require(initial.all { identities.add(it.rowId) } && extraSetTargets.all { identities.add(it.rowId) }) { "Duplicate v3 target identity" }
    }

    fun toSnapshotJson(): JsonElement = if (schemaVersion == 3) snapshotObject(includeExtraTargets = true)
        else legacyJson.encodeToJsonElement(this)

    private fun snapshotObject(includeExtraTargets: Boolean): JsonObject {
        requireV3Snapshot()
        return buildJsonObject {
            put("schema_version", 3)
            put("measurement_version", 3)
            put("modality", requireNotNull(modality).name)
            put("tracking_type", requireNotNull(trackingType))
            put("tracking_profile", trackingProfile?.let { JsonPrimitive(it.wireValue) } ?: JsonNull)
            put("supported_metrics", JsonArray(supportedMetrics.map { JsonPrimitive(it.wireValue) }))
            put("set_targets", snapshotJson.encodeToJsonElement(requireNotNull(setTargets)))
            put("extra_set_targets", snapshotJson.encodeToJsonElement(if (includeExtraTargets) extraSetTargets else emptyList()))
        }
    }

    fun toStoredString(): String = if (schemaVersion == 3) toSnapshotJson().toString() else key

    companion object {
        private val legacyJson = Json { encodeDefaults = false }
        private val snapshotJson = Json { encodeDefaults = true; explicitNulls = true }
        private val snapshotKeys = setOf("schema_version", "measurement_version", "modality", "tracking_type",
            "tracking_profile", "supported_metrics", "set_targets", "extra_set_targets")
        private val rowKeys = setOf("row_id", "set_number", "weight", "reps", "duration_seconds", "rest_seconds")

        fun fromStoredString(value: String): ExerciseInputDefaults {
            val text = value.trim()
            return fromSnapshotJson(if (text.startsWith("{") || text.startsWith("[") || text.startsWith("\""))
                snapshotJson.parseToJsonElement(text) else JsonPrimitive(value))
        }

        fun fromSnapshotJson(element: JsonElement): ExerciseInputDefaults {
            if (element is JsonPrimitive && element.isString) return fromLegacyKey(element.content)
            val obj = element as? JsonObject ?: throw IllegalArgumentException("Configuration must be an object or legacy key")
            fun field(camel: String, snake: String = camel): JsonElement? = obj[snake] ?: obj[camel]
            fun integer(value: JsonElement?): Int? {
                if (value == null || value == JsonNull) return null
                val primitive = value as? JsonPrimitive ?: throw IllegalArgumentException("Expected integer")
                require(!primitive.isString) { "Expected numeric integer" }
                return requireNotNull(primitive.intOrNull) { "Expected integer" }
            }
            fun string(value: JsonElement?): String? {
                if (value == null || value == JsonNull) return null
                val primitive = value as? JsonPrimitive ?: throw IllegalArgumentException("Expected string")
                require(primitive.isString) { "Expected string" }
                return primitive.content
            }
            val versionElement = field("schemaVersion", "schema_version")
            val version = integer(versionElement)
            require(versionElement == null || version in 1..3) { "Unsupported configuration version" }
            if (version == 3) {
                require(obj.keys == snapshotKeys && integer(obj["measurement_version"]) == 3) { "Incomplete v3 configuration" }
                fun rows(name: String): List<SetTarget> {
                    val array = obj[name] as? JsonArray ?: throw IllegalArgumentException("Missing $name")
                    for (row in array) {
                        val fields = row as? JsonObject ?: throw IllegalArgumentException("Invalid target row")
                        require(fields.keys == rowKeys) { "Incomplete target row" }
                        requireNotNull(string(fields["row_id"])) { "Missing target row identity" }
                        for (key in listOf("set_number", "weight", "reps", "duration_seconds", "rest_seconds")) {
                            val value = fields.getValue(key)
                            require(value == JsonNull || (value is JsonPrimitive && !value.isString)) { "Target value must be numeric or null" }
                        }
                    }
                    return snapshotJson.decodeFromJsonElement(array)
                }
                val metrics = obj["supported_metrics"] as? JsonArray ?: throw IllegalArgumentException("Missing capabilities")
                val result = ExerciseInputDefaults(
                    sets = (obj["set_targets"] as? JsonArray)?.size ?: 0,
                    reps = 0,
                    trackingType = requireNotNull(string(obj["tracking_type"])),
                    schemaVersion = 3,
                    setTargets = rows("set_targets"),
                    extraSetTargets = rows("extra_set_targets"),
                    modality = ExerciseModality.valueOf(requireNotNull(string(obj["modality"]))),
                    trackingProfile = string(obj["tracking_profile"])?.let(TrackingProfile::fromWire),
                    supportedMetrics = metrics.map { CardioMetric.fromWire(requireNotNull(string(it))) }
                )
                result.requireV3Snapshot()
                return result
            }
            require(obj["measurement_version"] == null || obj["measurement_version"] == JsonNull) { "Unrecognized configuration contract" }
            require(obj["set_targets"] == null && obj["extra_set_targets"] == null && obj["setTargets"] == null) { "Unversioned targets" }
            val type = string(field("trackingType", "tracking_type"))?.also { TrackingType.valueOf(it) }
            return ExerciseInputDefaults(
                sets = requireNotNull(integer(obj["sets"])) { "Missing legacy target count" },
                reps = integer(obj["reps"]) ?: 0,
                trackingType = type,
                targetDurationSeconds = integer(field("targetDurationSeconds", "target_duration_seconds")),
                targetDistanceMeters = integer(field("targetDistanceMeters", "target_distance_meters")),
                schemaVersion = version
            )
        }

        private fun fromLegacyKey(key: String): ExerciseInputDefaults {
            val parts = key.split(':')
            require(parts.size == 2 || parts.size == 5) { "Unsupported legacy defaults key" }
            return ExerciseInputDefaults(
                sets = parts[0].toInt(),
                reps = parts[1].toInt(),
                trackingType = if (parts.size == 5) TrackingType.valueOf(parts[2]).name else null,
                targetDurationSeconds = if (parts.size == 5) parts[3].toInt().takeUnless { it == 0 } else null,
                targetDistanceMeters = if (parts.size == 5) parts[4].toInt().takeUnless { it == 0 } else null
            )
        }
    }
}

fun TemplateExercise.inputDefaults(): ExerciseInputDefaults {
    if (measurementVersion == 3) {
        return ExerciseInputDefaults(
            sets = setCount, reps = 0, trackingType = trackingType.name,
            schemaVersion = 3, setTargets = requireNotNull(setTargets), modality = exercise.modality,
            trackingProfile = exercise.trackingProfile, supportedMetrics = exercise.supportedMetrics
        ).also { it.requireV3Snapshot() }
    }
    require(measurementVersion == null) { "Unsupported routine measurement version" }
    return ExerciseInputDefaults(sets, reps ?: 0, trackingType.name, targetDurationSeconds, targetDistanceMeters)
}

fun ExerciseLog.addDefaultSet(): ExerciseLog {
    if (measurementVersion == 3) {
        if (sets.size >= 20) return this
        val defaults = requireNotNull(inputDefaults) { "Missing v3 configuration snapshot" }
        val baseTarget = defaults.setTargets?.lastOrNull() ?: defaults.setTargets?.firstOrNull()
        val isStrength = trackingType == TrackingType.WEIGHT_REPS
        val newRowId = java.util.UUID.randomUUID().toString()
        val newExtraTarget = SetTarget(
            rowId = newRowId,
            setNumber = defaults.extraSetTargets.size + 1,
            weightKg = if (isStrength) (baseTarget?.weightKg ?: 0.0) else null,
            reps = if (isStrength) (baseTarget?.reps ?: 8).coerceIn(1, 100) else null,
            durationSeconds = if (isStrength) null else (baseTarget?.durationSeconds ?: 60).coerceIn(1, 86400),
            restSeconds = 60
        )
        val updatedDefaults = defaults.copy(
            extraSetTargets = defaults.extraSetTargets + newExtraTarget
        ).also { it.requireV3Snapshot() }
        val newSet = WorkoutSet(
            setNumber = sets.size + 1,
            weightKg = newExtraTarget.weightKg,
            reps = newExtraTarget.reps,
            durationSeconds = newExtraTarget.durationSeconds,
            isCompleted = false,
            id = java.util.UUID.randomUUID().toString(),
            targetRowId = newRowId,
            measurementVersion = 3
        )
        return copy(
            sets = sets + newSet,
            inputDefaults = updatedDefaults
        )
    }
    require(measurementVersion == null) { "Unsupported exercise measurement version" }
    return copy(sets = sets + WorkoutSet(
        setNumber = (sets.maxOfOrNull { it.setNumber } ?: 0) + 1,
        reps = inputDefaults?.reps ?: (TemplateExercise(exercise).reps ?: 0),
        durationSeconds = inputDefaults?.targetDurationSeconds,
        distanceMeters = inputDefaults?.targetDistanceMeters?.toDouble()
    ))
}

fun ExerciseLog.removeDraftSet(setIndex: Int): ExerciseLog {
    if (sets.size <= 1) return this
    val target = sets.getOrNull(setIndex) ?: return this
    if (target.isCompleted) return this
    if (measurementVersion == 3) {
        val defaults = requireNotNull(inputDefaults) { "Missing v3 configuration snapshot" }
        val remainingSets = sets
            .filterIndexed { index, _ -> index != setIndex }
            .mapIndexed { index, set -> set.copy(setNumber = index + 1) }
        val referencedRowIds = remainingSets.mapNotNull { it.targetRowId }.toSet()
        val remainingExtras = defaults.extraSetTargets
            .filter { it.rowId in referencedRowIds }
            .mapIndexed { index, row -> row.copy(setNumber = index + 1) }
        val updatedDefaults = defaults.copy(extraSetTargets = remainingExtras).also { it.requireV3Snapshot() }
        return copy(sets = remainingSets, inputDefaults = updatedDefaults)
    }
    return copy(sets = sets.filterIndexed { index, _ -> index != setIndex })
}

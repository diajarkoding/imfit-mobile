package com.diajarkoding.imfit.data.legacy

import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

data class LegacyMappedImport(
    val source: JsonObject,
    val workout: JsonObject,
    val exercises: JsonArray,
    val sets: JsonArray,
    val idMapping: Map<String, String>,
    val exerciseCount: Int,
    val setCount: Int,
)

enum class LegacyImportFailure { INVALID_SOURCE, REVIEW_REQUIRED }
class LegacyImportException(val reason: LegacyImportFailure) : IllegalArgumentException("Legacy import unavailable: $reason")

/** Pure mapping of one accepted read-only snapshot. Never invents IDs, dates, or provenance. */
object LegacyImportMapper {
    private val uuidPattern = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val decimalPattern = Regex("-?[0-9]+(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
    private val integerPattern = Regex("-?(0|[1-9][0-9]*)")
    private val timestampFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    fun map(aggregate: LegacyAggregate): LegacyMappedImport {
        try {
            return mapChecked(aggregate)
        } catch (failure: LegacyImportException) {
            throw failure
        } catch (_: Exception) {
            invalid()
        }
    }

    private fun mapChecked(a: LegacyAggregate): LegacyMappedImport {
        if (a.inventory.schemaVersion !in setOf(6, 7, 9) || a.item.kind != LegacyKind.WORKOUT) invalid()
        if (a.item.disposition != LegacyDisposition.PENDING_CREATE) review()
        if (a.aggregateFingerprint != a.inventory.aggregateFingerprint(a.item) ||
            a.dependencyFingerprint != a.inventory.dependencyFingerprint() ||
            !Regex("[0-9a-f]{64}").matches(a.aggregateFingerprint)) invalid()
        val expected = a.inventory.aggregateItems(a.item)
        val expectedByKey = expected.associateBy { it.kind to it.id }
        if (expectedByKey.size != expected.size) invalid()
        if (a.records.keys.any { it !in setOf(LegacyKind.WORKOUT, LegacyKind.EXERCISE_LOG, LegacyKind.SET) }) invalid()
        val allRecords = a.records.flatMap { (kind, rows) -> rows.map { kind to it } }
        if (allRecords.size != expected.size) invalid()
        allRecords.forEach { (kind, row) ->
            val item = expectedByKey[kind to legacyRecordId(kind, row)] ?: invalid()
            if (legacyRowFingerprint(row) != item.fingerprint) invalid()
            if (row["pending_operation"] !in setOf(null, "CREATE") || row["sync_status"] !in setOf("SYNCED", "PENDING_SYNC", "SYNC_FAILED")) review()
        }
        val parent = a.records[LegacyKind.WORKOUT]?.singleOrNull() ?: invalid()
        if (parent["pending_operation"] != "CREATE" || parent["id"] != a.item.id) review()
        val owner = uuid(a.inventory.ownerId)
        if (uuid(parent.text("user_id")) != owner) invalid()
        val workoutId = uuid(parent.text("id"))
        val templateId = parent["template_id"]?.let(::uuid)
        validateDependencies(a)
        if (templateId != null) {
            val template = a.dependencies.getValue("TEMPLATE").singleOrNull { uuid(it.text("id")) == templateId } ?: review()
            if (uuid(template.text("user_id")) != owner) review()
        }
        val rawExercises = a.records[LegacyKind.EXERCISE_LOG].orEmpty()
        val rawSets = a.records[LegacyKind.SET].orEmpty()
        val availableExercises = a.dependencies.getValue("EXERCISE").mapTo(hashSetOf()) { it.text("id") }
        val exercises = rawExercises.map { row ->
            if (uuid(row.text("workout_log_id")) != workoutId) invalid()
            if (row.text("exercise_id") !in availableExercises) review()
            buildJsonObject {
                put("id", uuid(row.text("id")))
                put("workout_log_id", workoutId)
                put("exercise_id", row.text("exercise_id"))
                put("exercise_name", row.text("exercise_name"))
                put("muscle_category", row.text("muscle_category"))
                put("order_index", row.integer("order_index"))
                totals(row)
                put("input_defaults", defaults(row["input_defaults"], a.inventory.schemaVersion))
                timestamps(row)
            }
        }.sortedWith(compareBy({ it.getValue("order_index").jsonPrimitive.int }, { it.string("id") }))
        if (exercises.map { it.string("id") }.toSet().size != exercises.size ||
            exercises.map { it.string("exercise_id") }.toSet().size != exercises.size ||
            exercises.map { it.getValue("order_index") }.toSet().size != exercises.size) invalid()
        val exercisesById = exercises.associateBy { it.string("id") }
        val sets = rawSets.map { row ->
            val exerciseId = uuid(row.text("exercise_log_id"))
            val exercise = exercisesById[exerciseId] ?: invalid()
            if (uuid(row.text("workout_log_id")) != workoutId || row.text("exercise_id") != exercise.string("exercise_id")) invalid()
            buildJsonObject {
                put("id", uuid(row.text("id")))
                put("exercise_log_id", exerciseId)
                put("workout_log_id", workoutId)
                put("exercise_id", row.text("exercise_id"))
                put("set_number", row.integer("set_number", 1))
                put("weight", decimal(row.text("weight"), BigDecimal("1000")).toPlainString())
                put("reps", row.integer("reps", max = 1000))
                put("is_completed", when (row.text("is_completed")) { "0" -> false; "1" -> true; else -> invalid() })
                timestamps(row)
            }
        }.sortedWith(compareBy({ it.string("exercise_log_id") }, { it.getValue("set_number").jsonPrimitive.int }, { it.string("id") }))
        if (sets.map { it.string("exercise_log_id") to it.getValue("set_number") }.toSet().size != sets.size) invalid()
        val ids = listOf(workoutId) + exercises.map { it.string("id") } + sets.map { it.string("id") }
        if (ids.toSet().size != ids.size) invalid()
        val start = epoch(parent.text("start_time"))
        val end = epoch(parent.text("end_time"))
        if (end <= start) invalid()
        val workout = buildJsonObject {
            put("id", workoutId)
            put("user_id", owner)
            put("template_id", templateId?.let(::JsonPrimitive) ?: JsonNull)
            put("template_name", parent.text("template_name"))
            put("date", epoch(parent.text("date")).atOffset(ZoneOffset.UTC).toLocalDate().toString())
            put("start_time", timestampFormat.format(start))
            put("end_time", timestampFormat.format(end))
            totals(parent)
            put("deleted_at", parent["deleted_at"]?.let { JsonPrimitive(timestampFormat.format(epoch(it))) } ?: JsonNull)
            timestamps(parent)
        }
        val setsByExercise = sets.groupBy { it.string("exercise_log_id") }
        exercises.forEach { exercise -> validateTotals(exercise, setsByExercise[exercise.string("id")].orEmpty()) }
        validateTotals(workout, sets)
        return LegacyMappedImport(
            source = buildJsonObject {
                put("schema_version", a.inventory.schemaVersion)
                put("kind", "WORKOUT")
                put("id", workoutId)
                put("fingerprint", a.aggregateFingerprint)
                put("mapping_version", 1)
            },
            workout = workout, exercises = JsonArray(exercises), sets = JsonArray(sets),
            idMapping = ids.associateWith { it }, exerciseCount = exercises.size, setCount = sets.size,
        )
    }
    private fun validateDependencies(a: LegacyAggregate) {
        if (a.dependencies.keys != setOf("TEMPLATE", "TEMPLATE_EXERCISE", "EXERCISE")) invalid()
        val actual = a.dependencies.flatMap { (kind, rows) -> rows.map { row ->
            val id = if (kind == "TEMPLATE_EXERCISE") legacyRecordId(LegacyKind.TEMPLATE_EXERCISE, row) else row.text("id")
            LegacyDependency(kind, id, true, legacyRowFingerprint(row))
        } }
        val expected = a.inventory.dependencies.filter { it.available }
        if (actual.size != expected.size || actual.toSet() != expected.toSet() || actual.toSet().size != actual.size) invalid()
    }

    private fun validateTotals(row: JsonObject, sets: List<JsonObject>) {
        var volume = BigDecimal.ZERO
        var reps = 0L
        var count = 0L
        sets.forEach { set ->
            if (set.getValue("is_completed").jsonPrimitive.boolean) {
                val setReps = set.getValue("reps").jsonPrimitive.int
                volume = volume.add(BigDecimal(set.string("weight")).multiply(BigDecimal(setReps)))
                reps += setReps
                count++
            }
        }
        if (volume.compareTo(BigDecimal(row.string("total_volume"))) != 0 ||
            reps != row.getValue("total_reps").jsonPrimitive.long || count != row.getValue("total_sets").jsonPrimitive.long) review()
    }

    private fun defaults(raw: String?, schema: Int): JsonElement {
        if (raw == null || raw == "null") return JsonNull
        if (schema == 6) invalid()
        val value = Json.parseToJsonElement(raw) as? JsonObject ?: invalid()
        if (value.keys != setOf("sets", "reps")) invalid()
        val sets = value.getValue("sets").jsonPrimitive
        val reps = value.getValue("reps").jsonPrimitive
        val setCount = sets.intOrNull ?: invalid()
        val repCount = reps.intOrNull ?: invalid()
        if (sets.isString || reps.isString || setCount !in 1..20 || repCount !in 1..100) invalid()
        return buildJsonObject { put("sets", setCount); put("reps", repCount) }
    }

    private fun JsonObjectBuilder.totals(row: Map<String, String?>) {
        put("total_volume", decimal(row.text("total_volume")).toPlainString())
        put("total_sets", row.integer("total_sets"))
        put("total_reps", row.integer("total_reps"))
    }
    private fun JsonObjectBuilder.timestamps(row: Map<String, String?>) {
        put("created_at", timestampFormat.format(epoch(row.text("created_at"))))
        put("updated_at", timestampFormat.format(epoch(row.text("updated_at"))))
    }
    private fun uuid(raw: String): String {
        if (!uuidPattern.matches(raw)) invalid()
        return UUID.fromString(raw).toString()
    }
    private fun epoch(raw: String): Instant {
        if (!integerPattern.matches(raw)) invalid()
        return Instant.ofEpochMilli(raw.toLong()).also { if (it.atOffset(ZoneOffset.UTC).year !in 1..9999) invalid() }
    }
    private fun decimal(raw: String, max: BigDecimal? = null): BigDecimal {
        if (raw.length > 1000 || !decimalPattern.matches(raw)) invalid()
        val value = BigDecimal(raw).stripTrailingZeros()
        if (value.signum() < 0 || value.scale() !in -1000..1000 || value.precision() > 1000 || (max != null && value > max)) invalid()
        if (value.toPlainString().length > 1000) invalid()
        return value
    }
    private fun Map<String, String?>.text(key: String): String = this[key] ?: invalid()
    private fun Map<String, String?>.integer(key: String, min: Int = 0, max: Int = Int.MAX_VALUE): Int {
        val raw = text(key)
        if (!integerPattern.matches(raw)) invalid()
        return raw.toInt().also { if (it !in min..max) invalid() }
    }
    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
    private fun invalid(): Nothing = throw LegacyImportException(LegacyImportFailure.INVALID_SOURCE)
    private fun review(): Nothing = throw LegacyImportException(LegacyImportFailure.REVIEW_REQUIRED)
}

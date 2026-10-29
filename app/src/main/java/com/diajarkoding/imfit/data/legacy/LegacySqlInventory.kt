package com.diajarkoding.imfit.data.legacy

import kotlinx.serialization.json.*

/** Inspects an existing connection whose read stability is validated by the caller. No writes. */
internal class LegacySqlInventory(private val db: LegacySqlReader, private val maxRows: Int = 10_000,
    private val payload: (Map<LegacyKind, List<Map<String, String?>>>) -> Unit = {},
    private val dependencyPayload: (Map<String, List<Map<String, String?>>>) -> Unit = {}) {
    init { require(maxRows > 0) }
    private val json = Json
    private fun rows(sql: String, args: List<String> = emptyList()): List<Map<String, String?>> = buildList {
        db.rows(sql, args) {
            if (size >= maxRows) throw LegacyScanException(LegacyReadFailure.LIMIT_EXCEEDED)
            add(it)
        }
    }
    private fun scalar(sql: String, args: List<String> = emptyList()) = rows(sql, args).singleOrNull()?.values?.singleOrNull()

    fun inspect(owner: String): LegacyInventory.Present {
        require(owner.isNotBlank())
        val version = scalar("PRAGMA user_version")?.toIntOrNull()
            ?: throw LegacyScanException(LegacyReadFailure.CORRUPT)
        val expected = schemas[version] ?: throw LegacyScanException(LegacyReadFailure.UNSUPPORTED_SCHEMA)
        val objects = rows("SELECT name, type FROM sqlite_master WHERE type IN ('table','view','trigger') ORDER BY name")
        val tables = objects.filterNot { it["name"]!!.startsWith("sqlite_") || it["name"] in setOf("android_metadata", "room_master_table") }
        val names = baseTables + if (version == 9) listOf("template_write_intents") else emptyList()
        if (tables.any { it["type"] != "table" } || tables.map { it["name"] }.toSet() != names.toSet()) {
            throw LegacyScanException(LegacyReadFailure.SCHEMA_MISMATCH)
        }
        val schema = names.sorted().joinToString("") { name ->
            rows("PRAGMA table_info(`$name`)").joinToString("") { column ->
                "$name|${column["name"]}|${column["type"]!!.uppercase(java.util.Locale.ROOT)}|${column["notnull"]}|${column["dflt_value"] ?: "<null>"}|${column["pk"]}\n"
            }
        }
        if (fingerprint(schema) != expected.second || scalar("SELECT identity_hash FROM room_master_table WHERE id = 42") != expected.first) {
            throw LegacyScanException(LegacyReadFailure.SCHEMA_MISMATCH)
        }
        // Unowned orphans cannot safely be assigned to the signed-in account. No payload is read.
        val orphan = """SELECT
            (SELECT COUNT(*) FROM template_exercises c LEFT JOIN workout_templates p ON p.id=c.template_id WHERE p.id IS NULL) +
            (SELECT COUNT(*) FROM exercise_logs c LEFT JOIN workout_logs p ON p.id=c.workout_log_id WHERE p.id IS NULL) +
            (SELECT COUNT(*) FROM workout_sets c LEFT JOIN exercise_logs e ON e.id=c.exercise_log_id LEFT JOIN workout_logs w ON w.id=c.workout_log_id WHERE e.id IS NULL OR w.id IS NULL) AS n"""
        if (scalar(orphan)?.toLongOrNull() != 0L) throw LegacyScanException(LegacyReadFailure.INTEGRITY)
        val badChildren = """SELECT COUNT(*) AS n FROM workout_sets s
            JOIN exercise_logs e ON e.id=s.exercise_log_id JOIN workout_logs ew ON ew.id=e.workout_log_id
            JOIN workout_logs sw ON sw.id=s.workout_log_id WHERE (ew.user_id=? OR sw.user_id=?)
            AND (s.workout_log_id!=e.workout_log_id OR s.exercise_id!=e.exercise_id)"""
        if (scalar(badChildren, listOf(owner, owner))?.toLongOrNull() != 0L) throw LegacyScanException(LegacyReadFailure.INTEGRITY)

        val selected = linkedMapOf(
            LegacyKind.PROFILE to rows("SELECT * FROM users WHERE id=? ORDER BY id", listOf(owner)),
            LegacyKind.TEMPLATE to rows("SELECT * FROM workout_templates WHERE user_id=? ORDER BY id", listOf(owner)),
            LegacyKind.TEMPLATE_EXERCISE to rows("SELECT c.* FROM template_exercises c JOIN workout_templates p ON p.id=c.template_id WHERE p.user_id=? ORDER BY c.template_id,c.exercise_id", listOf(owner)),
            LegacyKind.WORKOUT to rows("SELECT * FROM workout_logs WHERE user_id=? ORDER BY id", listOf(owner)),
            LegacyKind.EXERCISE_LOG to rows("SELECT c.* FROM exercise_logs c JOIN workout_logs p ON p.id=c.workout_log_id WHERE p.user_id=? ORDER BY c.id", listOf(owner)),
            LegacyKind.SET to rows("SELECT c.* FROM workout_sets c JOIN workout_logs p ON p.id=c.workout_log_id WHERE p.user_id=? ORDER BY c.id", listOf(owner)),
            LegacyKind.ACTIVE_SESSION to rows("SELECT * FROM active_sessions WHERE user_id=? ORDER BY id", listOf(owner)),
            LegacyKind.TEMPLATE_INTENT to if (version == 9) rows("SELECT * FROM template_write_intents WHERE user_id=? ORDER BY `key`", listOf(owner)) else emptyList(),
        )
        if (selected.values.sumOf { it.size } > maxRows) throw LegacyScanException(LegacyReadFailure.LIMIT_EXCEEDED)
        val exerciseIds = linkedSetOf<String>()
        val templateIds = linkedSetOf<String>()
        val items = selected.flatMap { (kind, records) -> records.map { row ->
            val id = when (kind) {
                LegacyKind.TEMPLATE_EXERCISE -> "${row["template_id"]}:${row["exercise_id"]}"
                LegacyKind.TEMPLATE_INTENT -> row["key"]
                else -> row["id"]
            }?.takeIf { it.isNotBlank() } ?: throw LegacyScanException(LegacyReadFailure.CORRUPT)
            val parent = row["exercise_log_id"] ?: row["workout_log_id"] ?: row["template_id"]
            row["exercise_id"]?.let(exerciseIds::add)
            if (kind in setOf(LegacyKind.WORKOUT, LegacyKind.ACTIVE_SESSION, LegacyKind.TEMPLATE_INTENT)) row["template_id"]?.let(templateIds::add)
            var disposition = classify(row)
            var requestId: String? = null
            if (kind == LegacyKind.ACTIVE_SESSION) {
                disposition = LegacyDisposition.REVIEW
                try {
                    val raw = row["session_data_json"] ?: error("Missing draft")
                    require(raw.toByteArray().size <= 1_048_576)
                    val draft = json.parseToJsonElement(raw).jsonObject
                    require(draft.getValue("id").jsonPrimitive.content == id)
                    require(draft.getValue("templateId").jsonPrimitive.content == row["template_id"])
                    require(draft.getValue("startTime").jsonPrimitive.long == row["start_time"]?.toLongOrNull())
                    draft.getValue("exerciseLogs").jsonArray.forEach { exercise ->
                        exerciseIds += exercise.jsonObject.getValue("exercise").jsonObject.getValue("id").jsonPrimitive.content
                    }
                } catch (_: Exception) { disposition = LegacyDisposition.PROBLEM }
            }
            if (kind == LegacyKind.TEMPLATE_INTENT) {
                disposition = LegacyDisposition.REVIEW
                try {
                    val raw = row["payload"] ?: error("Missing intent")
                    require(raw.toByteArray().size <= 1_048_576)
                    val payload = json.parseToJsonElement(raw).jsonObject
                    require(payload.getValue("p_owner_id").jsonPrimitive.content == owner)
                    require(payload.getValue("p_template_id").jsonPrimitive.content == row["template_id"])
                    requestId = payload.getValue("p_request_id").jsonPrimitive.content.also { require(it.isNotBlank()) }
                    payload["p_exercises"]?.jsonArray?.forEach { exercise ->
                        exerciseIds += exercise.jsonObject.getValue("exercise_id").jsonPrimitive.content.also { require(it.isNotBlank()) }
                    }
                    payload["p_removed_exercise_ids"]?.jsonArray?.forEach { exercise ->
                        exerciseIds += exercise.jsonPrimitive.content.also { require(it.isNotBlank()) }
                    }
                } catch (_: Exception) { disposition = LegacyDisposition.PROBLEM }
            }
            LegacyItem(kind, id, disposition, legacyRowFingerprint(row), parent, requestId)
        } }
        if (exerciseIds.size + templateIds.size > maxRows) throw LegacyScanException(LegacyReadFailure.LIMIT_EXCEEDED)
        val templates = selected.getValue(LegacyKind.TEMPLATE)
        val templateExercises = selected.getValue(LegacyKind.TEMPLATE_EXERCISE)
        val exercises = buildList {
            exerciseIds.toList().chunked(400).forEach { ids ->
                addAll(rows("SELECT * FROM exercises WHERE id IN (${ids.joinToString { "?" }}) ORDER BY id", ids))
                if (size + selected.values.sumOf { it.size } > maxRows) throw LegacyScanException(LegacyReadFailure.LIMIT_EXCEEDED)
            }
        }
        val dependencyRows = linkedMapOf("TEMPLATE" to templates, "TEMPLATE_EXERCISE" to templateExercises, "EXERCISE" to exercises)
        val dependencies = dependencyRows.flatMap { (kind, records) ->
            records.map { row ->
                val id = if (kind == "TEMPLATE_EXERCISE") legacyRecordId(LegacyKind.TEMPLATE_EXERCISE, row) else row.getValue("id")!!
                LegacyDependency(kind, id, true, legacyRowFingerprint(row))
            }
        }.toMutableList()
        val ownerTemplateIds = templates.mapTo(hashSetOf()) { it["id"] ?: throw LegacyScanException(LegacyReadFailure.CORRUPT) }
        val availableExerciseIds = exercises.mapTo(hashSetOf()) { it["id"] ?: throw LegacyScanException(LegacyReadFailure.CORRUPT) }
        dependencies += (templateIds - ownerTemplateIds).map { LegacyDependency("TEMPLATE", it, false) }
        dependencies += (exerciseIds - availableExerciseIds).map { LegacyDependency("EXERCISE", it, false) }
        if (dependencies.size > maxRows) throw LegacyScanException(LegacyReadFailure.LIMIT_EXCEEDED)
        dependencies.sortWith(compareBy({ it.kind }, { it.id }))
        val evidence = "$version:${expected.first}:" + items.joinToString { "${it.kind}:${it.id}:${it.fingerprint}" } +
            dependencies.joinToString { "${it.kind}:${it.id}:${it.available}:${it.fingerprint}" }
        val result = LegacyInventory.Present(owner, version, fingerprint(evidence), items, dependencies)
        payload(selected)
        dependencyPayload(dependencyRows)
        return result
    }

    private fun classify(row: Map<String, String?>): LegacyDisposition = when {
        row["sync_status"] == "SYNCED" && row["pending_operation"] == null -> LegacyDisposition.SYNCED_CACHE_CANDIDATE
        row["pending_operation"] == "REVIEW" -> LegacyDisposition.REVIEW
        row["sync_status"] !in setOf("SYNCED", "PENDING_SYNC", "SYNC_FAILED") -> LegacyDisposition.PROBLEM
        row["pending_operation"] == "CREATE" -> LegacyDisposition.PENDING_CREATE
        row["pending_operation"] in setOf("UPDATE", "DELETE") -> LegacyDisposition.PENDING_CHANGE
        else -> LegacyDisposition.REVIEW
    }


    companion object {
        private val baseTables = listOf("users", "exercises", "workout_templates", "template_exercises", "workout_logs", "exercise_logs", "workout_sets", "active_sessions")
        // Exact exported Room snapshots; an unknown layout (including alternate v7) is review-blocked.
        private val schemas = mapOf(
            6 to ("ec3ccb35221c3ec47016785c28e0e4e1" to "957b2dc906829cb21aaf4cbba4c63675a609b675bc8e460d5382dd2ebca7db69"),
            7 to ("8414bab007f64723b32c05867e8eaccd" to "44ef94983c325cbb7757eb5f6e5527872b7a1cbec4572bd8c76b8f6ba34755e5"),
            9 to ("2d97f1665eb5aece003dbe23ebb77e38" to "cf9dcba3a35d5abd562068ba4982faaff2e9842f6deb9095f06deed2c2371fd5"),
        )
    }
}

package com.diajarkoding.imfit.data.legacy

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/** Actual inventory SQL against exported Room schemas on host SQLite. No migration or device proof. */
class LegacySqlInventoryTest {
    internal fun fixture(version: Int = 9, work: Fixture.() -> Unit) {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            val suffix = "schemas/com.diajarkoding.imfit.data.local.database.IMFITDatabase/$version.json"
            val schema = Json.parseToJsonElement(listOf(File(suffix), File("app/$suffix")).first { it.isFile }.readText())
                .jsonObject.getValue("database").jsonObject
            schema.getValue("entities").jsonArray.forEach { entry ->
                val entity = entry.jsonObject
                db.createStatement().use { it.execute(entity.getValue("createSql").jsonPrimitive.content
                    .replace("\${TABLE_NAME}", entity.getValue("tableName").jsonPrimitive.content)) }
            }
            db.createStatement().use {
                it.execute("PRAGMA user_version=$version")
                it.execute("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
                it.execute("INSERT INTO room_master_table VALUES(42,'${schema.getValue("identityHash").jsonPrimitive.content}')")
            }
            Fixture(db).work()
        }
    }
    internal class Fixture(val db: Connection) {
        val queries = mutableListOf<String>()
        val reader = LegacySqlReader { sql, args, consume ->
            assertTrue("Inventory is query-only", sql.trimStart().startsWith("SELECT") || sql.startsWith("PRAGMA"))
            queries += sql
            db.prepareStatement(sql).use { statement ->
                args.forEachIndexed { index, value -> statement.setString(index + 1, value) }
                statement.executeQuery().use { rows ->
                    while (rows.next()) consume((1..rows.metaData.columnCount).associate { rows.metaData.getColumnLabel(it) to rows.getString(it) })
                }
            }
        }
        fun insert(table: String, vararg values: Pair<String, Any?>) {
            val given = values.toMap()
            val fields = linkedMapOf<String, Any?>()
            db.createStatement().use { s -> s.executeQuery("PRAGMA table_info(`$table`)").use { rows ->
                while (rows.next()) {
                    val name = rows.getString("name")
                    fields[name] = if (name in given) given[name] else when {
                        name == "sync_status" -> "SYNCED"
                        name == "pending_operation" || rows.getInt("notnull") == 0 -> null
                        rows.getString("type") == "TEXT" -> "fixture-$name"
                        else -> 1
                    }
                }
            } }
            db.prepareStatement("INSERT INTO `$table` (${fields.keys.joinToString { "`$it`" }}) VALUES (${fields.keys.joinToString { "?" }})").use { s ->
                fields.values.forEachIndexed { i, value -> s.setObject(i + 1, value) }; s.executeUpdate()
            }
        }
        fun sql(sql: String) { db.createStatement().use { it.execute(sql) } }
        fun scan(owner: String = "A", cap: Int = 10_000) = LegacySqlInventory(reader, cap).inspect(owner)
        fun aggregate(owner: String, item: LegacyItem, evidence: String): LegacyAggregate {
            var records: Map<LegacyKind, List<Map<String, String?>>> = emptyMap()
            var dependencies: Map<String, List<Map<String, String?>>> = emptyMap()
            db.createStatement().use { it.execute("BEGIN DEFERRED") }
            try {
                val inventory = LegacySqlInventory(reader, payload = { records = it }, dependencyPayload = { dependencies = it }).inspect(owner)
                return inventory.selectedAggregate(item, evidence, records, dependencies)
            } finally {
                db.createStatement().use { it.execute("ROLLBACK") }
            }
        }
        fun log(id: String, owner: String) {
            insert("workout_logs", "id" to id, "user_id" to owner)
            insert("exercise_logs", "id" to "e-$id", "workout_log_id" to id, "exercise_id" to "bench")
            insert("workout_sets", "id" to "s-$id", "exercise_log_id" to "e-$id", "workout_log_id" to id, "exercise_id" to "bench")
        }
        fun expect(failure: LegacyReadFailure, run: () -> Unit) {
            try { run(); fail("Expected $failure") } catch (e: LegacyScanException) { assertEquals(failure, e.failure) }
        }
    }

    @Test fun `exported schema 6 7 and 9 are detected without migrations`() {
        for (version in listOf(6, 7, 9)) fixture(version) {
            assertEquals(version, scan().schemaVersion)
            assertTrue(scan().items.isEmpty())
            assertEquals(version, db.createStatement().use { s -> s.executeQuery("PRAGMA user_version").use { it.next(); it.getInt(1) } })
        }
    }

    @Test fun `owner inventory never returns another account IDs or payload`() = fixture {
        log("A-log", "A"); log("B-private", "B")
        insert("users", "id" to "A", "name" to "A name"); insert("users", "id" to "B", "name" to "B secret")
        val result = scan()
        assertEquals(setOf("A", "A-log", "e-A-log", "s-A-log"), result.items.map { it.id }.toSet())
        assertFalse(result.toString().contains("B-private")); assertFalse(result.toString().contains("B secret"))
        val before = result.fingerprint
        sql("UPDATE workout_logs SET template_name='changed B payload' WHERE user_id='B'")
        assertEquals(before, scan().fingerprint)
        assertTrue(scan("A' OR 1=1 --").items.isEmpty())
    }

    @Test fun `pending child remains pending with a synced parent and siblings`() = fixture {
        log("log", "A")
        sql("UPDATE workout_sets SET sync_status='SYNC_FAILED',pending_operation='UPDATE'")
        val result = scan()
        assertEquals(LegacyDisposition.SYNCED_CACHE_CANDIDATE, result.items.single { it.kind == LegacyKind.WORKOUT }.disposition)
        assertEquals(LegacyDisposition.PENDING_CHANGE, result.items.single { it.kind == LegacyKind.SET }.disposition)
    }

    @Test fun `synced flag alone with REVIEW or unknown operation is never treated as safe cache`() = fixture {
        insert("users", "id" to "A", "pending_operation" to "REVIEW")
        insert("workout_templates", "id" to "t", "user_id" to "A", "pending_operation" to "FUTURE")
        assertTrue(scan().items.all { it.disposition == LegacyDisposition.REVIEW })
    }

    @Test fun `active draft JSON is preserved and inventoried only for owner`() = fixture {
        val raw = """{"id":"active","templateId":"t","startTime":1,"exerciseLogs":[{"exercise":{"id":"bench"},"sets":[]}]}"""
        insert("active_sessions", "id" to "active", "user_id" to "A", "template_id" to "t", "session_data_json" to raw)
        insert("active_sessions", "id" to "B-active", "user_id" to "B", "session_data_json" to "private B")
        val item = scan().items.single()
        assertEquals(LegacyDisposition.REVIEW, item.disposition)
        assertEquals("active", item.id)
        assertFalse(item.toString().contains(raw))
        assertTrue(scan().dependencies.any { it.id == "bench" && !it.available })
        db.createStatement().use { s -> s.executeQuery("SELECT session_data_json FROM active_sessions WHERE user_id='A'").use { it.next(); assertEquals(raw, it.getString(1)) } }
    }

    @Test fun `malformed or mismatched active JSON is a problem not absent`() = fixture {
        insert("active_sessions", "id" to "active", "user_id" to "A", "session_data_json" to "{broken")
        assertEquals(LegacyDisposition.PROBLEM, scan().items.single().disposition)
        sql("UPDATE active_sessions SET session_data_json='{}'")
        assertEquals(LegacyDisposition.PROBLEM, scan().items.single().disposition)
    }

    @Test fun `template intent exposes only request identity and never acknowledges it`() = fixture {
        insert("template_write_intents", "key" to "intent", "user_id" to "A", "template_id" to "t",
            "payload" to """{"p_owner_id":"A","p_template_id":"t","p_request_id":"request","p_name":"Private name"}""")
        val item = scan().items.single()
        assertEquals("request", item.requestId); assertEquals(LegacyDisposition.REVIEW, item.disposition)
        assertFalse(item.toString().contains("Private name"))
        sql("UPDATE template_write_intents SET payload='{}'")
        assertEquals(LegacyDisposition.PROBLEM, scan().items.single().disposition)
    }

    @Test fun `missing provenance for pending exercise is retained as unknown without inference`() = fixture {
        log("log", "A")
        sql("UPDATE exercise_logs SET sync_status='PENDING_SYNC',pending_operation='CREATE'")
        assertEquals(LegacyDisposition.PENDING_CREATE, scan().items.single { it.kind == LegacyKind.EXERCISE_LOG }.disposition)
    }

    @Test fun `unsupported schema including 4 8 and zero fails closed`() = fixture {
        for (version in listOf(0, 4, 8, 10)) {
            sql("PRAGMA user_version=$version")
            expect(LegacyReadFailure.UNSUPPORTED_SCHEMA) { scan() }
        }
    }

    @Test fun `schema identity and exact columns are checked independently`() = fixture {
        sql("ALTER TABLE users ADD COLUMN unreviewed TEXT")
        expect(LegacyReadFailure.SCHEMA_MISMATCH) { scan() }
    }

    @Test fun `wrong Room identity hash blocks even when columns match`() = fixture {
        sql("UPDATE room_master_table SET identity_hash='unknown'")
        expect(LegacyReadFailure.SCHEMA_MISMATCH) { scan() }
    }

    @Test fun `unknown table or trigger is not silently ignored`() = fixture {
        sql("CREATE TABLE extension_private (id TEXT)")
        expect(LegacyReadFailure.SCHEMA_MISMATCH) { scan() }
    }

    @Test fun `alternate v7 without sync metadata is review blocked rather than guessed`() = fixture(7) {
        sql("ALTER TABLE users DROP COLUMN pending_operation")
        expect(LegacyReadFailure.SCHEMA_MISMATCH) { scan() }
    }

    @Test fun `unowned orphan blocks without trying to assign it to an account`() = fixture {
        insert("exercise_logs", "id" to "orphan", "workout_log_id" to "missing")
        expect(LegacyReadFailure.INTEGRITY) { scan() }
    }

    @Test fun `cross-owner set parentage is rejected before reading payload`() = fixture {
        log("A-log", "A"); log("B-log", "B")
        sql("UPDATE workout_sets SET exercise_log_id='e-B-log' WHERE workout_log_id='A-log'")
        expect(LegacyReadFailure.INTEGRITY) { scan() }
    }

    @Test fun `row cap fails closed instead of truncating inventory`() = fixture {
        repeat(41) { insert("workout_templates", "id" to "t-$it", "user_id" to "A") }
        expect(LegacyReadFailure.LIMIT_EXCEEDED) { scan(cap = 40) }
    }

    @Test fun `fingerprint detects owner payload changes but scan itself cannot write`() = fixture {
        log("log", "A")
        val old = scan().fingerprint
        sql("UPDATE workout_sets SET weight=65.5005")
        assertNotEquals(old, scan().fingerprint)
        sql("PRAGMA query_only=ON")
        assertEquals(scan().fingerprint, scan().fingerprint)
        assertTrue(queries.all { it.startsWith("SELECT") || it.startsWith("PRAGMA") })
    }
}

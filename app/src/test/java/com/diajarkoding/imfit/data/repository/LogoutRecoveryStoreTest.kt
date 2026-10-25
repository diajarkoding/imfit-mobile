package com.diajarkoding.imfit.data.repository

import androidx.sqlite.db.SupportSQLiteDatabase
import com.diajarkoding.imfit.data.local.database.LogoutRecoveryStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/** Actual retention SQL on synthetic host SQLite, never a user's device database. */
class LogoutRecoveryStoreTest {
    private fun fixture(test: Fixture.() -> Unit) {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            val suffix = "schemas/com.diajarkoding.imfit.data.local.database.IMFITDatabase/9.json"
            val schema = Json.parseToJsonElement(listOf(File(suffix), File("app/$suffix")).first { it.isFile }.readText())
                .jsonObject.getValue("database").jsonObject.getValue("entities").jsonArray
            schema.forEach { entry ->
                val entity = entry.jsonObject
                connection.createStatement().use { it.execute(entity.getValue("createSql").jsonPrimitive.content
                    .replace("\${TABLE_NAME}", entity.getValue("tableName").jsonPrimitive.content)) }
            }
            val db = mockk<SupportSQLiteDatabase>()
            every { db.execSQL(any(), any()) } answers {
                connection.prepareStatement(firstArg()).use { statement ->
                    secondArg<Array<Any?>>().forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                    statement.executeUpdate()
                }; Unit
            }
            connection.autoCommit = false
            Fixture(connection, db).test()
            connection.rollback()
        }
    }

    private class Fixture(val connection: Connection, val db: SupportSQLiteDatabase) {
        fun insert(table: String, vararg values: Pair<String, Any?>) {
            val given = values.toMap()
            val fields = linkedMapOf<String, Any?>()
            connection.createStatement().use { statement -> statement.executeQuery("PRAGMA table_info(`$table`)").use { rows ->
                while (rows.next()) {
                    val name = rows.getString("name")
                    fields[name] = if (name in given) given[name] else when {
                        name == "sync_status" -> "SYNCED"
                        name == "pending_operation" -> null
                        rows.getInt("notnull") == 0 -> null
                        rows.getString("type") == "TEXT" -> "fixture-$name"
                        else -> 1
                    }
                }
            } }
            connection.prepareStatement("INSERT INTO `$table` (${fields.keys.joinToString { "`$it`" }}) VALUES (${fields.keys.joinToString { "?" }})").use { statement ->
                fields.values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
                statement.executeUpdate()
            }
        }
        fun ids(table: String, column: String = "id"): Set<String> = connection.createStatement().use { statement ->
            statement.executeQuery("SELECT `$column` FROM `$table`").use { rows -> buildSet { while (rows.next()) add(rows.getString(1)) } }
        }
        fun log(id: String, owner: String, pending: Boolean = false, template: String? = null) {
            insert("workout_logs", "id" to id, "user_id" to owner, "template_id" to template,
                "sync_status" to if (pending) "PENDING_SYNC" else "SYNCED", "pending_operation" to if (pending) "CREATE" else null)
            insert("exercise_logs", "id" to "exercise-$id", "workout_log_id" to id)
            insert("workout_sets", "id" to "set-$id", "workout_log_id" to id, "exercise_log_id" to "exercise-$id")
        }
    }

    @Test fun logoutPurgesOnlyOwnersSyncedCacheAndKeepsOtherAccountsUntouched() = fixture {
        log("synced-A", "A"); log("pending-A", "A", true); log("synced-B", "B"); log("pending-B", "B", true)
        insert("users", "id" to "A"); insert("users", "id" to "B")
        LogoutRecoveryStore.pruneSyncedCache(db, "A")
        assertEquals(setOf("pending-A", "synced-B", "pending-B"), ids("workout_logs"))
        assertEquals(setOf("exercise-pending-A", "exercise-synced-B", "exercise-pending-B"), ids("exercise_logs"))
        assertEquals(setOf("set-pending-A", "set-synced-B", "set-pending-B"), ids("workout_sets"))
        assertEquals(setOf("B"), ids("users"))
    }

    @Test fun pendingLogKeepsItsTemplateAndAllTemplateChildren() = fixture {
        insert("workout_templates", "id" to "needed", "user_id" to "A")
        insert("template_exercises", "template_id" to "needed")
        insert("workout_templates", "id" to "cache", "user_id" to "A")
        insert("template_exercises", "template_id" to "cache")
        log("pending", "A", true, "needed")
        LogoutRecoveryStore.pruneSyncedCache(db, "A")
        assertEquals(setOf("needed"), ids("workout_templates"))
        assertEquals(setOf("needed"), ids("template_exercises", "template_id"))
        assertEquals(setOf("pending"), ids("workout_logs"))
    }

    @Test fun pendingChildProtectsSyncedParentAndEverySibling() = fixture {
        log("child-pending", "A")
        connection.createStatement().use { it.executeUpdate("UPDATE workout_sets SET pending_operation='UPDATE' WHERE workout_log_id='child-pending'") }
        insert("workout_templates", "id" to "child-template", "user_id" to "A")
        insert("template_exercises", "template_id" to "child-template", "pending_operation" to "UPDATE")
        LogoutRecoveryStore.pruneSyncedCache(db, "A")
        assertEquals(setOf("child-pending"), ids("workout_logs"))
        assertEquals(setOf("exercise-child-pending"), ids("exercise_logs"))
        assertEquals(setOf("child-template"), ids("workout_templates"))
    }

    @Test fun unverifiedLegacyRowsAndPendingProfileAreNeverTreatedAsDisposableCache() = fixture {
        log("review", "A"); log("unknown", "A")
        connection.createStatement().use {
            it.executeUpdate("UPDATE workout_logs SET sync_status='SYNC_FAILED',pending_operation='REVIEW' WHERE id='review'")
            it.executeUpdate("UPDATE workout_logs SET sync_status='PENDING_SYNC' WHERE id='unknown'")
        }
        insert("users", "id" to "A", "sync_status" to "PENDING_SYNC")
        LogoutRecoveryStore.pruneSyncedCache(db, "A")
        assertEquals(setOf("review", "unknown"), ids("workout_logs"))
        assertEquals(setOf("A"), ids("users"))
    }

    @Test fun activeDraftKeepsTemplateAndCatalogAndLogoutCanBeRepeated() = fixture {
        insert("workout_templates", "id" to "draft-template", "user_id" to "A")
        insert("active_sessions", "id" to "draft", "user_id" to "A", "template_id" to "draft-template", "session_data_json" to "original-draft")
        insert("exercises", "id" to "catalog")
        repeat(2) { LogoutRecoveryStore.pruneSyncedCache(db, "A") }
        assertEquals(setOf("draft"), ids("active_sessions"))
        assertEquals(setOf("draft-template"), ids("workout_templates"))
        assertEquals(setOf("catalog"), ids("exercises"))
    }

    @Test fun failedCleanupCanRollBackWithoutLosingAnyRecoveryOrCacheRows() = fixture {
        log("synced", "A"); log("pending", "A", true)
        connection.createStatement().use { it.execute("CREATE TRIGGER reject_cleanup BEFORE DELETE ON workout_logs BEGIN SELECT RAISE(ABORT, 'fixture failure'); END") }
        connection.commit()
        try {
            LogoutRecoveryStore.pruneSyncedCache(db, "A")
            fail("Fixture must fail midway through cleanup")
        } catch (_: java.sql.SQLException) { connection.rollback() }
        assertEquals(setOf("synced", "pending"), ids("workout_logs"))
        assertEquals(setOf("exercise-synced", "exercise-pending"), ids("exercise_logs"))
        assertEquals(setOf("set-synced", "set-pending"), ids("workout_sets"))
    }

    @Test fun ambiguousTemplateRequestAndItsCachedDependenciesSurviveOwnerLogout() = fixture {
        insert("workout_templates", "id" to "saving", "user_id" to "A")
        insert("template_exercises", "template_id" to "saving")
        insert("template_write_intents", "key" to "request-A", "user_id" to "A", "template_id" to "saving", "payload" to "original-payload")
        insert("template_write_intents", "key" to "request-B", "user_id" to "B", "template_id" to "other", "payload" to "foreign-payload")
        LogoutRecoveryStore.pruneSyncedCache(db, "A")
        assertEquals(setOf("saving"), ids("workout_templates"))
        assertEquals(setOf("saving"), ids("template_exercises", "template_id"))
        assertEquals(setOf("request-A", "request-B"), ids("template_write_intents", "key"))
    }
}

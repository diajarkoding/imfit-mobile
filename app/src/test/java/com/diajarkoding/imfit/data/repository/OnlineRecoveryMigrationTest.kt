package com.diajarkoding.imfit.data.repository

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import com.diajarkoding.imfit.data.local.database.OnlineRecoveryMigration
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager

/** Executes the actual Migration's SQL against host SQLite. Not Android Room validation/device proof. */
class OnlineRecoveryMigrationTest {
    private fun scenario(online: Boolean, work: (Connection, SupportSQLiteDatabase) -> Unit) {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            val suffix = "schemas/com.diajarkoding.imfit.data.local.database.IMFITDatabase/7.json"
            val file = listOf(File(suffix), File("app/$suffix")).first { it.isFile }
            val schema = Json.parseToJsonElement(file.readText()).jsonObject.getValue("database").jsonObject.getValue("entities").jsonArray
            schema.forEach { entry ->
                val entity = entry.jsonObject
                val name = entity.getValue("tableName").jsonPrimitive.content
                var ddl = entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", name)
                if (online) {
                    ddl = ddl.replace("`sync_status` TEXT NOT NULL, ", "").replace("`pending_operation` TEXT, ", "")
                        .replace("`input_defaults` TEXT, ", "")
                        .replace("`created_at` INTEGER NOT NULL", "`created_at` INTEGER")
                        .replace("`updated_at` INTEGER NOT NULL", "`updated_at` INTEGER")
                }
                connection.createStatement().use { it.execute(ddl) }
                val columns = mutableListOf<Pair<String, String>>()
                connection.createStatement().use { statement -> statement.executeQuery("PRAGMA table_info(`$name`)").use { rows ->
                    while (rows.next()) columns += rows.getString("name") to rows.getString("type")
                } }
                val sql = "INSERT INTO `$name` (${columns.joinToString { "`${it.first}`" }}) VALUES (${columns.joinToString { "?" }})"
                connection.prepareStatement(sql).use { insert ->
                    columns.forEachIndexed { index, (column, type) ->
                        val value: Any? = when {
                            online && column in setOf("created_at", "updated_at") -> null
                            column == "pending_operation" -> "CREATE"
                            column == "sync_status" -> "PENDING_SYNC"
                            column == "session_data_json" -> "{\"id\":\"active-original\",\"isPaused\":true}"
                            column == "input_defaults" -> "trusted-original"
                            type == "TEXT" -> "original-$column"
                            else -> 17
                        }
                        insert.setObject(index + 1, value)
                    }
                    insert.executeUpdate()
                }
            }
            val database = Proxy.newProxyInstance(SupportSQLiteDatabase::class.java.classLoader,
                arrayOf(SupportSQLiteDatabase::class.java)) { _, method, args ->
                when (method.name) {
                    "execSQL" -> { connection.createStatement().use { it.execute(args!![0] as String) }; null }
                    "query" -> {
                        val statement = connection.createStatement()
                        val rows = statement.executeQuery(args!![0] as String)
                        Proxy.newProxyInstance(Cursor::class.java.classLoader, arrayOf(Cursor::class.java)) { _, call, values ->
                            when (call.name) {
                                "moveToNext", "moveToFirst" -> rows.next()
                                "getColumnIndexOrThrow" -> (1..rows.metaData.columnCount).first { rows.metaData.getColumnLabel(it).equals(values!![0] as String, true) } - 1
                                "getString" -> rows.getString((values!![0] as Int) + 1)
                                "getInt" -> rows.getInt((values!![0] as Int) + 1)
                                "getLong" -> rows.getLong((values!![0] as Int) + 1)
                                "close" -> { rows.close(); statement.close(); null }
                                else -> error("Unexpected Cursor call: ${call.name}")
                            }
                        }
                    }
                    else -> error("Unexpected DB call: ${method.name}")
                }
            } as SupportSQLiteDatabase
            connection.autoCommit = false
            try { work(connection, database) } finally { connection.rollback() }
        }
    }

    @Test fun development7RetainsPendingRowsAndActiveSession() = scenario(false) { connection, db ->
        OnlineRecoveryMigration.migrate(db)
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT pending_operation, sync_status FROM workout_logs").use { rows ->
                assertTrue(rows.next()); assertEquals("CREATE", rows.getString(1)); assertEquals("PENDING_SYNC", rows.getString(2))
            }
            statement.executeQuery("SELECT input_defaults FROM exercise_logs").use { rows ->
                assertTrue(rows.next()); assertEquals("trusted-original", rows.getString(1))
            }
            statement.executeQuery("SELECT session_data_json FROM active_sessions").use { rows ->
                assertTrue(rows.next()); assertEquals("{\"id\":\"active-original\",\"isPaused\":true}", rows.getString(1))
            }
        }
    }

    @Test fun online7WithoutReceiptsIsNotInventedAsSyncedAndNullableTimesNormalize() = scenario(true) { connection, db ->
        OnlineRecoveryMigration.migrate(db)
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT pending_operation, sync_status, created_at, updated_at FROM workout_logs").use { rows ->
                assertTrue(rows.next()); assertEquals("REVIEW", rows.getString(1)); assertEquals("SYNC_FAILED", rows.getString(2))
                assertEquals(0L, rows.getLong(3)); assertEquals(0L, rows.getLong(4))
            }
            statement.executeQuery("SELECT COUNT(*) FROM active_sessions").use { rows -> assertTrue(rows.next()); assertEquals(1, rows.getInt(1)) }
        }
    }

    @Test fun unknownHistoricalColumnStopsMigrationAndTransactionCanRollBack() = scenario(false) { connection, db ->
        connection.createStatement().use { it.execute("ALTER TABLE workout_logs ADD COLUMN preserved_extension TEXT") }
        connection.commit()
        try { OnlineRecoveryMigration.migrate(db); fail("Unknown recovery data must not be dropped") }
        catch (_: IllegalStateException) { connection.rollback() }
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*), pending_operation FROM workout_logs").use { rows ->
                assertTrue(rows.next()); assertEquals(1, rows.getInt(1)); assertEquals("CREATE", rows.getString(2))
            }
        }
    }
}

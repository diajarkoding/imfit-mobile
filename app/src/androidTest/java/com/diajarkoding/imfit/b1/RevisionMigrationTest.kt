package com.diajarkoding.imfit.b1

import android.content.ContentValues
import android.database.Cursor
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.diajarkoding.imfit.data.local.database.IMFITDatabase
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RevisionMigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext.also {
        check(it.packageName in setOf("com.diajarkoding.imfit.demo.modaldiagnostic", "com.diajarkoding.imfit.modaldiagnostic"))
        check(it.applicationContext.javaClass == android.app.Application::class.java)
    }
    @get:Rule val helper = MigrationTestHelper(instrumentation, IMFITDatabase::class.java)
    private val names = mutableListOf<String>()
    private val migrations get() = arrayOf(IMFITDatabase.MIGRATION_4_5, IMFITDatabase.MIGRATION_5_6,
        IMFITDatabase.MIGRATION_6_7, IMFITDatabase.MIGRATION_7_8)

    @After fun cleanup() { names.forEach { context.deleteDatabase(it) } }

    @Test fun sevenToEightPreservesEveryExistingColumnAndPendingOperation() = migrate(7)
    @Test fun sixToEightPreservesRowsAndInitializesProvenanceToNull() = migrate(6)

    // No exported schema4/5 exists. Reconstruct only the reverse of the known additive migrations;
    // validate the result against exported schema8. This is not evidence about an unknown older APK.
    @Test fun reconstructedFourToEightChainPreservesActiveSessionAndAggregates() = migrate(4)
    @Test fun reconstructedFiveToEightChainPreservesActiveSessionAndAggregates() = migrate(5)

    private fun migrate(from: Int) {
        val name = "b1-migration-${UUID.randomUUID()}.db".also(names::add)
        val old = if (from >= 6) helper.createDatabase(name, from) else reconstructed(name, from)
        val tables = listOf("users", "exercises", "workout_templates", "template_exercises", "workout_logs",
            "exercise_logs", "workout_sets", "active_sessions")
        val before = old.use { db ->
            tables.forEach { table ->
                listOf("CREATE", "UPDATE", "DELETE").forEachIndexed { i, operation -> seed(db, table, i, operation) }
            }
            tables.associateWith { rows(db, it) }
        }
        helper.runMigrationsAndValidate(name, 8, true, *migrations).use { db ->
            tables.forEach { table ->
                val actual = rows(db, table)
                assertEquals(table, before[table]!!.size, actual.size)
                before[table]!!.zip(actual).forEach { (expected, row) ->
                    expected.forEach { (column, value) -> assertEquals("$table.$column", value, row[column]) }
                }
            }
            db.query("SELECT COUNT(*), MIN(revision), MAX(revision) FROM aggregate_revisions WHERE aggregate_type != 'clock'").use {
                assertTrue(it.moveToFirst())
                assertEquals(6L, it.getLong(0))
                assertEquals(1L, it.getLong(1))
                assertEquals(1L, it.getLong(2))
            }
            db.query("SELECT COUNT(*) FROM sqlite_master WHERE type = 'trigger' AND name LIKE 'revision_%'").use {
                assertTrue(it.moveToFirst()); assertEquals(23, it.getInt(0))
            }
            if (from < 7) db.query("SELECT input_defaults FROM exercise_logs").use {
                while (it.moveToNext()) assertTrue(it.isNull(0))
            }
        }
        // A real Room open after validation checks identity/schema and persisted trigger behavior.
        val reopened = Room.databaseBuilder(context, IMFITDatabase::class.java, name)
            .addMigrations(*migrations).addCallback(IMFITDatabase.REVISION_CALLBACK).build()
        try {
            runBlocking {
                assertEquals(1L, reopened.aggregateRevisionDao().revision("owner-0", "workout", "workout-0"))
                val row = reopened.workoutLogDao().getWorkoutLogById("workout-0")!!
                reopened.workoutLogDao().updateWorkoutLog(row.copy(templateName = "after reopen"))
                assertTrue(reopened.aggregateRevisionDao().revision("owner-0", "workout", "workout-0")!! > 1L)
                assertEquals("CREATE", reopened.workoutLogDao().getWorkoutLogById("workout-0")!!.pendingOperation)
            }
        } finally { reopened.close() }
    }

    private fun seed(db: SupportSQLiteDatabase, table: String, index: Int, operation: String) {
        val values = ContentValues()
        db.query("PRAGMA table_info($table)").use { columns ->
            while (columns.moveToNext()) {
                val name = columns.getString(columns.getColumnIndexOrThrow("name"))
                val type = columns.getString(columns.getColumnIndexOrThrow("type"))
                when (type) {
                    "INTEGER" -> values.put(name, 1234L + index)
                    "REAL" -> values.put(name, 42.5 + index)
                    else -> values.put(name, "$table-$name-$index")
                }
            }
        }
        val id = when (table) {
            "workout_logs" -> "workout-$index"
            "workout_templates" -> "template-$index"
            "exercise_logs" -> "log-$index"
            else -> "$table-$index"
        }
        if (values.containsKey("id")) values.put("id", id)
        if (values.containsKey("user_id")) values.put("user_id", "owner-$index")
        if (values.containsKey("template_id")) values.put("template_id", "template-$index")
        if (values.containsKey("workout_log_id")) values.put("workout_log_id", "workout-$index")
        if (values.containsKey("exercise_log_id")) values.put("exercise_log_id", "log-$index")
        if (values.containsKey("exercise_id")) values.put("exercise_id", "exercises-$index")
        if (values.containsKey("sync_status")) values.put("sync_status", "PENDING_SYNC")
        if (values.containsKey("pending_operation")) values.put("pending_operation", operation)
        db.insert(table, android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT, values)
    }

    private fun rows(db: SupportSQLiteDatabase, table: String): List<Map<String, String?>> =
        db.query("SELECT * FROM $table ORDER BY rowid").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.columnNames.associateWith { name ->
                    val i = cursor.getColumnIndexOrThrow(name)
                    when (cursor.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> null
                        else -> cursor.getString(i)
                    }
                })
            }
        }

    private fun reconstructed(name: String, version: Int): SupportSQLiteDatabase {
        val schema = JSONObject(instrumentation.context.assets.open(
            "com.diajarkoding.imfit.data.local.database.IMFITDatabase/6.json").bufferedReader().use { it.readText() })
            .getJSONObject("database").getJSONArray("entities")
        val omitted = if (version == 4) setOf("is_paused", "total_paused_time_ms", "last_pause_time",
            "rest_timer_end_time", "rest_timer_exercise_name", "session_rest_override")
        else setOf("rest_timer_end_time", "rest_timer_exercise_name", "session_rest_override")
        val sqlite = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name).callback(object : SupportSQLiteOpenHelper.Callback(version) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    for (i in 0 until schema.length()) {
                        val entity = schema.getJSONObject(i)
                        val table = entity.getString("tableName")
                        var sql = entity.getString("createSql").replace("\${TABLE_NAME}", table)
                        if (table == "active_sessions") omitted.forEach { column ->
                            sql = sql.replace(Regex("`$column` [^,]+, "), "")
                        }
                        db.execSQL(sql)
                        val indices = entity.optJSONArray("indices")
                        if (indices != null) for (j in 0 until indices.length()) {
                            db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                        }
                    }
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unexpected upgrade")
            }).build())
        // Close the helper through the returned database's use{} before migration; no app DB involved.
        return sqlite.writableDatabase
    }
}

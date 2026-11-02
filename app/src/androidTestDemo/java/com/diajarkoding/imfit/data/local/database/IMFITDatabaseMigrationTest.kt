package com.diajarkoding.imfit.data.local.database

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IMFITDatabaseMigrationTest {
    @get:Rule val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), IMFITDatabase::class.java)
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val databaseName = "migration-test.db"

    @After
    fun cleanUp() {
        context.deleteDatabase(databaseName)
    }

    @Test fun fullRoomSchemaSixToSevenIsValidatedAndRetainsHistory() {
        migrations.createDatabase(databaseName, 6).use { db ->
            db.execSQL("INSERT INTO exercise_logs (id, workout_log_id, exercise_id, exercise_name, muscle_category, order_index, total_volume, total_sets, total_reps, sync_status, created_at, updated_at) VALUES ('legacy','workout','exercise','Bench','CHEST',0,80,1,8,'SYNCED',1,1)")
        }
        migrations.runMigrationsAndValidate(databaseName, 7, true, IMFITDatabase.MIGRATION_6_7).use { db ->
            db.query("SELECT exercise_name, total_volume, input_defaults FROM exercise_logs WHERE id = 'legacy'").use {
                assertTrue(it.moveToFirst())
                assertEquals("Bench", it.getString(0))
                assertEquals(80f, it.getFloat(1))
                assertTrue(it.isNull(2))
            }
        }
    }

    @Test fun fullRoomSchemaSevenToEightIsValidatedAndRetainsHistory() {
        migrations.createDatabase(databaseName, 7).use { db ->
            db.execSQL("INSERT INTO exercises (id, name, muscle_category_id, description, is_active, created_at, updated_at) VALUES ('ex_1', 'Bench', 1, 'Chest press', 1, 100, 100)")
            db.execSQL("INSERT INTO workout_templates (id, user_id, name, is_deleted, sync_status, created_at, updated_at) VALUES ('tmpl_1', 'user_1', 'Chest Day', 0, 'SYNCED', 100, 100)")
            db.execSQL("INSERT INTO template_exercises (template_id, exercise_id, order_index, sets, reps, rest_seconds, sync_status, created_at, updated_at) VALUES ('tmpl_1', 'ex_1', 0, 3, 10, 60, 'SYNCED', 100, 100)")
            db.execSQL("INSERT INTO workout_logs (id, user_id, template_name, date, start_time, end_time, total_volume, total_sets, total_reps, sync_status, created_at, updated_at) VALUES ('wkt_1', 'user_1', 'Chest Day', 100, 100, 200, 300, 3, 30, 'SYNCED', 100, 200)")
            db.execSQL("INSERT INTO exercise_logs (id, workout_log_id, exercise_id, exercise_name, muscle_category, order_index, total_volume, total_sets, total_reps, sync_status, created_at, updated_at, input_defaults) VALUES ('elog_1', 'wkt_1', 'ex_1', 'Bench', 'CHEST', 0, 300, 3, 30, 'SYNCED', 100, 200, '3:10')")
            db.execSQL("INSERT INTO workout_sets (id, exercise_log_id, workout_log_id, exercise_id, set_number, weight, reps, is_completed, sync_status, created_at, updated_at) VALUES ('set_1', 'elog_1', 'wkt_1', 'ex_1', 1, 10.0, 10, 1, 'SYNCED', 100, 200)")
            db.execSQL("INSERT INTO workout_logs (id, user_id, template_name, date, start_time, end_time, total_volume, total_sets, total_reps, sync_status, pending_operation, created_at, updated_at) VALUES ('pending', 'user_1', 'Pending', 100, 100, 200, 80, 1, 8, 'PENDING_SYNC', 'CREATE', 100, 200)")
            db.execSQL("INSERT INTO exercise_logs (id, workout_log_id, exercise_id, exercise_name, muscle_category, order_index, total_volume, total_sets, total_reps, sync_status, pending_operation, created_at, updated_at) VALUES ('pending-log', 'pending', 'ex_1', 'Bench', 'CHEST', 0, 80, 1, 8, 'PENDING_SYNC', 'CREATE', 100, 200)")
            db.execSQL("INSERT INTO workout_sets (id, exercise_log_id, workout_log_id, exercise_id, set_number, weight, reps, is_completed, sync_status, pending_operation, created_at, updated_at) VALUES ('pending-set', 'pending-log', 'pending', 'ex_1', 1, 10.0, 8, 1, 'PENDING_SYNC', 'CREATE', 100, 200)")
            db.execSQL("INSERT INTO active_sessions (id, user_id, template_id, template_name, start_time, current_exercise_index, session_data_json, created_at, updated_at, is_paused, total_paused_time_ms, last_pause_time, rest_timer_end_time, rest_timer_exercise_name, session_rest_override) VALUES ('active-v1', 'user_1', 'tmpl_1', 'Chest Day', 100, 0, ?, 100, 200, 1, 3000, 150, 5000, 'Bench', 90)",
                arrayOf("""{"id":"active-v1","templateId":"tmpl_1","templateName":"Chest Day","startTime":100,"exerciseLogs":[{"exercise":{"id":"ex_1","name":"Bench","muscleCategory":"CHEST","description":""},"sets":[{"setNumber":1,"weight":10.0,"reps":8,"isCompleted":true}]}]}"""))
        }

        migrations.runMigrationsAndValidate(databaseName, 8, true, IMFITDatabase.MIGRATION_7_8).use { db ->
            // Verify template_exercises
            db.query("SELECT tracking_type, sets, reps, target_duration_seconds FROM template_exercises WHERE template_id = 'tmpl_1'").use {
                assertTrue(it.moveToFirst())
                assertEquals("WEIGHT_REPS", it.getString(0))
                assertEquals(3, it.getInt(1))
                assertEquals(10, it.getInt(2))
                assertTrue(it.isNull(3))
            }
            // Verify workout_sets
            db.query("SELECT weight, reps, duration_seconds, additional_weight FROM workout_sets WHERE id = 'set_1'").use {
                assertTrue(it.moveToFirst())
                assertEquals(10f, it.getFloat(0))
                assertEquals(10, it.getInt(1))
                assertTrue(it.isNull(2))
                assertTrue(it.isNull(3))
            }
            // Verify exercise_logs
            db.query("SELECT tracking_type, modality, metric_quality, total_volume FROM exercise_logs WHERE id = 'elog_1'").use {
                assertTrue(it.moveToFirst())
                assertEquals("WEIGHT_REPS", it.getString(0))
                assertEquals("STRENGTH", it.getString(1))
                assertEquals("LEGACY_UNVERIFIED", it.getString(2))
                assertEquals(300f, it.getFloat(3))
            }
            // Verify workout_logs
            db.query("SELECT data_quality, total_volume, total_duration_seconds FROM workout_logs WHERE id = 'wkt_1'").use {
                assertTrue(it.moveToFirst())
                assertEquals("LEGACY_UNVERIFIED", it.getString(0))
                assertEquals(300f, it.getFloat(1))
                assertEquals(0, it.getInt(2))
            }
            // Verify exercises
            db.query("SELECT default_tracking_type, modality FROM exercises WHERE id = 'ex_1'").use {
                assertTrue(it.moveToFirst())
                assertEquals("WEIGHT_REPS", it.getString(0))
                assertEquals("STRENGTH", it.getString(1))
            }
            db.query("SELECT w.id, w.sync_status, w.pending_operation, w.data_quality, e.id, e.sync_status, e.pending_operation, s.id, s.sync_status, s.pending_operation, s.weight, s.reps FROM workout_logs w JOIN exercise_logs e ON e.workout_log_id = w.id JOIN workout_sets s ON s.exercise_log_id = e.id AND s.workout_log_id = w.id WHERE w.id = 'pending'").use {
                assertTrue(it.moveToFirst())
                assertEquals("pending", it.getString(0))
                assertEquals("PENDING_SYNC", it.getString(1))
                assertEquals("CREATE", it.getString(2))
                assertEquals("LEGACY_UNVERIFIED", it.getString(3))
                assertEquals("pending-log", it.getString(4))
                assertEquals("PENDING_SYNC", it.getString(5))
                assertEquals("CREATE", it.getString(6))
                assertEquals("pending-set", it.getString(7))
                assertEquals("PENDING_SYNC", it.getString(8))
                assertEquals("CREATE", it.getString(9))
                assertEquals(10f, it.getFloat(10))
                assertEquals(8, it.getInt(11))
            }
            db.query("SELECT session_data_json, is_paused, total_paused_time_ms, last_pause_time, rest_timer_end_time, rest_timer_exercise_name, session_rest_override FROM active_sessions WHERE id = 'active-v1' AND user_id = 'user_1'").use {
                assertTrue(it.moveToFirst())
                val snapshot = org.json.JSONObject(it.getString(0))
                assertEquals("active-v1", snapshot.getString("id"))
                assertEquals(8, snapshot.getJSONArray("exerciseLogs").getJSONObject(0)
                    .getJSONArray("sets").getJSONObject(0).getInt("reps"))
                assertEquals(1, it.getInt(1))
                assertEquals(3000L, it.getLong(2))
                assertEquals(150L, it.getLong(3))
                assertEquals(5000L, it.getLong(4))
                assertEquals("Bench", it.getString(5))
                assertEquals(90, it.getInt(6))
            }
        }
        migrations.runMigrationsAndValidate(databaseName, 9, true, IMFITDatabase.MIGRATION_8_9).use { db ->
            db.query("SELECT sync_status, pending_operation, total_reps FROM workout_logs WHERE id = 'pending'").use {
                assertTrue(it.moveToFirst())
                assertEquals("PENDING_SYNC", it.getString(0))
                assertEquals("CREATE", it.getString(1))
                assertEquals(8, it.getInt(2))
            }
            db.query("SELECT session_data_json, session_rest_override FROM active_sessions WHERE id = 'active-v1'").use {
                assertTrue(it.moveToFirst())
                assertTrue(it.getString(0).contains("active-v1"))
                assertEquals(90, it.getInt(1))
            }
        }
    }

    @Test fun fullRoomSchemaSixToEightChainedMigrationPasses() {
        migrations.createDatabase(databaseName, 6).use { db ->
            db.execSQL("INSERT INTO exercise_logs (id, workout_log_id, exercise_id, exercise_name, muscle_category, order_index, total_volume, total_sets, total_reps, sync_status, created_at, updated_at) VALUES ('legacy','workout','exercise','Bench','CHEST',0,80,1,8,'SYNCED',1,1)")
        }
        migrations.runMigrationsAndValidate(databaseName, 8, true, IMFITDatabase.MIGRATION_6_7, IMFITDatabase.MIGRATION_7_8).use { db ->
            db.query("SELECT exercise_name, total_volume, input_defaults, tracking_type, metric_quality FROM exercise_logs WHERE id = 'legacy'").use {
                assertTrue(it.moveToFirst())
                assertEquals("Bench", it.getString(0))
                assertEquals(80f, it.getFloat(1))
                assertTrue(it.isNull(2))
                assertEquals("WEIGHT_REPS", it.getString(3))
                assertEquals("LEGACY_UNVERIFIED", it.getString(4))
            }
        }
    }

    @Test
    fun activeSessionMigrationsPreserveRowAndAddExpectedColumns() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(4) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE active_sessions (
                                id TEXT NOT NULL PRIMARY KEY,
                                user_id TEXT NOT NULL,
                                template_id TEXT NOT NULL,
                                template_name TEXT NOT NULL,
                                start_time INTEGER NOT NULL,
                                current_exercise_index INTEGER NOT NULL,
                                session_data_json TEXT NOT NULL,
                                created_at INTEGER NOT NULL,
                                updated_at INTEGER NOT NULL
                            )
                            """.trimIndent()
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )

        helper.writableDatabase.use { db ->
            db.execSQL(
                "INSERT INTO active_sessions VALUES " +
                    "('session-1','user-1','template-1','Push',1,0,'{}',1,1)"
            )

            IMFITDatabase.MIGRATION_4_5.migrate(db)
            IMFITDatabase.MIGRATION_5_6.migrate(db)

            val columns = db.query("PRAGMA table_info(active_sessions)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(nameIndex))
                }
            }
            assertTrue("is_paused" in columns)
            assertTrue("total_paused_time_ms" in columns)
            assertTrue("last_pause_time" in columns)
            assertTrue("rest_timer_end_time" in columns)
            assertTrue("rest_timer_exercise_name" in columns)
            assertTrue("session_rest_override" in columns)
            assertTrue(db.query("SELECT id FROM active_sessions").use { it.moveToFirst() })
        }
    }

    @Test fun provenanceMigrationKeepsLegacyHistoryAndUsesNullRatherThanGuessingOldTargets() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(6) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE exercise_logs (id TEXT NOT NULL PRIMARY KEY, exercise_name TEXT NOT NULL)")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build())
        try {
            val db = helper.writableDatabase
            db.execSQL("INSERT INTO exercise_logs VALUES ('legacy', 'Bench')")
            IMFITDatabase.MIGRATION_6_7.migrate(db)
            db.query("SELECT id, exercise_name, input_defaults FROM exercise_logs").use {
                assertTrue(it.moveToFirst())
                assertEquals("legacy", it.getString(0))
                assertEquals("Bench", it.getString(1))
                assertTrue(it.isNull(2))
            }
        } finally { helper.close() }
    }
}

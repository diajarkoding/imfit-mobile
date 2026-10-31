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
            db.execSQL("INSERT INTO workout_templates (id, user_id, name, created_at, updated_at) VALUES ('tmpl_1', 'user_1', 'Chest Day', 100, 100)")
            db.execSQL("INSERT INTO template_exercises (template_id, exercise_id, order_index, sets, reps, rest_seconds, sync_status, created_at, updated_at) VALUES ('tmpl_1', 'ex_1', 0, 3, 10, 60, 'SYNCED', 100, 100)")
            db.execSQL("INSERT INTO workout_logs (id, user_id, template_name, date, start_time, end_time, total_volume, total_sets, total_reps, sync_status, created_at, updated_at) VALUES ('wkt_1', 'user_1', 'Chest Day', 100, 100, 200, 300, 3, 30, 'SYNCED', 100, 200)")
            db.execSQL("INSERT INTO exercise_logs (id, workout_log_id, exercise_id, exercise_name, muscle_category, order_index, total_volume, total_sets, total_reps, sync_status, created_at, updated_at, input_defaults) VALUES ('elog_1', 'wkt_1', 'ex_1', 'Bench', 'CHEST', 0, 300, 3, 30, 'SYNCED', 100, 200, '3:10')")
            db.execSQL("INSERT INTO workout_sets (id, exercise_log_id, workout_log_id, exercise_id, set_number, weight, reps, is_completed, sync_status, created_at, updated_at) VALUES ('set_1', 'elog_1', 'wkt_1', 'ex_1', 1, 10.0, 10, 1, 'SYNCED', 100, 200)")
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

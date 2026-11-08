package com.diajarkoding.imfit.data.local.database

import android.content.Context
import android.database.Cursor
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.diajarkoding.imfit.data.local.entity.ExerciseEntity
import com.diajarkoding.imfit.data.local.entity.ExerciseLogEntity
import com.diajarkoding.imfit.data.local.entity.WorkoutLogEntity
import com.diajarkoding.imfit.data.local.entity.WorkoutSetEntity
import kotlinx.coroutines.runBlocking
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
    fun tenToElevenAddsNullableRestAndSessionDurationColumnsAndKeepsEveryExistingRowAndRawSessionJson() = runBlocking {
        val rawSession = """{"id":"active","schemaVersion":3,"future":"kept byte for byte"}"""
        val before = migrations.createDatabase(databaseName, 10).use { db ->
            db.execSQL("INSERT INTO workout_logs (id,user_id,template_id,template_name,date,start_time,end_time,total_volume,total_sets,total_reps,sync_status,pending_operation,deleted_at,created_at,updated_at,total_duration_seconds,total_distance_meters,additional_volume,data_quality,measurement_version,recorded_load_volume) VALUES ('w-v3','owner',NULL,'Pending v3',1,1,2,0,1,0,'PENDING_SYNC','CREATE',NULL,1,1,80,NULL,0,'VERIFIED',3,0)")
            db.execSQL("INSERT INTO workout_logs (id,user_id,template_id,template_name,date,start_time,end_time,total_volume,total_sets,total_reps,sync_status,pending_operation,deleted_at,created_at,updated_at,total_duration_seconds,total_distance_meters,additional_volume,data_quality,measurement_version,recorded_load_volume) VALUES ('w-old','owner',NULL,'Legacy',1,1,2,80,1,8,'SYNCED',NULL,NULL,1,1,0,0,0,'LEGACY_UNVERIFIED',NULL,NULL)")
            db.execSQL("INSERT INTO workout_sets (id,exercise_log_id,workout_log_id,exercise_id,set_number,weight,reps,is_completed,sync_status,pending_operation,created_at,updated_at,duration_seconds,target_row_id,measurement_version) VALUES ('s-v3','e','w-v3','x',1,NULL,NULL,1,'PENDING_SYNC','CREATE',1,1,80,'row-1',3)")
            db.execSQL("INSERT INTO workout_sets (id,exercise_log_id,workout_log_id,exercise_id,set_number,weight,reps,is_completed,sync_status,pending_operation,created_at,updated_at) VALUES ('s-old','e','w-old','x',1,40,8,1,'SYNCED',NULL,1,1)")
            db.execSQL("INSERT INTO active_sessions (id,user_id,template_id,template_name,start_time,current_exercise_index,session_data_json,created_at,updated_at) VALUES ('active','owner','t','T',1,0,'${rawSession.replace("'", "''")}',1,1)")
            listOf("workout_logs" to "SELECT * FROM `workout_logs` ORDER BY 1",
                "workout_sets" to "SELECT * FROM `workout_sets` ORDER BY 1",
                "active_sessions" to "SELECT * FROM `active_sessions` ORDER BY 1").associate { (table, sql) ->
                table to (db.query("SELECT * FROM `$table` LIMIT 0").use { it.columnNames.toList() } to snapshotRows(db, sql))
            }
        }
        migrations.runMigrationsAndValidate(databaseName, 11, true, IMFITDatabase.MIGRATION_10_11).use { db ->
            before.forEach { (table, original) ->
                val (columns, rows) = original
                assertEquals("Original values in $table", rows,
                    snapshotRows(db, "SELECT ${columns.joinToString(",") { "`$it`" }} FROM `$table` ORDER BY 1"))
            }
            db.query("SELECT COUNT(*) FROM workout_sets WHERE rest_elapsed_ms IS NOT NULL OR rest_end_reason IS NOT NULL").use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
            }
            db.query("SELECT COUNT(*) FROM workout_logs WHERE paused_elapsed_ms IS NOT NULL OR active_elapsed_ms IS NOT NULL").use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
            }
            db.query("SELECT session_data_json FROM active_sessions").use {
                assertTrue(it.moveToFirst()); assertEquals(rawSession, it.getString(0))
            }
            db.execSQL("UPDATE workout_sets SET rest_elapsed_ms = 35000, rest_end_reason = 'SKIPPED' WHERE id = 's-v3'")
            db.execSQL("UPDATE workout_logs SET paused_elapsed_ms = 30000, active_elapsed_ms = 115000 WHERE id = 'w-v3'")
            db.query("SELECT rest_elapsed_ms, rest_end_reason FROM workout_sets WHERE id = 's-v3'").use {
                assertTrue(it.moveToFirst()); assertEquals(35000L, it.getLong(0)); assertEquals("SKIPPED", it.getString(1))
            }
        }
    }

    @Test
    fun sixToElevenChainValidatesAgainstTheExportedSchema() {
        migrations.createDatabase(databaseName, 6).close()
        migrations.runMigrationsAndValidate(databaseName, 11, true,
            IMFITDatabase.MIGRATION_6_7, IMFITDatabase.MIGRATION_7_8, IMFITDatabase.MIGRATION_8_9,
            IMFITDatabase.MIGRATION_9_10, IMFITDatabase.MIGRATION_10_11).close()
    }

    @Test
    fun nineToTenPreservesEveryLegacyColumnAndRawRecoveryPayloadThenRoundTripsV3() = runBlocking {
        val tables = listOf("users", "exercises", "workout_templates", "template_exercises",
            "workout_logs", "exercise_logs", "workout_sets", "active_sessions")
        val before = migrations.createDatabase(databaseName, 9).use { db ->
            seedVersionNine(db)
            tables.associateWith { table ->
                LegacyTableSnapshot(
                    rows = snapshotRows(db, "SELECT * FROM `$table` ORDER BY 1, 2"),
                    columns = db.query("SELECT * FROM `$table` LIMIT 0").use { it.columnNames.toList() },
                    indexes = snapshotRows(db,
                        "SELECT name, sql FROM sqlite_master WHERE type = 'index' AND tbl_name = '$table' ORDER BY name"),
                    foreignKeys = snapshotRows(db, "PRAGMA foreign_key_list(`$table`)"),
                    primaryKey = primaryKey(db, table)
                )
            }
        }

        // Fresh databases are now v11, so the fresh-vs-migrated schema equality below must compare against v11.
        migrations.runMigrationsAndValidate(databaseName, 11, true, IMFITDatabase.MIGRATION_9_10, IMFITDatabase.MIGRATION_10_11).use { db ->
            before.forEach { (table, original) ->
                val columns = original.columns.joinToString(",") { "`$it`" }
                assertEquals("Original values in $table", original.rows,
                    snapshotRows(db, "SELECT $columns FROM `$table` ORDER BY 1, 2"))
                assertEquals("Indexes in $table", original.indexes, snapshotRows(db,
                    "SELECT name, sql FROM sqlite_master WHERE type = 'index' AND tbl_name = '$table' ORDER BY name"))
                assertEquals("Foreign keys in $table", original.foreignKeys,
                    snapshotRows(db, "PRAGMA foreign_key_list(`$table`)"))
                assertEquals("Primary key in $table", original.primaryKey, primaryKey(db, table))
            }
            db.query("SELECT tracking_profile, supported_metrics FROM exercises").use {
                assertTrue(it.moveToFirst())
                assertTrue(it.isNull(0))
                assertEquals("", it.getString(1))
            }
            for (table in listOf("workout_templates", "template_exercises", "workout_logs",
                "exercise_logs", "workout_sets")) {
                db.query("SELECT COUNT(*) FROM `$table` WHERE measurement_version IS NOT NULL").use {
                    assertTrue(it.moveToFirst())
                    assertEquals("No version3 backfill in $table", 0, it.getInt(0))
                }
            }
            db.query("SELECT set_targets FROM template_exercises").use {
                assertTrue(it.moveToFirst())
                assertTrue(it.isNull(0))
            }
            for (table in listOf("exercise_logs", "workout_logs")) {
                db.query("SELECT COUNT(*) FROM `$table` WHERE recorded_load_volume IS NOT NULL").use {
                    assertTrue(it.moveToFirst())
                    assertEquals(0, it.getInt(0))
                }
            }
            db.query("SELECT target_row_id, calories_kcal, speed_kph, lap_reps, incline_percent FROM workout_sets").use {
                while (it.moveToNext()) for (column in 0 until it.columnCount) assertTrue(it.isNull(column))
            }
            val fresh = Room.inMemoryDatabaseBuilder(context, IMFITDatabase::class.java).build()
            try {
                for (table in tables) {
                    for (pragma in listOf("table_info", "foreign_key_list", "index_list")) {
                        assertEquals("Fresh and migrated $pragma for $table",
                            snapshotRows(fresh.openHelper.writableDatabase, "PRAGMA $pragma(`$table`)"),
                            snapshotRows(db, "PRAGMA $pragma(`$table`)"))
                    }
                }
            } finally { fresh.close() }
        }

        val migrated = Room.databaseBuilder(context, IMFITDatabase::class.java, databaseName)
            .addMigrations(IMFITDatabase.MIGRATION_9_10, IMFITDatabase.MIGRATION_10_11)
            .build()
        try { assertV3RoundTrips(migrated) } finally { migrated.close() }
    }

    @Test
    fun freshVersionTenRoundTripsNullableDecimalResultsAndMetadata() = runBlocking {
        val fresh = Room.inMemoryDatabaseBuilder(context, IMFITDatabase::class.java).build()
        try { assertV3RoundTrips(fresh) } finally { fresh.close() }
    }

    @Test
    fun sixToTenChainRetainsLegacyHistoryWithoutAssigningMeasurementVersion() {
        migrations.createDatabase(databaseName, 6).use { db ->
            db.execSQL("INSERT INTO exercise_logs (id, workout_log_id, exercise_id, exercise_name, muscle_category, order_index, total_volume, total_sets, total_reps, sync_status, created_at, updated_at) VALUES ('legacy','workout','exercise','Bench','CHEST',0,80,1,8,'SYNCED',1,1)")
        }
        migrations.runMigrationsAndValidate(databaseName, 10, true,
            IMFITDatabase.MIGRATION_6_7, IMFITDatabase.MIGRATION_7_8,
            IMFITDatabase.MIGRATION_8_9, IMFITDatabase.MIGRATION_9_10).use { db ->
            db.query("SELECT id, total_volume, input_defaults, measurement_version, recorded_load_volume, total_distance_meters FROM exercise_logs").use {
                assertTrue(it.moveToFirst())
                assertEquals("legacy", it.getString(0))
                assertEquals(80.0, it.getDouble(1), 0.0)
                assertTrue(it.isNull(2))
                assertTrue(it.isNull(3))
                assertTrue(it.isNull(4))
                assertEquals(0.0, it.getDouble(5), 0.0)
            }
        }
    }

    private fun seedVersionNine(db: SupportSQLiteDatabase) {
        val pendingCommand = """  {"schema_version":2,"command_id":"original-command","weight":72.125,"unknown":{"retain":true}}  """
        db.execSQL("""
            INSERT INTO users (id, name, email, birth_date, profile_photo_uri, sync_status,
                pending_operation, created_at, updated_at)
            VALUES ('owner', 'Owner', 'owner@example.test', '1990-01-02', 'content://photo',
                'PENDING_SYNC', ?, 101, 102)
        """.trimIndent(), arrayOf(pendingCommand))
        db.execSQL("""
            INSERT INTO exercises (id, name, muscle_category_id, description, image_url, is_active,
                created_at, updated_at, default_tracking_type, modality, allowed_tracking_types)
            VALUES ('legacy-exercise', 'Legacy row', 1, 'Keep metadata', 'https://example.test/image',
                1, 103, 104, 'WEIGHT_REPS', 'STRENGTH', 'WEIGHT_REPS,BODYWEIGHT_REPS')
        """.trimIndent())
        db.execSQL("""
            INSERT INTO workout_templates (id, user_id, name, is_deleted, sync_status,
                pending_operation, created_at, updated_at)
            VALUES ('legacy-template', 'owner', 'Quarantined routine', 1, 'PENDING_SYNC', ?, 105, 106)
        """.trimIndent(), arrayOf(pendingCommand))
        db.execSQL("""
            INSERT INTO template_exercises (template_id, exercise_id, order_index, sets, reps,
                rest_seconds, sync_status, pending_operation, created_at, updated_at,
                tracking_type, target_duration_seconds, target_distance_meters)
            VALUES ('legacy-template', 'legacy-exercise', 4, 3, NULL, 0, 'PENDING_SYNC', ?,
                107, 108, 'DISTANCE_DURATION', 301, 1251)
        """.trimIndent(), arrayOf(pendingCommand))

        for ((index, state) in listOf("legacy" to "SYNCED", "pending" to "PENDING_SYNC").withIndex()) {
            val (id, status) = state
            val pending = if (status == "SYNCED") null else pendingCommand
            val defaults = if (index == 0) "3:8" else """ {"sets":3,"reps":8,"tracking_type":"WEIGHT_REPS","unknown":"preserve"} """
            db.execSQL("""
                INSERT INTO workout_logs (id, user_id, template_id, template_name, date,
                    start_time, end_time, total_volume, total_sets, total_reps, sync_status,
                    pending_operation, deleted_at, created_at, updated_at, total_duration_seconds,
                    total_distance_meters, additional_volume, data_quality)
                VALUES (?, 'owner', 'legacy-template', 'Historical routine', 109, 110, 111,
                    577.0, 1, 8, ?, ?, 112, 113, 114, 301, 1251, 12.5, 'LEGACY_UNVERIFIED')
            """.trimIndent(), arrayOf(id, status, pending))
            db.execSQL("""
                INSERT INTO exercise_logs (id, workout_log_id, exercise_id, exercise_name,
                    muscle_category, order_index, total_volume, total_sets, total_reps, sync_status,
                    pending_operation, created_at, updated_at, input_defaults, tracking_type,
                    modality, total_duration_seconds, total_distance_meters, additional_volume, metric_quality)
                VALUES (?, ?, 'legacy-exercise', 'Historical exercise name', 'CHEST', 4,
                    577.0, 1, 8, ?, ?, 115, 116, ?, 'WEIGHT_REPS', 'STRENGTH',
                    301, 1251, 12.5, 'LEGACY_UNVERIFIED')
            """.trimIndent(), arrayOf("$id-exercise", id, status, pending, defaults))
            db.execSQL("""
                INSERT INTO workout_sets (id, exercise_log_id, workout_log_id, exercise_id,
                    set_number, weight, reps, is_completed, sync_status, pending_operation,
                    created_at, updated_at, duration_seconds, distance_meters, additional_weight, assistance_weight)
                VALUES (?, ?, ?, 'legacy-exercise', 2, 72.125, 8, 1, ?, ?, 117, 118, 301, 1251, 1.5, 2.25)
            """.trimIndent(), arrayOf("$id-set", "$id-exercise", id, status, pending))
        }

        val activePayloads = listOf(
            """ {"id":"active-v1","exerciseLogs":[{"sets":[{"weight":72.125,"reps":8}]}]} """,
            """{"schemaVersion":2,"id":"active-v2","exerciseLogs":[],"unknown":{"keep":[1,2]}}""",
            """ {broken-json: [""",
            """ {"schemaVersion":98,"id":"future","opaque":{"do_not_rewrite":true}} """
        )
        activePayloads.forEachIndexed { index, raw ->
            db.execSQL("""
                INSERT INTO active_sessions (id, user_id, template_id, template_name, start_time,
                    current_exercise_index, session_data_json, created_at, updated_at, is_paused,
                    total_paused_time_ms, last_pause_time, rest_timer_end_time,
                    rest_timer_exercise_name, session_rest_override)
                VALUES (?, ?, 'legacy-template', 'Active original', 119, 2, ?, 120, 121,
                    1, 9876, 122, 123456, 'Original rest exercise', 0)
            """.trimIndent(), arrayOf("active-$index", "owner-$index", raw))
        }
    }

    private suspend fun assertV3RoundTrips(db: IMFITDatabase) {
        val exercise = ExerciseEntity(
            id = "v3-exercise", name = "Equipment", muscleCategoryId = 1, description = "Metadata",
            imageUrl = "https://example.test/v3", isActive = true, createdAt = 201, updatedAt = 202,
            defaultTrackingType = "DURATION", modality = "CARDIO", allowedTrackingTypes = listOf("DURATION"),
            trackingProfile = "equipment_metrics",
            supportedMetrics = listOf("calories", "distance", "speed", "laps", "incline")
        )
        db.exerciseDao().insertExercise(exercise)
        assertEquals(exercise, db.exerciseDao().getExerciseById(exercise.id))

        val targets = """[{"row_id":"row-2","set_number":1,"weight":null,"reps":null,"duration_seconds":60,"rest_seconds":0},{"row_id":"row-1","set_number":2,"weight":null,"reps":null,"duration_seconds":120,"rest_seconds":30}]"""
        val defaults = """{"schema_version":3,"measurement_version":3,"modality":"CARDIO","tracking_type":"DURATION","tracking_profile":"equipment_metrics","supported_metrics":["calories","distance","speed","laps","incline"],"set_targets":$targets,"extra_set_targets":[{"row_id":"extra-row","set_number":3,"weight":null,"reps":null,"duration_seconds":30,"rest_seconds":0}]}"""
        val template = com.diajarkoding.imfit.data.local.entity.WorkoutTemplateEntity(
            id = "v3-template", userId = "v3-owner", name = "V3 routine", isDeleted = false,
            syncStatus = "SYNCED", createdAt = 203, updatedAt = 204, measurementVersion = 3
        )
        val templateExercise = com.diajarkoding.imfit.data.local.entity.TemplateExerciseEntity(
            templateId = template.id, exerciseId = exercise.id, orderIndex = 0, sets = 2, reps = null,
            restSeconds = 0, syncStatus = "SYNCED", createdAt = 205, updatedAt = 206,
            trackingType = "DURATION", setTargets = targets, measurementVersion = 3
        )
        db.openHelper.writableDatabase.execSQL("""
            INSERT INTO workout_templates (id, user_id, name, is_deleted, sync_status, pending_operation,
                created_at, updated_at, measurement_version) VALUES (?, ?, ?, 0, 'SYNCED', NULL, 203, 204, 3)
        """.trimIndent(), arrayOf(template.id, template.userId, template.name))
        db.openHelper.writableDatabase.execSQL("""
            INSERT INTO template_exercises (template_id, exercise_id, order_index, sets, reps, rest_seconds,
                sync_status, pending_operation, created_at, updated_at, tracking_type,
                target_duration_seconds, target_distance_meters, set_targets, measurement_version)
            VALUES (?, ?, 0, 2, NULL, 0, 'SYNCED', NULL, 205, 206, 'DURATION', NULL, NULL, ?, 3)
        """.trimIndent(), arrayOf(template.id, exercise.id, targets))
        assertEquals(listOf(template), db.workoutTemplateDao().getAllTemplatesByUserIncludingDeleted("v3-owner"))
        assertEquals(listOf(templateExercise), db.templateExerciseDao().getExercisesForTemplateList(template.id))

        val workout = WorkoutLogEntity(
            id = "v3-workout", userId = "v3-owner", templateId = template.id, templateName = template.name,
            date = 207, startTime = 208, endTime = 209, totalVolume = 0f, totalSets = 3, totalReps = 8,
            syncStatus = "PENDING_SYNC", pendingOperation = """{"schema_version":3,"id":"v3-workout"}""",
            createdAt = 210, updatedAt = 211, totalDurationSeconds = 180, totalDistanceMeters = 1234.56,
            dataQuality = "VERIFIED", measurementVersion = 3, recordedLoadVolume = 579.6
        )
        val log = ExerciseLogEntity(
            id = "v3-exercise-log", workoutLogId = workout.id, exerciseId = exercise.id,
            exerciseName = exercise.name, muscleCategory = "CARDIO", orderIndex = 0, totalVolume = 0f,
            totalSets = 2, totalReps = 0, createdAt = 212, updatedAt = 213,
            inputDefaults = defaults, trackingType = "DURATION", modality = "CARDIO",
            totalDurationSeconds = 180, totalDistanceMeters = 1234.56,
            metricQuality = "VERIFIED", measurementVersion = 3, recordedLoadVolume = 0.0
        )
        val nullableSet = WorkoutSetEntity(
            id = "v3-nullable-set", exerciseLogId = log.id, workoutLogId = workout.id, exerciseId = exercise.id,
            setNumber = 1, weight = null, reps = null, isCompleted = true, createdAt = 214, updatedAt = 215,
            durationSeconds = 60, distanceMeters = 1234.56, targetRowId = "row-2", caloriesKcal = 88.12,
            speedKph = 12.34, lapReps = 4, inclinePercent = 2.75, measurementVersion = 3
        )
        val unrecordedSet = nullableSet.copy(
            id = "v3-unrecorded-set", setNumber = 2, targetRowId = "row-1", durationSeconds = 120,
            distanceMeters = null, caloriesKcal = null, speedKph = null, lapReps = null, inclinePercent = null
        )
        val strengthExercise = exercise.copy(
            id = "v3-strength", name = "Strength", defaultTrackingType = "WEIGHT_REPS", modality = "STRENGTH",
            allowedTrackingTypes = listOf("WEIGHT_REPS"), trackingProfile = null, supportedMetrics = emptyList()
        )
        val strengthLog = log.copy(
            id = "v3-strength-log", exerciseId = strengthExercise.id, exerciseName = strengthExercise.name,
            muscleCategory = "CHEST", orderIndex = 1, trackingType = "WEIGHT_REPS", modality = "STRENGTH",
            totalSets = 1, totalReps = 8, totalDurationSeconds = 0, totalDistanceMeters = null,
            recordedLoadVolume = 579.6,
            inputDefaults = """{"schema_version":3,"measurement_version":3,"modality":"STRENGTH","tracking_type":"WEIGHT_REPS","tracking_profile":null,"supported_metrics":[],"set_targets":[{"row_id":"row-strength","set_number":1,"weight":null,"reps":8,"duration_seconds":null,"rest_seconds":0}],"extra_set_targets":[]}"""
        )
        val decimalSet = nullableSet.copy(
            id = "v3-decimal-set", exerciseLogId = strengthLog.id, exerciseId = strengthExercise.id,
            setNumber = 1, weight = 72.45, reps = 8,
            targetRowId = "row-strength", durationSeconds = null, distanceMeters = null,
            caloriesKcal = null, speedKph = null, lapReps = null, inclinePercent = null
        )
        db.exerciseDao().insertExercise(strengthExercise)
        db.workoutLogDao().insertWorkoutLog(workout)
        db.exerciseLogDao().insertExerciseLogs(listOf(log, strengthLog))
        db.workoutSetDao().insertWorkoutSets(listOf(nullableSet, unrecordedSet, decimalSet))
        assertEquals(workout, db.workoutLogDao().getWorkoutLogById(workout.id))
        assertEquals(log, db.exerciseLogDao().getExerciseLog(workout.id, exercise.id))
        assertEquals(nullableSet, db.workoutSetDao().getSetById(nullableSet.id))
        assertEquals(unrecordedSet, db.workoutSetDao().getSetById(unrecordedSet.id))
        assertEquals(strengthExercise, db.exerciseDao().getExerciseById(strengthExercise.id))
        assertEquals(strengthLog, db.exerciseLogDao().getExerciseLog(workout.id, strengthExercise.id))
        for (distance in listOf<Double?>(null, 0.0)) {
            val id = if (distance == null) "v3-unrecorded-distance" else "v3-zero-distance"
            val aggregate = workout.copy(id = id, totalDistanceMeters = distance, recordedLoadVolume = null)
            val aggregateLog = log.copy(id = "$id-log", workoutLogId = id,
                totalDistanceMeters = distance, recordedLoadVolume = null)
            db.workoutLogDao().insertWorkoutLog(aggregate)
            db.exerciseLogDao().insertExerciseLog(aggregateLog)
            assertEquals(aggregate, db.workoutLogDao().getWorkoutLogById(id))
            assertEquals(aggregateLog, db.exerciseLogDao().getExerciseLog(id, exercise.id))
        }
        assertEquals(decimalSet, db.workoutSetDao().getSetById(decimalSet.id))
    }

    private data class LegacyTableSnapshot(
        val rows: List<List<Any?>>,
        val columns: List<String>,
        val indexes: List<List<Any?>>,
        val foreignKeys: List<List<Any?>>,
        val primaryKey: List<Pair<String, Int>>
    )

    private fun primaryKey(db: SupportSQLiteDatabase, table: String): List<Pair<String, Int>> =
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val position = cursor.getInt(cursor.getColumnIndexOrThrow("pk"))
                    if (position > 0) add(cursor.getString(cursor.getColumnIndexOrThrow("name")) to position)
                }
            }
        }

    private fun snapshotRows(db: SupportSQLiteDatabase, sql: String): List<List<Any?>> =
        db.query(sql).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add((0 until cursor.columnCount).map { column ->
                    when (cursor.getType(column)) {
                        Cursor.FIELD_TYPE_NULL -> null
                        // Integer distance columns intentionally acquire REAL affinity in v10.
                        Cursor.FIELD_TYPE_INTEGER, Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(column)
                        Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(column).toList()
                        else -> cursor.getString(column)
                    }
                })
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

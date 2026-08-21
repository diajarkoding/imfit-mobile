package com.diajarkoding.imfit.data.local.database

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IMFITDatabaseMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val databaseName = "migration-test.db"

    @After
    fun cleanUp() {
        context.deleteDatabase(databaseName)
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
}

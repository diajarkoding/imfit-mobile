package com.diajarkoding.imfit.data.sync

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.diajarkoding.imfit.data.local.database.IMFITDatabase
import com.diajarkoding.imfit.data.local.entity.WorkoutLogEntity
import com.diajarkoding.imfit.data.recovery.RoomLegacyRoutineRecovery
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LegacyRoutineRecoveryUpgradeTest {
    @get:Rule val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), IMFITDatabase::class.java)
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "legacy-routine-upgrade.db"

    @After fun cleanup() { context.deleteDatabase(name) }

    @Test fun pendingUpgradeRowsRemainExportableIncludingTombstonesWhileHistoryStillImports() = runBlocking {
        migrations.createDatabase(name, 6).use { old ->
            fun parent(id: String, owner: String, deleted: Int, status: String, operation: String?) {
                old.execSQL("INSERT INTO workout_templates (id,user_id,name,is_deleted,sync_status,pending_operation,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?)",
                    arrayOf(id, owner, "Original $id", deleted, status, operation, 11L, 22L))
            }
            parent("create", "owner", 0, "PENDING_SYNC", "CREATE")
            parent("update", "owner", 0, "SYNC_FAILED", "UPDATE")
            parent("delete", "owner", 1, "PENDING_SYNC", "DELETE")
            parent("dirty-child", "owner", 0, "SYNCED", null)
            parent("clean", "owner", 0, "SYNCED", null)
            parent("foreign", "another-owner", 0, "PENDING_SYNC", "CREATE")
            listOf("create", "update", "delete", "dirty-child").forEachIndexed { index, id ->
                old.execSQL("INSERT INTO template_exercises (template_id,exercise_id,order_index,sets,reps,rest_seconds,sync_status,pending_operation,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    arrayOf(id, "exercise-$index", 2, 4, 12, 90, "SYNC_FAILED", "UPDATE", 33L, 44L))
            }
        }
        migrations.runMigrationsAndValidate(name, 7, true, IMFITDatabase.MIGRATION_6_7).close()
        val db = Room.databaseBuilder(context, IMFITDatabase::class.java, name).build()
        try {
            val recovery = RoomLegacyRoutineRecovery(db)
            val originals = db.workoutTemplateDao().getAllTemplatesByUserIncludingDeleted("owner").sortedBy { it.id }
            val originalChildren = originals.associate { it.id to db.templateExerciseDao().getExercisesForTemplateList(it.id) }
            assertEquals(4, recovery.observeCount("owner").first())
            val export = Json.parseToJsonElement(recovery.export("owner")).jsonObject
            val routines = export.getValue("routines").jsonArray.map { it.jsonObject }.associateBy { it.getValue("id").jsonPrimitive.content }
            assertEquals(setOf("create", "update", "delete", "dirty-child"), routines.keys)
            assertEquals("DELETE", routines.getValue("delete").getValue("pending_operation").jsonPrimitive.content)
            assertTrue(routines.getValue("delete").getValue("is_deleted").jsonPrimitive.boolean)
            for ((id, raw) in routines) {
                assertEquals("Original $id", raw.getValue("name").jsonPrimitive.content)
                assertEquals(11L, raw.getValue("created_at").jsonPrimitive.long)
                assertEquals(22L, raw.getValue("updated_at").jsonPrimitive.long)
                val child = raw.getValue("exercises").jsonArray.single().jsonObject
                assertEquals(2, child.getValue("order_index").jsonPrimitive.int)
                assertEquals(4, child.getValue("sets").jsonPrimitive.int)
                assertEquals(12, child.getValue("reps").jsonPrimitive.int)
                assertEquals(90, child.getValue("rest_seconds").jsonPrimitive.int)
                assertEquals("SYNC_FAILED", child.getValue("sync_status").jsonPrimitive.content)
                assertEquals("UPDATE", child.getValue("pending_operation").jsonPrimitive.content)
                assertEquals(33L, child.getValue("created_at").jsonPrimitive.long)
                assertEquals(44L, child.getValue("updated_at").jsonPrimitive.long)
            }
            RemoteSnapshotImporter(db).workout(
                WorkoutLogEntity("history", "owner", "delete", "Remote history", 1, 1, 2, 0f,
                    syncStatus = "SYNCED", updatedAt = 99), emptyList(), emptyList())
            assertNotNull(db.workoutLogDao().getWorkoutLogById("history"))
            assertEquals(originals, db.workoutTemplateDao().getAllTemplatesByUserIncludingDeleted("owner").sortedBy { it.id })
            originalChildren.forEach { (id, rows) -> assertEquals(rows, db.templateExerciseDao().getExercisesForTemplateList(id)) }
            assertEquals(1, recovery.observeCount("another-owner").first())
            // Logout clears session/history but must not silently erase the recovery archive.
            db.deleteUserData("owner")
            assertNull(db.workoutLogDao().getWorkoutLogById("history"))
            assertEquals(originals, db.workoutTemplateDao().getAllTemplatesByUserIncludingDeleted("owner").sortedBy { it.id })
            originalChildren.forEach { (id, rows) -> assertEquals(rows, db.templateExerciseDao().getExercisesForTemplateList(id)) }
            assertEquals(export, Json.parseToJsonElement(recovery.export("owner")).jsonObject)
        } finally { db.close() }
    }
}

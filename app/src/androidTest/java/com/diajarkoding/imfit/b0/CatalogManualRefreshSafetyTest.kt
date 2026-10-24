package com.diajarkoding.imfit.b0

import com.diajarkoding.imfit.data.local.entity.ExerciseEntity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Only isolated diagnostic+B0 runner; exercises actual manual-read apply, not full sync. */
class CatalogManualRefreshSafetyTest {
    @Test fun shortCatalogReadDoesNotDeactivateMissingRecordsOrTouchPendingAggregates() = runBlocking {
        withTimeout(10_000) {
            val fixture = SyncSafetyFixture.create()
            try {
                val template = fixture.seedTemplate()
                val workout = fixture.seedWorkout()
                val beforeTemplate = fixture.db.workoutTemplateDao().getTemplateById(template.id)
                val beforeWorkout = fixture.db.workoutLogDao().getWorkoutLogById(workout.id)
                fixture.db.exerciseDao().insertExercise(ExerciseEntity("cached", "Cached", 1, ""))
                fixture.remote.script = { ScriptedSyncRemote.Reply("""[{"id":"fresh","name":"Fresh","muscle_category_id":1}]""") }
                fixture.stage("pullCatalogRead", SyncSafetyFixture.OWNER_A, false)
                assertTrue(fixture.db.exerciseDao().getExerciseById("cached")!!.isActive)
                assertEquals("Fresh", fixture.db.exerciseDao().getExerciseById("fresh")!!.name)
                assertEquals(beforeTemplate, fixture.db.workoutTemplateDao().getTemplateById(template.id))
                assertEquals(beforeWorkout, fixture.db.workoutLogDao().getWorkoutLogById(workout.id))
                assertEquals(1, fixture.remote.ledger.count("GET /rest/v1/exercises"))
            } finally { fixture.close() }
        }
    }

    @Test fun duplicateIdsAreRejectedBeforeAnyManualCatalogApply() = runBlocking {
        withTimeout(10_000) {
            val fixture = SyncSafetyFixture.create()
            try {
                fixture.db.exerciseDao().insertExercise(ExerciseEntity("cached", "Cached", 1, ""))
                fixture.remote.script = { ScriptedSyncRemote.Reply("""[{"id":"duplicate","name":"First","muscle_category_id":1},{"id":"duplicate","name":"Second","muscle_category_id":1}]""") }
                try { fixture.stage("pullCatalogRead", SyncSafetyFixture.OWNER_A, false); fail("Unverified duplicate snapshot accepted") }
                catch (_: IllegalArgumentException) { }
                assertEquals(listOf("cached"), fixture.db.exerciseDao().getAllActiveExercisesList().map { it.id })
            } finally { fixture.close() }
        }
    }
}

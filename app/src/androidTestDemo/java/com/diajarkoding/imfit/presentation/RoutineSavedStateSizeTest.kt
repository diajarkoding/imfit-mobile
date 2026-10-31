package com.diajarkoding.imfit.presentation

import android.os.Bundle
import android.os.Parcel
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.diajarkoding.imfit.di.RestTimerEntryPoint
import com.diajarkoding.imfit.domain.model.*
import com.diajarkoding.imfit.domain.repository.*
import com.diajarkoding.imfit.presentation.ui.workout.EditWorkoutViewModel
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Maximum supported routine: exact replay must survive a real Android Bundle round-trip. */
@RunWith(AndroidJUnit4::class)
class RoutineSavedStateSizeTest {
    @Test fun hundredExerciseUnknownCommitRestoresWithoutCatalogueAndReplaysExactly() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ports = EntryPointAccessors.fromApplication(instrumentation.targetContext, RestTimerEntryPoint::class.java)
        val owner = requireNotNull(ports.auth().getLocalUserId())
        val routines = ports.routines()
        val empty = routines.createRoutine(owner, "Saved state boundary").getOrThrow()
        val exercises = (1..100).map { Exercise("boundary-${UUID.randomUUID()}", "Exercise $it", MuscleCategory.CHEST,
            "Large catalogue description that must not enter saved command state. ".repeat(100)) }
        val catalog = object : ExerciseCatalogRepository {
            override suspend fun fetchCatalogPage(search: String, categoryId: Int?, cursor: String?, limit: Int): Result<ExerciseCatalogPage> = error("Not a picker")
            override suspend fun getExercisesByIds(ids: List<String>): ExerciseHydrationResult = ExerciseHydrationResult.Error(java.io.IOException("Catalogue unavailable"))
        }
        var loseResponse = true
        val unreliable = object : RoutineRepository by routines {
            override suspend fun saveRoutine(routineId: String, name: String, exercises: List<TemplateExercise>, expectedRevision: Long?, requestId: String, baselineExerciseIds: List<String>): Result<WorkoutTemplate> {
                val committed = routines.saveRoutine(routineId, name, exercises, expectedRevision, requestId, baselineExerciseIds)
                if (committed.isSuccess && loseResponse) {
                    loseResponse = false
                    return Result.failure(RoutineError.Network("Acknowledgment lost"))
                }
                return committed
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val context = RoutineFlowContext(UUID.randomUUID().toString(), empty.id, owner, RoutineFormMode.SETUP)
        try {
            val handle = SavedStateHandle()
            val first = EditWorkoutViewModel(unreliable, catalog, ports.auth(), handle, scope)
            first.initialize(empty.id, context.mode, context.flowId, owner)
            assertTrue(first.acceptSelection(RoutineSelectionResult(context, "selection", exercises)))
            first.saveChanges()
            assertTrue(first.state.value.hasPendingSave)
            val committed = requireNotNull(routines.getRoutineById(empty.id).getOrThrow())
            assertEquals(100, committed.exercises.size)
            val bundle = Bundle().apply { handle.keys().forEach { putString(it, handle.get<String>(it)) } }
            val parcel = Parcel.obtain()
            val bytes = try { parcel.writeBundle(bundle); parcel.marshall() } finally { parcel.recycle() }
            val restoredBundle = Parcel.obtain().let { restored ->
                try { restored.unmarshall(bytes, 0, bytes.size); restored.setDataPosition(0); requireNotNull(restored.readBundle(javaClass.classLoader)) }
                finally { restored.recycle() }
            }
            Log.i("IMFITRoutineState", "100-exercise immutable command + raw draft Bundle bytes=${bytes.size}; target=10240")
            instrumentation.sendStatus(0, Bundle().apply { putInt("routine_saved_state_bytes", bytes.size) })
            assertTrue("One routine draft must leave headroom within Binder's shared transaction budget", bytes.size < 256 * 1024)
            val restored = EditWorkoutViewModel(unreliable, catalog, ports.auth(),
                SavedStateHandle(restoredBundle.keySet().associateWith { restoredBundle.getString(it) }), scope)
            restored.initialize(empty.id, context.mode, context.flowId, owner)
            assertTrue(restored.state.value.hasPendingSave)
            restored.saveChanges()
            assertTrue(restored.state.value.isSaved)
            val replayed = requireNotNull(routines.getRoutineById(empty.id).getOrThrow())
            assertEquals(committed.revision, replayed.revision)
            assertEquals(exercises.map { it.id }, replayed.exercises.map { it.id })
            assertEquals(List(100) { 10 }, replayed.exercises.map { it.reps })
        } finally {
            scope.cancel()
            val latest = requireNotNull(routines.getRoutineById(empty.id).getOrThrow())
            routines.deleteRoutine(latest.id, latest.revision, baselineExerciseIds = latest.exercises.map { it.id }, name = latest.name).getOrThrow()
        }
    }
}

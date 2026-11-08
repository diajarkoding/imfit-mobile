package com.diajarkoding.imfit.presentation.ui.workout

import androidx.lifecycle.SavedStateHandle
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.domain.model.*
import com.diajarkoding.imfit.domain.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class RoutineRefactorFlowTest {
    private val bench = Exercise("ex_chest_1", "Bench", MuscleCategory.CHEST, "Press")
    private val row = Exercise("ex_back_3", "Row", MuscleCategory.BACK, "Pull")
    private val plank = Exercise("ex_core_1", "Renamed static exercise", MuscleCategory.CORE, "Hold",
        defaultTrackingType = TrackingType.DURATION, modality = ExerciseModality.ISOMETRIC)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val context = RoutineFlowContext("flow", "routine", "owner", RoutineFormMode.EDIT)
    private val auth = EditorAuth()
    private val catalog = EditorCatalog(listOf(bench, row, plank))
    private val repository = EditorRoutines(WorkoutTemplate("routine", "owner", "Original",
        listOf(
            v3Strength(bench, listOf(SetTarget("b-1", 1, 40.0, 8, restSeconds = 60), SetTarget("b-2", 2, 45.0, 8, restSeconds = 60), SetTarget("b-3", 3, 50.0, 8, restSeconds = 60))),
            v3Strength(row, listOf(SetTarget("r-1", 1, 30.0, 12, restSeconds = 90), SetTarget("r-2", 2, 30.0, 12, restSeconds = 90), SetTarget("r-3", 3, 30.0, 12, restSeconds = 90), SetTarget("r-4", 4, 30.0, 12, restSeconds = 90)))
        ), revision = 7, measurementVersion = 3))

    @After fun close() { scope.cancel() }

    private fun editor(handle: SavedStateHandle = SavedStateHandle(), ctx: RoutineFlowContext = context): EditWorkoutViewModel =
        EditWorkoutViewModel(repository, catalog, auth, handle, scope).also {
            it.initialize(ctx.routineId, ctx.mode, ctx.flowId, ctx.ownerId)
        }

    private fun recreate(handle: SavedStateHandle) = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })

    private fun v3Strength(exercise: Exercise, targets: List<SetTarget>) = TemplateExercise(
        exercise = exercise,
        sets = targets.size,
        reps = targets.firstOrNull()?.reps,
        restSeconds = targets.firstOrNull()?.restSeconds ?: 60,
        trackingType = TrackingType.WEIGHT_REPS,
        setTargets = targets,
        measurementVersion = 3
    )

    @Test fun rawInvalidTextOrderMembershipAndBaselineSurviveRealConsumerRecreation() {
        val handle = SavedStateHandle()
        val original = editor(handle)
        original.updateWorkoutName("My raw draft")
        original.updateSetRow(bench.id, "b-1") { it.copy(weight = "", reps = "-1.5") }
        original.moveExerciseDown(0)
        val restored = editor(recreate(handle))
        assertEquals(1, repository.reads)
        assertEquals(listOf(row.id, bench.id), catalog.requests.last())
        assertEquals(listOf(row.id, bench.id), restored.state.value.exercises.map { it.id })
        val restoredBenchRow1 = restored.state.value.rawConfigs.getValue(bench.id).setRows.first()
        assertEquals("b-1", restoredBenchRow1.rowId)
        assertEquals("", restoredBenchRow1.weight)
        assertEquals("-1.5", restoredBenchRow1.reps)
        assertEquals("My raw draft", restored.state.value.workoutName)
        assertEquals(7L, restored.state.value.baseRevision)
        assertTrue(restored.state.value.isDirty)
        assertTrue(bench.id in restored.state.value.invalidConfigIds)
        restored.saveChanges()
        assertTrue(repository.commands.isEmpty())
        restored.removeExercise(1)
        restored.saveChanges()
        assertEquals(listOf(bench.id, row.id), repository.commands.single().baselineIds)
        assertEquals(listOf(row.id), repository.commands.single().exercises.map { it.id })
    }

    @Test fun reorderNeverReplacesRawInputsWithLastValidPrescription() {
        val vm = editor()
        vm.updateSetRow(bench.id, "b-2") { it.copy(weight = "-10", reps = "") }
        val expected = vm.state.value.rawConfigs[bench.id]
        vm.moveExerciseDown(0)
        vm.moveExerciseUp(1)
        assertEquals(expected, vm.state.value.rawConfigs[bench.id])
        assertTrue(bench.id in vm.state.value.invalidConfigIds)
        assertTrue(repository.commands.isEmpty())
    }

    @Test fun setupSelectionIsDurablyAcceptedExactlyOnce() {
        repository.routine = repository.routine.copy(exercises = emptyList())
        val ctx = context.copy(mode = RoutineFormMode.SETUP)
        val handle = SavedStateHandle()
        val vm = editor(handle, ctx)
        val result = RoutineSelectionResult(ctx, "selection-1", listOf(row, bench))
        assertTrue(vm.acceptSelection(result))
        val firstRowId = vm.state.value.rawConfigs.getValue(row.id).setRows.first().rowId
        vm.updateSetRow(row.id, firstRowId) { it.copy(reps = "") }
        vm.removeExercise(1)
        val restored = editor(recreate(handle), ctx)
        assertTrue(restored.acceptSelection(result))
        assertEquals(RoutineFormMode.SETUP, restored.state.value.mode)
        assertEquals(listOf(row.id), restored.state.value.exercises.map { it.id })
        assertEquals("", restored.state.value.rawConfigs[row.id]?.setRows?.first()?.reps)
        assertFalse(restored.acceptSelection(result.copy(context = ctx.copy(flowId = "other"))))
        assertFalse(restored.acceptSelection(result.copy(context = ctx.copy(ownerId = "other"))))
    }

    @Test fun unknownSaveRecreationAndRetryKeepExactRequestPayloadAndFreezeDraft() {
        val handle = SavedStateHandle()
        repository.save = { Result.failure(RoutineError.Network("Response lost after commit")) }
        val vm = editor(handle)
        vm.updateWorkoutName("Immutable command")
        vm.removeExercise(0)
        vm.updateSetRow(row.id, "r-1") { it.copy(weight = "55", reps = "9") }
        vm.saveChanges()
        val first = repository.commands.single()
        assertTrue(vm.state.value.hasPendingSave)
        vm.dismissErrorDialog()
        vm.updateWorkoutName("Must not change")
        vm.updateSetRow(row.id, "r-1") { it.copy(weight = "99", reps = "99") }
        vm.removeExercise(0)
        assertEquals("Immutable command", vm.state.value.workoutName)
        assertEquals(1, vm.state.value.exercises.size)
        catalog.available = emptyList() // Replay remains possible even if catalogue access is lost after commit.
        val restored = editor(recreate(handle))
        assertTrue(restored.state.value.hasPendingSave)
        assertFalse(restored.acceptSelection(RoutineSelectionResult(context, "other", listOf(bench))))
        repository.save = { command -> Result.success(repository.routine.copy(name = command.name,
            exercises = command.exercises, revision = 8)) }
        restored.saveChanges()
        assertEquals(first, repository.commands[1])
        assertTrue(restored.state.value.isSaved)
        assertFalse(restored.state.value.hasPendingSave)
        assertEquals(listOf(bench.id, row.id), first.baselineIds)
    }

    @Test fun malformedServerAcknowledgmentAlsoRetainsTheUnresolvedCommand() {
        val vm = editor()
        repository.save = { Result.failure(RoutineError.Server("Malformed acknowledgment")) }
        vm.saveChanges()
        vm.saveChanges()
        assertTrue(vm.state.value.hasPendingSave)
        assertEquals(repository.commands[0], repository.commands[1])
    }

    @Test fun recreationDuringInFlightSaveReplaysThePersistedCommandWithoutAutomaticSubmission() {
        val handle = SavedStateHandle()
        val oldProcessScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        repository.save = { awaitCancellation() }
        val original = EditWorkoutViewModel(repository, catalog, auth, handle, oldProcessScope)
        original.initialize(context.routineId, context.mode, context.flowId, context.ownerId)
        original.updateWorkoutName("In-flight payload")
        original.saveChanges()
        assertTrue(original.state.value.isSaving)
        val immutable = repository.commands.single()
        val restoredHandle = recreate(handle)
        oldProcessScope.cancel()
        val restored = editor(restoredHandle)
        assertEquals(1, repository.commands.size)
        assertTrue(restored.state.value.hasPendingSave)
        repository.save = { Result.success(repository.routine.copy(name = it.name, exercises = it.exercises, revision = 8)) }
        restored.saveChanges()
        assertEquals(immutable, repository.commands.last())
        assertTrue(restored.state.value.isSaved)
    }

    @Test fun knownErrorsRemainTypedAndNeverDiscardDraft() {
        val vm = editor()
        vm.updateWorkoutName("Retained")
        listOf(RoutineError.RevisionConflict(), RoutineError.NotFound(),
            RoutineError.ActiveSessionConflict(), RoutineError.ValidationError("Invalid server input")).forEach { error ->
            repository.save = { Result.failure(error) }
            vm.saveChanges()
            assertSame(error, vm.state.value.saveError)
            assertEquals("Retained", vm.state.value.workoutName)
            assertTrue(vm.state.value.showErrorDialog)
            assertFalse(vm.state.value.isSaved)
            assertFalse(vm.state.value.hasPendingSave)
            vm.dismissErrorDialog()
        }
    }

    @Test fun missingHydrationReportsErrorAndDoesNotSilentlyDropPersistedMembership() {
        val handle = SavedStateHandle()
        val vm = editor(handle)
        vm.updateWorkoutName("Keep both")
        catalog.available = listOf(row)
        val restored = editor(recreate(handle))
        assertFalse(restored.state.value.hasLoaded)
        assertEquals(R.string.error_routine_load, restored.state.value.errorRes)
        assertTrue(restored.state.value.errorMessage!!.contains(bench.id))
        catalog.available = listOf(row, bench)
        restored.loadWorkout()
        assertEquals(listOf(bench.id, row.id), restored.state.value.exercises.map { it.id })
        assertEquals("Keep both", restored.state.value.workoutName)
    }

    @Test fun committedSuccessDoesNotDependOnCanonicalReadAndIsConsumedOnce() {
        val handle = SavedStateHandle()
        val vm = editor(handle)
        vm.saveChanges()
        assertTrue(vm.state.value.isSaved)
        repository.read = { Result.failure(RoutineError.Network("Presentation unavailable")) }
        val restoredHandle = recreate(handle)
        val restored = editor(restoredHandle)
        assertTrue(restored.state.value.isSaved)
        assertEquals(1, repository.reads)
        restored.consumeSaved()
        assertFalse(editor(recreate(restoredHandle)).state.value.isSaved)
        assertEquals(1, repository.commands.size)
    }

    @Test fun accountSwitchDuringSaveErasesDraftAndRejectsOldResponse() = runBlocking {
        val response = CompletableDeferred<Result<WorkoutTemplate>>()
        repository.save = { response.await() }
        val handle = SavedStateHandle()
        val vm = editor(handle)
        vm.updateWorkoutName("Private owner draft")
        vm.saveChanges()
        auth.switch("other-owner")
        response.complete(Result.success(repository.routine.copy(revision = 8)))
        assertTrue(vm.state.value.accountInvalid)
        assertFalse(vm.state.value.isSaved)
        assertEquals("", vm.state.value.workoutName)
        assertNull(handle.get<String>(EditWorkoutViewModel.DRAFT_KEY))
    }

    @Test fun restoredDraftCannotBeOpenedUnderAnotherOwner() {
        val handle = SavedStateHandle()
        editor(handle).updateWorkoutName("Private")
        val restored = editor(recreate(handle), context.copy(ownerId = "other-owner"))
        assertTrue(restored.state.value.accountInvalid)
        assertEquals("", restored.state.value.workoutName)
    }

    @Test fun dynamicSetRowsAddEditDeleteRenumberAndRestoreKeepStableIdsAndIndependentValues() {
        repository.routine = WorkoutTemplate(
            id = "routine",
            userId = "owner",
            name = "Strength targets",
            exercises = listOf(
                v3Strength(
                    bench,
                    listOf(
                        SetTarget("r1", 1, 40.0, 10, restSeconds = 60),
                        SetTarget("r2", 2, 45.0, 8, restSeconds = 90),
                        SetTarget("r3", 3, 50.0, 6, restSeconds = 120)
                    )
                )
            ),
            revision = 7,
            measurementVersion = 3
        )
        val handle = SavedStateHandle()
        val vm = editor(handle)
        vm.addSetRow(bench.id)
        val rowsAfterAdd = vm.state.value.rawConfigs.getValue(bench.id).setRows
        assertEquals(4, rowsAfterAdd.size)
        val r4Id = rowsAfterAdd[3].rowId
        assertTrue(r4Id !in setOf("r1", "r2", "r3"))
        assertEquals("60", rowsAfterAdd[3].rest)

        vm.updateSetRow(bench.id, "r2") { it.copy(weight = "47.5") }
        val rowsAfterEdit = vm.state.value.rawConfigs.getValue(bench.id).setRows
        assertEquals("40", rowsAfterEdit[0].weight)
        assertEquals("47.5", rowsAfterEdit[1].weight)
        assertEquals("50", rowsAfterEdit[2].weight)
        assertEquals(listOf("r1", "r2", "r3", r4Id), rowsAfterEdit.map { it.rowId })

        vm.removeSetRow(bench.id, "r2")
        val restored = editor(recreate(handle))
        val restoredRows = restored.state.value.rawConfigs.getValue(bench.id).setRows
        assertEquals(listOf("r1", "r3", r4Id), restoredRows.map { it.rowId })
        assertEquals(listOf("40", "50", "0"), restoredRows.map { it.weight })
        assertEquals(listOf("10", "6", "10"), restoredRows.map { it.reps })
        assertEquals(listOf("60", "120", "60"), restoredRows.map { it.rest })

        restored.saveChanges()
        val savedExercise = repository.commands.single().exercises.single()
        assertEquals(3, savedExercise.measurementVersion)
        assertEquals(3, savedExercise.sets)
        val savedTargets = requireNotNull(savedExercise.setTargets)
        assertEquals(listOf("r1", "r3", r4Id), savedTargets.map { it.rowId })
        assertEquals(listOf(1, 2, 3), savedTargets.map { it.setNumber })
        assertEquals(listOf(40.0, 50.0, 0.0), savedTargets.map { it.weightKg })
        assertEquals(listOf(10, 6, 10), savedTargets.map { it.reps })
        assertEquals(listOf(60, 120, 60), savedTargets.map { it.restSeconds })

        // Deleting all rows leaves a zero-row invalid draft instead of regenerating 3 defaults.
        repository.commands.clear()
        val reopened = editor()
        reopened.removeSetRow(bench.id, "r1")
        reopened.removeSetRow(bench.id, "r2")
        reopened.removeSetRow(bench.id, "r3")
        assertTrue(reopened.state.value.rawConfigs.getValue(bench.id).setRows.isEmpty())
        assertTrue(bench.id in reopened.state.value.invalidConfigIds)
        reopened.saveChanges()
        assertTrue(repository.commands.isEmpty())
    }

    @Test fun firstInsertGeneratesThreeOrOneRowByProfileWhileEditAndRecoveryNeverRegenerateDefaults() {
        val jumpingJacks = Exercise(
            id = "ex_cardio_duration",
            name = "Jumping Jacks",
            muscleCategory = MuscleCategory.CARDIO,
            description = "",
            defaultTrackingType = TrackingType.DURATION,
            modality = ExerciseModality.CARDIO,
            allowedTrackingTypes = listOf(TrackingType.DURATION),
            trackingProfile = TrackingProfile.DURATION_ONLY,
            supportedMetrics = emptyList()
        )
        val jogging = Exercise(
            id = "ex_cardio_distance",
            name = "Outdoor Jogging",
            muscleCategory = MuscleCategory.CARDIO,
            description = "",
            defaultTrackingType = TrackingType.DURATION,
            modality = ExerciseModality.CARDIO,
            allowedTrackingTypes = listOf(TrackingType.DURATION),
            trackingProfile = TrackingProfile.DISTANCE_ACTIVITY,
            supportedMetrics = listOf(CardioMetric.DISTANCE, CardioMetric.SPEED)
        )
        val treadmill = Exercise(
            id = "ex_cardio_equip",
            name = "Treadmill",
            muscleCategory = MuscleCategory.CARDIO,
            description = "",
            defaultTrackingType = TrackingType.DURATION,
            modality = ExerciseModality.CARDIO,
            allowedTrackingTypes = listOf(TrackingType.DURATION),
            trackingProfile = TrackingProfile.EQUIPMENT_METRICS,
            supportedMetrics = listOf(CardioMetric.CALORIES, CardioMetric.DISTANCE, CardioMetric.SPEED, CardioMetric.INCLINE)
        )
        catalog.available = listOf(bench, plank, jumpingJacks, jogging, treadmill)
        repository.routine = WorkoutTemplate("routine", "owner", "Setup defaults", emptyList(), revision = 1)
        val setupCtx = context.copy(mode = RoutineFormMode.SETUP)
        val vm = editor(ctx = setupCtx)
        assertTrue(
            vm.acceptSelection(
                RoutineSelectionResult(
                    setupCtx,
                    "sel-all",
                    listOf(bench, plank, jumpingJacks, jogging, treadmill)
                )
            )
        )
        val configs = vm.state.value.rawConfigs
        assertEquals(3, configs.getValue(bench.id).setRows.size)
        assertEquals(3, configs.getValue(plank.id).setRows.size)
        assertEquals(3, configs.getValue(jumpingJacks.id).setRows.size)
        assertEquals(1, configs.getValue(jogging.id).setRows.size)
        assertEquals(1, configs.getValue(treadmill.id).setRows.size)
        configs.values.flatMap { it.setRows }.forEach { assertEquals("60", it.rest) }

        // Existing cardio equipment routine with 3 rows and rest 0 must not be reset to 1 row / 60s rest.
        repository.routine = WorkoutTemplate(
            id = "routine",
            userId = "owner",
            name = "Existing cardio",
            exercises = listOf(
                TemplateExercise(
                    exercise = treadmill,
                    sets = 3,
                    reps = null,
                    restSeconds = 0,
                    trackingType = TrackingType.DURATION,
                    targetDurationSeconds = 300,
                    setTargets = listOf(
                        SetTarget("t-1", 1, durationSeconds = 300, restSeconds = 0),
                        SetTarget("t-2", 2, durationSeconds = 600, restSeconds = 0),
                        SetTarget("t-3", 3, durationSeconds = 900, restSeconds = 0)
                    ),
                    measurementVersion = 3
                )
            ),
            revision = 2,
            measurementVersion = 3
        )
        val existingVm = editor()
        val existingRows = existingVm.state.value.rawConfigs.getValue(treadmill.id).setRows
        assertEquals(listOf("t-1", "t-2", "t-3"), existingRows.map { it.rowId })
        assertEquals(listOf("00:05:00", "00:10:00", "00:15:00"), existingRows.map { it.duration })
        assertEquals(listOf("0", "0", "0"), existingRows.map { it.rest })
    }

    @Test fun draftValidationEnforcesWeightRepsRestDurationAndRowCountBoundariesWhilePreservingInvalidRawInput() {
        val run = Exercise(
            id = "ex_cardio_1",
            name = "Run",
            muscleCategory = MuscleCategory.CARDIO,
            description = "",
            defaultTrackingType = TrackingType.DURATION,
            modality = ExerciseModality.CARDIO,
            allowedTrackingTypes = listOf(TrackingType.DURATION),
            trackingProfile = TrackingProfile.EQUIPMENT_METRICS,
            supportedMetrics = listOf(CardioMetric.DISTANCE)
        )
        catalog.available = listOf(bench, plank, run)
        repository.routine = WorkoutTemplate(
            id = "routine",
            userId = "owner",
            name = "Validation",
            exercises = listOf(
                v3Strength(bench, listOf(SetTarget("b-1", 1, 0.0, 10, restSeconds = 0))),
                TemplateExercise(plank, sets = 1, reps = null, trackingType = TrackingType.DURATION,
                    setTargets = listOf(SetTarget("p-1", 1, durationSeconds = 60, restSeconds = 60)), measurementVersion = 3)
            ),
            revision = 3,
            measurementVersion = 3
        )
        val handle = SavedStateHandle()
        val vm = editor(handle)
        assertTrue(vm.state.value.invalidConfigIds.isEmpty())

        // Invalid weight (blank, >1000, >2 decimals), reps (<1, >100), rest (<0, >600), duration (MM>59, >24h)
        vm.updateSetRow(bench.id, "b-1") { it.copy(weight = "1000.01", reps = "101", rest = "601") }
        vm.updateSetRow(plank.id, "p-1") { it.copy(duration = "00:60:00", rest = "-1") }
        assertEquals(setOf(bench.id, plank.id), vm.state.value.invalidConfigIds)

        // Survives picker round-trip and recreation
        assertTrue(vm.acceptSelection(RoutineSelectionResult(context, "add-run", listOf(run))))
        val restored = editor(recreate(handle))
        assertEquals("1000.01", restored.state.value.rawConfigs.getValue(bench.id).setRows.single().weight)
        assertEquals("00:60:00", restored.state.value.rawConfigs.getValue(plank.id).setRows.single().duration)
        assertEquals(setOf(bench.id, plank.id), restored.state.value.invalidConfigIds)
        restored.saveChanges()
        assertTrue(repository.commands.isEmpty())

        // Fix to boundary-valid values (weight 0 and 1000, reps 100, rest 0 and 600, duration 24:00:00)
        restored.updateSetRow(bench.id, "b-1") { it.copy(weight = "1000", reps = "100", rest = "600") }
        restored.updateSetRow(plank.id, "p-1") { it.copy(duration = "24:00:00", rest = "0") }
        assertTrue(restored.state.value.invalidConfigIds.isEmpty())
        restored.saveChanges()
        assertEquals(1, repository.commands.size)
    }

    @Test fun legacyScalarRoutinesExpandWithDeterministicRowIdsAndPlankFallbackWithoutInventingWeightOrCardioSeconds() {
        val jumpRope = Exercise(
            id = "ex_cardio_4",
            name = "Jump Rope",
            muscleCategory = MuscleCategory.CARDIO,
            description = "",
            defaultTrackingType = TrackingType.REPS_ONLY,
            modality = ExerciseModality.CARDIO,
            allowedTrackingTypes = listOf(TrackingType.REPS_ONLY, TrackingType.DURATION),
            trackingProfile = TrackingProfile.DURATION_ONLY,
            supportedMetrics = emptyList()
        )
        val plankExisting = plank.copy(id = "ex_core_existing", name = "Plank 90s")
        catalog.available = listOf(bench, plank, plankExisting, jumpRope)
        repository.routine = WorkoutTemplate(
            id = "routine",
            userId = "owner",
            name = "Legacy scalar",
            exercises = listOf(
                TemplateExercise(bench, sets = 2, reps = 8, restSeconds = 0),
                TemplateExercise(plank, sets = 2, reps = null, targetDurationSeconds = null, restSeconds = 45, trackingType = TrackingType.DURATION),
                TemplateExercise(plankExisting, sets = 1, reps = null, targetDurationSeconds = 90, restSeconds = 60, trackingType = TrackingType.DURATION),
                TemplateExercise(jumpRope, sets = 2, reps = 50, targetDurationSeconds = null, restSeconds = 30, trackingType = TrackingType.REPS_ONLY)
            ),
            revision = 4,
            measurementVersion = null
        )
        val vm = editor()
        val benchConfig = vm.state.value.rawConfigs.getValue(bench.id)
        assertEquals(2, benchConfig.setRows.size)
        assertEquals(
            LegacyTargetAdapter.expand("routine:${bench.id}", 2, 8, null, null, 0, TrackingType.WEIGHT_REPS).map { it.rowId },
            benchConfig.setRows.map { it.rowId }
        )
        assertTrue(benchConfig.setRows.all { it.weight.isEmpty() && it.reps == "8" && it.rest == "0" })
        assertTrue(bench.id in vm.state.value.invalidConfigIds)

        val plankUnknownRows = vm.state.value.rawConfigs.getValue(plank.id).setRows
        assertEquals(listOf("00:01:00", "00:01:00"), plankUnknownRows.map { it.duration })
        assertEquals(listOf("45", "45"), plankUnknownRows.map { it.rest })
        assertFalse(plank.id in vm.state.value.invalidConfigIds)

        val plankExistingRows = vm.state.value.rawConfigs.getValue(plankExisting.id).setRows
        assertEquals(listOf("00:01:30"), plankExistingRows.map { it.duration })

        // Legacy cardio REPS_ONLY does not convert 50 reps into 50 seconds; requires explicit HH:MM:SS duration.
        val jumpRopeRows = vm.state.value.rawConfigs.getValue(jumpRope.id).setRows
        assertEquals(2, jumpRopeRows.size)
        assertTrue(jumpRopeRows.all { it.duration.isEmpty() && it.reps.isEmpty() && it.rest == "30" })
        assertTrue(jumpRope.id in vm.state.value.invalidConfigIds)
    }

    @Test fun routineDetailTargetSummaryFormatsHomogeneousAndHeterogeneousTargetsWithoutWeightRestOrBadges() {
        val homStrength = v3Strength(
            bench,
            listOf(
                SetTarget("1", 1, 40.0, 8, restSeconds = 60),
                SetTarget("2", 2, 45.0, 8, restSeconds = 90),
                SetTarget("3", 3, 50.0, 8, restSeconds = 120)
            )
        )
        assertEquals("3 x 8 reps", homStrength.formatRoutineTargetSummary())

        val hetStrength = v3Strength(
            bench,
            listOf(
                SetTarget("1", 1, 40.0, 12, restSeconds = 60),
                SetTarget("2", 2, 45.0, 10, restSeconds = 60),
                SetTarget("3", 3, 50.0, 8, restSeconds = 60)
            )
        )
        assertEquals("3 set · 12/10/8 reps", hetStrength.formatRoutineTargetSummary())

        val homPlank = TemplateExercise(
            plank,
            sets = 3,
            reps = null,
            trackingType = TrackingType.DURATION,
            setTargets = listOf(
                SetTarget("1", 1, durationSeconds = 60, restSeconds = 60),
                SetTarget("2", 2, durationSeconds = 60, restSeconds = 60),
                SetTarget("3", 3, durationSeconds = 60, restSeconds = 60)
            ),
            measurementVersion = 3
        )
        assertEquals("3 set · 60 s", homPlank.formatRoutineTargetSummary())

        val hetPlank = homPlank.copy(
            setTargets = listOf(
                SetTarget("1", 1, durationSeconds = 60, restSeconds = 60),
                SetTarget("2", 2, durationSeconds = 90, restSeconds = 60),
                SetTarget("3", 3, durationSeconds = 60, restSeconds = 60)
            )
        )
        assertEquals("3 set · 60/90/60 s", hetPlank.formatRoutineTargetSummary())

        val bike = Exercise(
            "ex_cardio_2", "Stationary Bike", MuscleCategory.CARDIO, "",
            defaultTrackingType = TrackingType.DURATION, modality = ExerciseModality.CARDIO,
            trackingProfile = TrackingProfile.EQUIPMENT_METRICS, supportedMetrics = listOf(CardioMetric.DISTANCE)
        )
        val cardioMin = TemplateExercise(
            bike,
            sets = 1,
            reps = null,
            trackingType = TrackingType.DURATION,
            setTargets = listOf(SetTarget("1", 1, durationSeconds = 1800, restSeconds = 60)),
            measurementVersion = 3
        )
        assertEquals("1 set · 30 min", cardioMin.formatRoutineTargetSummary())

        val cardioHetSec = cardioMin.copy(
            sets = 2,
            setTargets = listOf(
                SetTarget("1", 1, durationSeconds = 45, restSeconds = 60),
                SetTarget("2", 2, durationSeconds = 90, restSeconds = 60)
            )
        )
        assertEquals("2 set · 45/90 s", cardioHetSec.formatRoutineTargetSummary())
    }
}

private data class EditorCommand(val routineId: String, val name: String,
    val exercises: List<TemplateExercise>, val revision: Long?, val requestId: String, val baselineIds: List<String>)

private class EditorRoutines(var routine: WorkoutTemplate) : RoutineRepository {
    var reads = 0
    val commands = mutableListOf<EditorCommand>()
    var read: suspend () -> Result<WorkoutTemplate?> = { Result.success(routine) }
    var save: suspend (EditorCommand) -> Result<WorkoutTemplate> = { Result.success(routine.copy(name = it.name,
        exercises = it.exercises, revision = routine.revision + 1)) }
    override suspend fun getRoutines(userId: String) = Result.success(listOf(routine))
    override suspend fun getRoutineById(routineId: String): Result<WorkoutTemplate?> { reads++; return read() }
    override suspend fun createRoutine(userId: String, name: String, requestId: String): Result<WorkoutTemplate> = error("Not an editor operation")
    override suspend fun saveRoutine(routineId: String, name: String, exercises: List<TemplateExercise>, expectedRevision: Long?,
        requestId: String, baselineExerciseIds: List<String>): Result<WorkoutTemplate> {
        val command = EditorCommand(routineId, name, exercises, expectedRevision, requestId, baselineExerciseIds)
        commands += command
        return save(command)
    }
    override suspend fun deleteRoutine(routineId: String, expectedRevision: Long?, requestId: String,
        baselineExerciseIds: List<String>, name: String): Result<Boolean> = error("Not an editor operation")
}

private class EditorCatalog(var available: List<Exercise>) : ExerciseCatalogRepository {
    val requests = mutableListOf<List<String>>()
    override suspend fun fetchCatalogPage(search: String, categoryId: Int?, cursor: String?, limit: Int): Result<ExerciseCatalogPage> = error("Editor must use targeted hydration")
    override suspend fun getExercisesByIds(ids: List<String>): ExerciseHydrationResult {
        requests += ids.toList()
        // Deliberately reverse server order: the consumer, not this fake, must restore order.
        val found = available.filter { it.id in ids }.reversed()
        val missing = ids.filterNot { id -> found.any { it.id == id } }
        return if (missing.isEmpty()) ExerciseHydrationResult.Success(found) else ExerciseHydrationResult.Missing(found, missing)
    }
}

private class EditorAuth : AuthRepository {
    private val current = MutableStateFlow<User?>(User("owner", "Owner", "owner@example.test"))
    private val profile = MutableStateFlow(UserProfileState.fromDirectUser(current.value))
    override val userProfileState: StateFlow<UserProfileState> = profile
    fun switch(id: String?) { current.value = id?.let { User(it, "Account", "$it@example.test") }; profile.value = UserProfileState.fromDirectUser(current.value) }
    override fun observeCurrentUser() = current
    override suspend fun getCurrentUser() = current.value
    override suspend fun getLocalUserId() = current.value?.id
    override suspend fun peekLocalUserId() = current.value?.id
    override suspend fun refreshProfile(force: Boolean) = Result.success(current.value)
    override suspend fun register(name: String, email: String, password: String, birthDate: String?, profilePhotoUri: String?): Result<RegisterResult> = error("Unused")
    override suspend fun login(email: String, password: String): Result<User> = error("Unused")
    override suspend fun logout() { switch(null) }
    override suspend fun isLoggedIn() = current.value != null
    override suspend fun updateProfile(user: User) = Result.success(user)
    override suspend fun getSignedAvatarUrl(storagePath: String?) = storagePath
}

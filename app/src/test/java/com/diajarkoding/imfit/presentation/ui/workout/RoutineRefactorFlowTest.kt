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
    private val plank = Exercise("ex_core_1", "Renamed static exercise", MuscleCategory.CORE, "Hold")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val context = RoutineFlowContext("flow", "routine", "owner", RoutineFormMode.EDIT)
    private val auth = EditorAuth()
    private val catalog = EditorCatalog(listOf(bench, row, plank))
    private val repository = EditorRoutines(WorkoutTemplate("routine", "owner", "Original",
        listOf(TemplateExercise(bench, 3, 8, 60), TemplateExercise(row, 4, 12, 90)), revision = 7))

    @After fun close() { scope.cancel() }

    private fun editor(handle: SavedStateHandle = SavedStateHandle(), ctx: RoutineFlowContext = context): EditWorkoutViewModel =
        EditWorkoutViewModel(repository, catalog, auth, handle, scope).also {
            it.initialize(ctx.routineId, ctx.mode, ctx.flowId, ctx.ownerId)
        }

    private fun recreate(handle: SavedStateHandle) = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })

    @Test fun rawInvalidTextOrderMembershipAndBaselineSurviveRealConsumerRecreation() {
        val handle = SavedStateHandle()
        val original = editor(handle)
        original.updateWorkoutName("My raw draft")
        original.updateRawConfig(bench.id, "", "-1.5", "99999999999999")
        original.moveExerciseDown(0)
        val restored = editor(recreate(handle))
        assertEquals(1, repository.reads)
        assertEquals(listOf(row.id, bench.id), catalog.requests.last())
        assertEquals(listOf(row.id, bench.id), restored.state.value.exercises.map { it.id })
        assertEquals(RawExerciseConfig("", "-1.5", "99999999999999"), restored.state.value.rawConfigs[bench.id])
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
        vm.updateRawConfig(bench.id, "0", "", "-3")
        vm.moveExerciseDown(0)
        vm.moveExerciseUp(1)
        assertEquals(RawExerciseConfig("0", "", "-3"), vm.state.value.rawConfigs[bench.id])
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
        vm.updateRawConfig(row.id, "", "12", "60")
        vm.removeExercise(1)
        val restored = editor(recreate(handle), ctx)
        assertTrue(restored.acceptSelection(result))
        assertEquals(RoutineFormMode.SETUP, restored.state.value.mode)
        assertEquals(listOf(row.id), restored.state.value.exercises.map { it.id })
        assertEquals("", restored.state.value.rawConfigs[row.id]?.sets)
        assertFalse(restored.acceptSelection(result.copy(context = ctx.copy(flowId = "other"))))
        assertFalse(restored.acceptSelection(result.copy(context = ctx.copy(ownerId = "other"))))
    }

    @Test fun unknownSaveRecreationAndRetryKeepExactRequestPayloadAndFreezeDraft() {
        val handle = SavedStateHandle()
        repository.save = { Result.failure(RoutineError.Network("Response lost after commit")) }
        val vm = editor(handle)
        vm.updateWorkoutName("Immutable command")
        vm.removeExercise(0)
        vm.updateRawConfig(row.id, "5", "9", "30")
        vm.saveChanges()
        val first = repository.commands.single()
        assertTrue(vm.state.value.hasPendingSave)
        vm.dismissErrorDialog()
        vm.updateWorkoutName("Must not change")
        vm.updateRawConfig(row.id, "99", "99", "99")
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

    @Test fun durationCapabilityUsesSeedIdentityAndBlocksConfigurationAndSave() {
        assertEquals(ExercisePrescriptionCapability.UNSUPPORTED_DURATION_DISTANCE, plank.prescriptionCapability)
        assertEquals(ExercisePrescriptionCapability.REPS_LOAD, bench.copy(name = "Plank").prescriptionCapability)
        assertEquals(ExercisePrescriptionCapability.REPS_LOAD,
            Exercise("ex_cardio_5", "Burpees", MuscleCategory.CARDIO, "").prescriptionCapability)
        listOf("ex_core_9", "ex_cardio_1", "ex_cardio_2", "ex_cardio_3", "ex_cardio_4", "ex_cardio_6", "ex_cardio_7", "ex_cardio_9").forEach {
            assertEquals(ExercisePrescriptionCapability.UNSUPPORTED_DURATION_DISTANCE,
                Exercise(it, "Any translated name", MuscleCategory.CHEST, "").prescriptionCapability)
        }
        val vm = editor()
        vm.acceptSelection(RoutineSelectionResult(context, "duration", listOf(plank)))
        assertEquals(RawExerciseConfig("", "", ""), vm.state.value.rawConfigs[plank.id])
        vm.updateRawConfig(plank.id, "3", "45", "60")
        assertEquals("", vm.state.value.rawConfigs[plank.id]?.reps)
        assertTrue(plank.id in vm.state.value.invalidConfigIds)
        vm.saveChanges()
        assertTrue(repository.commands.isEmpty())
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

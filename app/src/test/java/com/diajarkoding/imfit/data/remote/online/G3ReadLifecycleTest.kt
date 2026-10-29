package com.diajarkoding.imfit.data.remote.online

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import com.diajarkoding.imfit.domain.model.Exercise
import com.diajarkoding.imfit.domain.model.MuscleCategory
import com.diajarkoding.imfit.presentation.ui.exercise.*
import com.diajarkoding.imfit.presentation.ui.profile.ProfileViewModel
import com.diajarkoding.imfit.presentation.ui.progress.RemoteHistoryViewModel
import com.diajarkoding.imfit.presentation.ui.progress.ProgressViewModel
import com.diajarkoding.imfit.presentation.ui.workout.EditWorkoutViewModel
import com.diajarkoding.imfit.presentation.ui.workout.WorkoutDetailViewModel
import com.diajarkoding.imfit.presentation.ui.workout.WorkoutSummaryViewModel
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

@OptIn(ExperimentalCoroutinesApi::class)
class G3ReadLifecycleTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val reads = ScriptedReadGateway()
    @Before fun main() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { store.clear(); reads.repository.close(); Dispatchers.setMain(Dispatchers.Unconfined) }
    private fun <T : ViewModel> keep(vm: T): T { store.put(vm.javaClass.name, vm); return vm }
    private fun FakeOnlineApi.Request.template(id: String = "template", name: String = "Routine", exercises: String = "[]") =
        reply("""{"id":"$id","name":"$name","revision":7,"updated_at":"${OnlineFixtures.time}","exercises":$exercises}""")
    private fun FakeOnlineApi.Request.catalog(vararg ids: String) = reply(onlineJson.encodeToString(
        RemotePage(ids.map { ExerciseSummary(it, 1, it, null, null, null) }, PageInfo(null, false, 25))))

    @Test fun `auth publishes identity only after all reset listeners even with immediate collectors`() = runTest(dispatcher) {
        val auth = OnlineAuth()
        val events = mutableListOf<String>()
        val first = auth.onIdentityChanged { events += "reset-one:${it?.ownerId}" }
        val second = auth.onIdentityChanged { events += "reset-two:${it?.ownerId}" }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            auth.identity.collect { events += "identity:${it?.ownerId}" }
        }
        events.clear()
        auth.signIn("A", "token")
        assertEquals(listOf("reset-one:A", "reset-two:A", "identity:A"), events)
        events.clear(); auth.signOut()
        assertEquals(listOf("reset-one:null", "reset-two:null", "identity:null"), events)
        first.close(); second.close()
    }

    @Test fun `picker context error survives search and filter and pull refresh retries context first`() = runTest(dispatcher) {
        val vm = keep(ExerciseSelectionViewModel(reads))
        vm.initialize("template")
        reads.api.requests.receive().loseResponse(); runCurrent()
        assertNotNull(vm.state.value.errorRes)
        vm.onSearchQueryChange("bench"); vm.selectCategory(ExerciseCategory.CHEST); runCurrent()
        assertNotNull(vm.state.value.errorRes)
        assertFalse(vm.state.value.isLoading)
        assertTrue(reads.api.requests.tryReceive().isFailure)
        vm.refresh()
        reads.api.requests.receive().also { assertEquals("get_template_detail", it.rpc); it.template() }
        reads.api.requests.receive().also { assertEquals("get_exercise_catalog_page", it.rpc); it.catalog("bench") }
        runCurrent()
        assertNull(vm.state.value.errorRes)
        assertEquals("bench", vm.state.value.filteredExercises.single().id)
    }

    @Test fun `picker A B A reloads owner context without route recreation and clears hidden selection`() = runTest(dispatcher) {
        val vm = keep(ExerciseSelectionViewModel(reads)); vm.initialize("template")
        reads.api.requests.receive().template()
        reads.api.requests.receive().catalog("selected"); runCurrent()
        vm.toggleExercise(vm.state.value.filteredExercises.single())
        val epoch = reads.auth.capture().identity
        reads.auth.signOut(); reads.auth.signIn("B", "B-token"); reads.auth.signIn("A", "new-A")
        assertTrue(vm.state.value.selectedExercises.isEmpty())
        assertTrue(vm.state.value.filteredExercises.isEmpty())
        val pending = mutableListOf<FakeOnlineApi.Request>()
        runCurrent()
        while (true) pending += reads.api.requests.tryReceive().getOrNull() ?: break
        // The catalog controller may restart its query concurrently, but only the new context
        // may release rows. No result can resurrect a selection from the first A epoch.
        assertTrue(pending.any { it.rpc == "get_template_detail" })
        pending.forEach { request ->
            assertNotEquals(epoch, request.credential.identity)
            if (request.rpc == "get_template_detail") request.template(name = "New owner context") else request.catalog("fresh")
        }
        runCurrent()
        assertEquals("New owner context", vm.state.value.routineName)
        assertTrue(vm.state.value.selectedExercises.isEmpty())
        assertEquals(listOf("fresh"), vm.state.value.filteredExercises.map { it.id })
    }

    @Test fun `picker changed caller exclusions reuse loaded page but never old selection or flags`() = runTest(dispatcher) {
        val vm = keep(ExerciseSelectionViewModel(reads)); vm.initialize("same", emptySet(), "First")
        reads.api.requests.receive().catalog("one", "two"); runCurrent()
        vm.toggleExercise(vm.state.value.filteredExercises.first())
        vm.initialize("same", setOf("one"), "Second"); runCurrent()
        assertEquals("Second", vm.state.value.routineName)
        assertEquals(listOf("two"), vm.state.value.filteredExercises.map { it.id })
        assertTrue(vm.state.value.selectedExercises.isEmpty())
        assertFalse(vm.state.value.isLoading)
        assertTrue(vm.state.value.endReached)
        assertTrue(reads.api.requests.tryReceive().isFailure)
    }

    @Test fun `picker cannot reuse an A caller name or exclusions for B via automatic reload or retry`() = runTest(dispatcher) {
        val vm = keep(ExerciseSelectionViewModel(reads)); vm.initialize("draft", emptySet(), "A private draft")
        reads.api.requests.receive().catalog("one"); runCurrent()
        reads.auth.signOut(); reads.auth.signIn("B", "B"); runCurrent()
        // Catalog is public to authenticated users, but B must not inherit A's caller context.
        reads.api.requests.tryReceive().getOrNull()?.catalog("public-row"); runCurrent()
        assertTrue(vm.state.value.routineName.isBlank())
        assertTrue(vm.state.value.filteredExercises.isEmpty())
        vm.retry(); vm.refresh(); runCurrent()
        assertTrue(reads.api.requests.tryReceive().isFailure)
        assertNotNull(vm.state.value.errorRes)
    }

    @Test fun `profile delayed A response cannot republish after A B A or refresh twice on retained load`() = runTest(dispatcher) {
        val profiles = Channel<CompletableDeferred<RemoteProfile>>(Channel.UNLIMITED)
        val gateway = object : RemoteReadGateway by reads {
            override suspend fun profile(): RemoteProfile {
                val response = CompletableDeferred<RemoteProfile>(); profiles.send(response)
                return withContext(NonCancellable) { response.await() }
            }
        }
        val vm = keep(ProfileViewModel(gateway, mockk(relaxed = true), OnlineMutationChanges(gateway.auth), idleSessionStore()))
        val old = profiles.receive()
        reads.auth.signOut(); reads.auth.signIn("B", "B"); reads.auth.signIn("A", "new-A"); runCurrent()
        val fresh = profiles.receive()
        fresh.complete(RemoteProfile("A", "Fresh", "fresh@example.invalid", null, null)); runCurrent()
        old.complete(RemoteProfile("A", "Obsolete", "obsolete@example.invalid", null, null)); runCurrent()
        assertEquals("Fresh", vm.state.value.data!!.name)
        vm.load(); vm.load(); runCurrent(); assertTrue(profiles.tryReceive().isFailure)
    }

    @Test fun `template table lookup which ignores cancellation cannot overwrite a new editor query`() = runTest(dispatcher) {
        val lookups = Channel<CompletableDeferred<List<Exercise>>>(Channel.UNLIMITED)
        val gateway = object : RemoteReadGateway by reads {
            override suspend fun template(id: String) = super<RemoteReadGateway>.template(id)
            override suspend fun exercises(ids: List<String>): List<Exercise> {
                val response = CompletableDeferred<List<Exercise>>(); lookups.send(response)
                return withContext(NonCancellable) { response.await() }
            }
        }
        val vm = keep(EditWorkoutViewModel(SavedStateHandle(), gateway, TemplateEditBridge(gateway, LegacyTemplateGuard { _, _ -> })))
        vm.initialize("old")
        reads.api.requests.receive().template("old", "Old")
        val old = lookups.receive()
        vm.initialize("new")
        reads.api.requests.receive().template("new", "Fresh")
        lookups.receive().complete(emptyList()); runCurrent()
        old.complete(emptyList()); runCurrent()
        assertEquals("Fresh", vm.state.value.workoutName)
        vm.initialize("new"); runCurrent(); assertTrue(reads.api.requests.tryReceive().isFailure)
    }

    @Test fun `editor retained route reloads after auth epoch change and never restores A draft into B`() = runTest(dispatcher) {
        val vm = keep(EditWorkoutViewModel(SavedStateHandle(), reads, TemplateEditBridge(reads, LegacyTemplateGuard { _, _ -> })))
        vm.initialize("template"); reads.api.requests.receive().template(name = "A routine"); runCurrent()
        val oldSourceRevision = vm.state.value.sourceRevision
        vm.updateWorkoutName("A dirty draft")
        reads.auth.signOut(); reads.auth.signIn("B", "B-token"); runCurrent()
        assertEquals("", vm.state.value.workoutName)
        reads.api.requests.receive().also { assertEquals("B", it.credential.identity.ownerId); it.template(name = "B routine") }
        runCurrent(); assertEquals("B routine", vm.state.value.workoutName)
        assertTrue("Raw rememberSaveable fields need a new key even if auth-reset emissions are conflated",
            vm.state.value.sourceRevision > oldSourceRevision)
    }

    @Test fun `detail retained route reinitialization does not fetch and account change reloads remote`() = runTest(dispatcher) {
        val vm = keep(WorkoutDetailViewModel(mockk(relaxed = true), SavedStateHandle(), reads,
            TemplateEditBridge(reads, LegacyTemplateGuard { _, _ -> })))
        vm.initialize("template"); reads.api.requests.receive().template(); runCurrent()
        vm.initialize("template"); runCurrent(); assertTrue(reads.api.requests.tryReceive().isFailure)
        reads.auth.signOut(); reads.auth.signIn("B", "B"); runCurrent()
        assertNull(vm.state.value.workout)
        reads.api.requests.receive().template(name = "B routine"); runCurrent()
        assertEquals("B routine", vm.state.value.workout!!.name)
    }

    @Test fun `history day switch rejects delayed summaries and duplicate initialize never reloads`() = runTest(dispatcher) {
        val vm = keep(RemoteHistoryViewModel(reads, OnlineMutationChanges(reads.auth)))
        vm.initialize(LocalDate.of(2026, 10, 1))
        val old = listOf(reads.api.requests.receive(), reads.api.requests.receive())
        vm.initialize(LocalDate.of(2026, 10, 2))
        val fresh = listOf(reads.api.requests.receive(), reads.api.requests.receive())
        fresh.forEach { it.replyHistory("2026-10-02", "2026-10-03") }; runCurrent()
        old.forEach { it.replyHistory("2026-10-01", "2026-10-02") }; runCurrent()
        assertEquals("2026-10-02", vm.totals.value.data!!.startDate)
        vm.initialize(LocalDate.of(2026, 10, 2)); runCurrent(); assertTrue(reads.api.requests.tryReceive().isFailure)
    }

    @Test fun `workout summary retains failed refresh and clears it immediately on query change`() = runTest(dispatcher) {
        val vm = keep(WorkoutSummaryViewModel(reads))
        vm.loadWorkoutLog("workout")
        reads.api.requests.receive().reply("""${OnlineFixtures.summary.dropLast(1)},"exercise_logs":[]}"""); runCurrent()
        vm.refresh(); reads.api.requests.receive().loseResponse(); runCurrent()
        assertTrue(vm.state.value.stale); assertNotNull(vm.state.value.data)
        vm.loadWorkoutLog("other"); assertNull(vm.state.value.data)
        reads.api.requests.receive().reply("{}", 404); runCurrent()
        assertNotNull(vm.state.value.error); assertNull(vm.state.value.data)
    }

    @Test fun `progress reverse completion and A B A cannot mix aggregate or markers from old query`() = runTest(dispatcher) {
        reads.api.ignoreCancellation = true
        val vm = keep(ProgressViewModel(reads, mockk(relaxed = true), OnlineMutationChanges(reads.auth)))
        vm.selectMonth(YearMonth.of(2026, 10))
        val october = listOf(reads.api.requests.receive(), reads.api.requests.receive())
        vm.selectMonth(YearMonth.of(2026, 11))
        val november = listOf(reads.api.requests.receive(), reads.api.requests.receive())
        november.forEach { it.replyPeriod("2026-11-01", "2026-12-01") }; runCurrent()
        october.forEach { it.replyPeriod("2026-10-01", "2026-11-01") }; runCurrent()
        assertEquals("2026-11-01", vm.state.value.aggregate!!.startDate)
        assertEquals(setOf(LocalDate.of(2026, 11, 1)), vm.state.value.markerCounts.keys)
        vm.selectMonth(YearMonth.of(2026, 11)); runCurrent(); assertTrue(reads.api.requests.tryReceive().isFailure)
        vm.loadData()
        val oldA = listOf(reads.api.requests.receive(), reads.api.requests.receive())
        reads.auth.signOut(); reads.auth.signIn("B", "B"); reads.auth.signIn("A", "new-A"); runCurrent()
        assertNull(vm.state.value.aggregate); assertTrue(vm.state.value.markerCounts.isEmpty())
        val newA = listOf(reads.api.requests.receive(), reads.api.requests.receive())
        newA.forEach { it.replyPeriod("2026-11-01", "2026-12-01") }; runCurrent()
        oldA.forEach { it.replyPeriod("2026-10-01", "2026-11-01") }; runCurrent()
        assertEquals("2026-11-01", vm.state.value.aggregate!!.startDate)
        assertEquals(setOf(LocalDate.of(2026, 11, 1)), vm.state.value.markerCounts.keys)
    }

    @Test fun `catalog retained route restarts after A B A but repeated initialize never refetches`() = runTest(dispatcher) {
        val vm = keep(ExerciseBrowserViewModel(reads)); vm.initialize(ExerciseCategory.ALL)
        reads.api.requests.receive().catalog("old"); runCurrent()
        reads.auth.signOut(); reads.auth.signIn("B", "B"); reads.auth.signIn("A", "new-A"); runCurrent()
        assertTrue(vm.state.value.results.isEmpty())
        reads.api.requests.receive().catalog("fresh"); runCurrent()
        assertEquals(listOf("fresh"), vm.state.value.results.map { it.id })
        vm.initialize(ExerciseCategory.ALL); vm.initialize(ExerciseCategory.ALL); runCurrent()
        assertTrue(reads.api.requests.tryReceive().isFailure)
    }

    private fun FakeOnlineApi.Request.replyPeriod(start: String, end: String) {
        if (rpc == "get_workout_date_markers") reply("""{"start_date":"$start","end_date":"$end","timezone":"UTC","dates":[{"date":"$start","workout_count":602}]}""")
        else replyHistory(start, end)
    }

    private fun FakeOnlineApi.Request.replyHistory(start: String, end: String) {
        if (rpc == "get_workout_history_page") reply(OnlineFixtures.emptyPage)
        else reply("""{"start_date":"$start","end_date":"$end","timezone":"UTC","workout_count":602,"total_volume":"602.005","total_sets":602,"total_reps":602,"duration_seconds":100000}""")
    }
}

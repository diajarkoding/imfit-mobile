package com.diajarkoding.imfit.data.remote.online

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import com.diajarkoding.imfit.data.remote.TemplateWriteReceipt
import com.diajarkoding.imfit.data.remote.TemplateWriteRequest
import com.diajarkoding.imfit.domain.model.Exercise
import com.diajarkoding.imfit.domain.model.MuscleCategory
import com.diajarkoding.imfit.domain.model.TemplateExercise
import com.diajarkoding.imfit.presentation.ui.exercise.*
import com.diajarkoding.imfit.presentation.ui.profile.ProfileViewModel
import com.diajarkoding.imfit.presentation.ui.progress.ProgressViewModel
import com.diajarkoding.imfit.presentation.ui.workout.EditWorkoutViewModel
import com.diajarkoding.imfit.presentation.ui.home.HomeViewModel
import com.diajarkoding.imfit.presentation.ui.progress.RemoteHistoryViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.YearMonth

internal class ScriptedReadGateway : RemoteReadGateway {
    override val auth = OnlineAuth().also { it.signIn("A", "token-A") }
    val api = FakeOnlineApi()
    val repository = OnlineRepository(api, auth)
    var profileCalls = 0
    var failProfile = false
    override suspend fun profile(): RemoteProfile {
        profileCalls++
        if (failProfile) throw OnlineFailure(RemoteError.Network)
        return RemoteProfile(auth.capture().identity.ownerId, "Fixture name", "fixture@example.invalid", null, null)
    }
    override suspend fun exercises(ids: List<String>) = ids.map { Exercise(it, "Fixture $it", MuscleCategory.CHEST, "Synthetic") }
    override suspend fun <T> read(call: RpcCall<T>): T = repository.execute(call)
    override suspend fun saveTemplate(request: TemplateWriteRequest): TemplateWriteReceipt = repository.execute(OnlineRpc.saveTemplate(request))
}

@OptIn(ExperimentalCoroutinesApi::class)
class G3RemoteReadTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val reads = ScriptedReadGateway()
    @Before fun main() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { store.clear(); reads.repository.close(); Dispatchers.setMain(Dispatchers.Unconfined) }
    private fun <T : ViewModel> keep(vm: T): T { store.put(vm.javaClass.name, vm); return vm }
    private fun FakeOnlineApi.Request.catalog(ids: List<String>, next: String? = null) = reply(onlineJson.encodeToString(
        RemotePage(ids.map { ExerciseSummary(it, 1, "Fixture $it", null, null, null) }, PageInfo(next, next != null, body.getValue("p_limit").jsonPrimitive.int))))

    @Test fun `catalog VM uses remote query pagination and no reload on equivalent query or initialize`() = runTest(dispatcher) {
        val vm = keep(ExerciseBrowserViewModel(reads))
        vm.initialize(ExerciseCategory.ALL)
        reads.api.requests.receive().also { assertEquals("get_exercise_catalog_page", it.rpc); it.catalog((1..25).map(Int::toString), "next") }
        runCurrent()
        assertEquals(25, vm.state.value.results.size)
        vm.initialize(ExerciseCategory.ALL); vm.setQuery("   "); runCurrent()
        assertTrue(reads.api.requests.tryReceive().isFailure)
        vm.access("25")
        reads.api.requests.receive().also { assertEquals("next", it.body["p_cursor"]!!.jsonPrimitive.content); it.catalog(listOf("25", "26")) }
        runCurrent()
        assertEquals(26, vm.state.value.results.size)
        assertEquals(1, vm.state.value.results.count { it.id == "25" })
        assertEquals(0, reads.profileCalls)
    }

    @Test fun `catalog query change clears rows immediately and debounces final search`() = runTest(dispatcher) {
        val vm = keep(ExerciseBrowserViewModel(reads)); vm.initialize(ExerciseCategory.CHEST)
        reads.api.requests.receive().catalog(listOf("old")); runCurrent()
        vm.setQuery(" B ")
        assertTrue(vm.state.value.results.isEmpty())
        runCurrent(); advanceTimeBy(200)
        vm.setQuery(" BENCH "); runCurrent(); advanceTimeBy(299); runCurrent()
        assertTrue(reads.api.requests.tryReceive().isFailure)
        advanceTimeBy(1); runCurrent()
        reads.api.requests.receive().also {
            assertEquals("bench", it.body["p_search"]!!.jsonPrimitive.content)
            assertEquals(1, it.body["p_category_id"]!!.jsonPrimitive.int)
            it.catalog(listOf("new"))
        }
        runCurrent(); assertEquals("new", vm.state.value.results.single().id)
    }

    @Test fun `picker preserves hidden selection exclusions and resets it on auth epoch change`() = runTest(dispatcher) {
        val vm = keep(ExerciseSelectionViewModel(reads))
        vm.initialize("fixture-context", setOf("excluded"), "Fixture routine")
        reads.api.requests.receive().catalog(listOf("excluded", "selected", "other")); runCurrent()
        assertFalse(vm.state.value.filteredExercises.any { it.id == "excluded" })
        vm.toggleExercise(vm.state.value.filteredExercises.first { it.id == "selected" })
        vm.onSearchQueryChange("different"); runCurrent(); advanceTimeBy(300); runCurrent()
        reads.api.requests.receive().catalog(listOf("different")); runCurrent()
        assertEquals(listOf("selected"), vm.state.value.selectedExercises.map { it.id })
        vm.refresh(); reads.api.requests.receive().catalog(emptyList()); runCurrent()
        assertEquals(listOf("selected"), vm.state.value.selectedExercises.map { it.id })
        reads.auth.signOut()
        assertTrue(vm.state.value.selectedExercises.isEmpty())
        assertTrue(vm.state.value.filteredExercises.isEmpty())
    }

    @Test fun `picker failed exclusion snapshot retries same context instead of searching incomplete data`() = runTest(dispatcher) {
        val vm = keep(ExerciseSelectionViewModel(reads))
        vm.initialize("template")
        reads.api.requests.receive().also { assertEquals("get_template_detail", it.rpc); it.loseResponse() }
        runCurrent(); assertNotNull(vm.state.value.errorRes)
        assertTrue(reads.api.requests.tryReceive().isFailure)
        vm.retry()
        reads.api.requests.receive().reply("""{"id":"template","name":"Routine","revision":1,"updated_at":"${OnlineFixtures.time}","exercises":[]}""")
        reads.api.requests.receive().catalog(listOf("available")); runCurrent()
        assertNull(vm.state.value.errorRes)
        assertEquals("available", vm.state.value.filteredExercises.single().id)
    }

    @Test fun `profile VM is independent from history and failed refresh retains stale profile`() = runTest(dispatcher) {
        val vm = keep(ProfileViewModel(reads, mockk(relaxed = true)))
        runCurrent()
        assertEquals("Fixture name", vm.state.value.data!!.name)
        assertEquals(1, reads.profileCalls)
        assertTrue(reads.api.requests.tryReceive().isFailure)
        reads.failProfile = true; vm.refresh(); runCurrent()
        assertTrue(vm.state.value.stale)
        assertEquals(RemoteError.Network, vm.state.value.error)
        assertEquals("Fixture name", vm.state.value.data!!.name)
        reads.auth.signOut(); assertNull(vm.state.value.data)
    }

    @Test fun `progress uses complete server aggregate and markers without loading history pages`() = runTest(dispatcher) {
        val vm = keep(ProgressViewModel(reads, mockk(relaxed = true)))
        vm.selectMonth(YearMonth.of(2026, 10))
        val requests = listOf(reads.api.requests.receive(), reads.api.requests.receive())
        assertEquals(setOf("get_progress_summary", "get_workout_date_markers"), requests.map { it.rpc }.toSet())
        requests.forEach { request ->
            if (request.rpc == "get_progress_summary") request.reply("""{"start_date":"2026-10-01","end_date":"2026-11-01","timezone":"UTC","workout_count":602,"total_volume":"652.605","total_sets":602,"total_reps":611,"duration_seconds":36000}""")
            else request.reply("""{"start_date":"2026-10-01","end_date":"2026-11-01","timezone":"UTC","dates":[{"date":"2026-10-01","workout_count":602}]}""")
        }
        runCurrent()
        assertEquals(602L, vm.state.value.aggregate!!.workoutCount)
        assertEquals("652.605", vm.state.value.aggregate!!.totalVolume)
        assertEquals(602L, vm.state.value.markerCounts[java.time.LocalDate.of(2026, 10, 1)])
        assertTrue(vm.state.value.workoutLogsByDate.isEmpty())
        assertTrue(reads.api.requests.tryReceive().isFailure)
    }

    @Test fun `detail resource keeps stale result only for same query and ignores uncancellable old response`() = runTest(dispatcher) {
        val resource = RemoteResource<String>(reads.auth, backgroundScope)
        resource.load("one") { "confirmed" }; runCurrent()
        resource.load("one", true) { throw OnlineFailure(RemoteError.Network) }; runCurrent()
        assertEquals("confirmed", resource.state.value.data); assertTrue(resource.state.value.stale)
        val old = CompletableDeferred<String>()
        resource.load("two") { withContext(NonCancellable) { old.await() } }; runCurrent()
        assertNull(resource.state.value.data)
        resource.load("three") { "new" }; runCurrent()
        old.complete("obsolete"); runCurrent()
        assertEquals("new", resource.state.value.data)
        resource.close()
    }

    @Test fun `editor reload never overwrites dirty draft or invalid config`() = runTest(dispatcher) {
        val edits = TemplateEditBridge(reads, LegacyTemplateGuard { _, _ -> })
        val vm = keep(EditWorkoutViewModel(SavedStateHandle(), reads, edits))
        vm.initialize("template")
        reads.api.requests.receive().reply("""{"id":"template","name":"Routine","revision":2147483648,"updated_at":"${OnlineFixtures.time}","exercises":[{"id":"child","exercise_id":"squat","order_index":0,"sets":3,"reps":10,"rest_seconds":60}]}""")
        runCurrent(); vm.updateWorkoutName("Unsaved draft"); vm.setConfigValidity("squat", false); vm.loadWorkout(); runCurrent()
        assertEquals("Unsaved draft", vm.state.value.workoutName)
        assertTrue(vm.state.value.invalidConfigIds.contains("squat"))
        assertTrue(reads.api.requests.tryReceive().isFailure)
    }

    @Test fun `template bridge uses displayed Long revision and explicit removed IDs not Room projection`() = runTest(dispatcher) {
        val edits = TemplateEditBridge(reads, LegacyTemplateGuard { _, _ -> })
        val source = TemplateDetail("template", "Routine", 2147483648, OnlineFixtures.time,
            listOf(TemplateChild("child", "squat", 0, 3, 10, 60)))
        val save = async { edits.save(source, "Renamed", emptyList()) }
        val call = reads.api.requests.receive()
        assertEquals(2147483648L, call.body["p_expected_revision"]!!.jsonPrimitive.long)
        assertEquals(listOf("squat"), call.body["p_removed_exercise_ids"]!!.jsonArray.map { it.jsonPrimitive.content })
        call.reply("""{"request_id":${call.body["p_request_id"]},"template_id":"template","revision":2147483650,"exercise_count":0,"server_updated_at":"${OnlineFixtures.time}"}""")
        assertEquals(2147483650L, save.await().revision)
    }

    @Test fun `legacy intent guard blocks template RPC and never acknowledges pending data`() = runTest(dispatcher) {
        val edits = TemplateEditBridge(reads, LegacyTemplateGuard { _, _ -> throw OnlineFailure(RemoteError.Conflict("LEGACY_REVIEW_REQUIRED", null)) })
        val result = runCatching { edits.save(null, "Routine", emptyList()) }.exceptionOrNull() as OnlineFailure
        assertEquals(RemoteError.Conflict("LEGACY_REVIEW_REQUIRED", null), result.reason)
        assertTrue(reads.api.requests.tryReceive().isFailure)
    }

    @Test fun `template response loss reuses exact request and rejects changed draft`() = runTest(dispatcher) {
        val edits = TemplateEditBridge(reads, LegacyTemplateGuard { _, _ -> })
        val first = async { runCatching { edits.save(null, "Routine", emptyList()) }.exceptionOrNull() }
        val sent = reads.api.requests.receive(); sent.loseResponse()
        assertEquals(RemoteError.UnknownOutcome, (first.await() as OnlineFailure).reason)
        val changed = runCatching { edits.save(null, "Changed", emptyList()) }.exceptionOrNull() as OnlineFailure
        assertEquals(RemoteError.UnresolvedMutation, changed.reason)
        val retry = async { edits.save(null, "Routine", emptyList()) }
        val replay = reads.api.requests.receive(); assertEquals(sent.body, replay.body)
        replay.reply("""{"request_id":${sent.body["p_request_id"]},"template_id":${sent.body["p_template_id"]},"revision":1,"exercise_count":0,"server_updated_at":"${OnlineFixtures.time}"}""")
        retry.await()
    }

    @Test fun `home reads bounded summaries retains stale error and cannot republish logged-out owner`() = runTest(dispatcher) {
        val sync = object : com.diajarkoding.imfit.data.sync.SyncStateProvider {
            override val syncState = MutableStateFlow(com.diajarkoding.imfit.data.sync.SyncState())
        }
        val vm = keep(HomeViewModel(mockk(relaxed = true), mockk(relaxed = true), sync, mockk(relaxed = true), reads,
            TemplateEditBridge(reads, LegacyTemplateGuard { _, _ -> })))
        val requests = listOf(reads.api.requests.receive(), reads.api.requests.receive())
        assertEquals(setOf("get_template_page", "get_home_summary"), requests.map { it.rpc }.toSet())
        requests.forEach {
            if (it.rpc == "get_home_summary") it.reply("""{"last_workout":null}""")
            else it.reply("""{"data":[{"id":"template","name":"Fixture","revision":7,"exercise_count":42,"updated_at":"${OnlineFixtures.time}"}],"page_info":{"next_cursor":null,"has_more":false,"limit":15}}""")
        }
        runCurrent()
        assertEquals(42, vm.state.value.templateSummaries.single().exerciseCount)
        assertTrue(vm.state.value.homeLoaded)
        assertTrue(reads.api.requests.tryReceive().isFailure)
        vm.refresh()
        repeat(2) { reads.api.requests.receive().loseResponse() }
        runCurrent()
        assertTrue(vm.state.value.stale); assertTrue(vm.state.value.hasReadError)
        assertEquals("template", vm.state.value.templateSummaries.single().id)
        reads.auth.signOut(); runCurrent()
        assertTrue(vm.state.value.templateSummaries.isEmpty())
        assertFalse(vm.state.value.homeLoaded)
        assertTrue(vm.state.value.userName.isBlank() || vm.state.value.userName == "User")
    }

    @Test fun `history initially requests one page and complete period totals but no child detail`() = runTest(dispatcher) {
        val vm = keep(RemoteHistoryViewModel(reads))
        vm.initialize(java.time.LocalDate.of(2026, 10, 1))
        val requests = listOf(reads.api.requests.receive(), reads.api.requests.receive())
        assertEquals(setOf("get_workout_history_page", "get_progress_summary"), requests.map { it.rpc }.toSet())
        requests.forEach {
            if (it.rpc == "get_workout_history_page") it.reply("""{"data":[],"page_info":{"next_cursor":null,"has_more":false,"limit":20}}""")
            else it.reply("""{"start_date":"2026-10-01","end_date":"2026-10-02","timezone":"UTC","workout_count":602,"total_volume":"602.005","total_sets":602,"total_reps":602,"duration_seconds":100000}""")
        }
        runCurrent()
        assertEquals(602L, vm.totals.value.data!!.workoutCount)
        assertTrue(vm.page.value.hasLoaded)
        assertTrue(reads.api.requests.tryReceive().isFailure)
        vm.initialize(java.time.LocalDate.of(2026, 10, 1)); runCurrent()
        assertTrue(reads.api.requests.tryReceive().isFailure)
        vm.open("explicit-detail")
        reads.api.requests.receive().also { assertEquals("get_workout_detail", it.rpc); it.reply("{}", 404) }
        runCurrent()
        assertNotNull(vm.selected.value.error)
    }
}

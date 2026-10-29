package com.diajarkoding.imfit.data.remote.online

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import com.diajarkoding.imfit.presentation.ui.home.HomeViewModel
import com.diajarkoding.imfit.presentation.ui.profile.ProfileViewModel
import com.diajarkoding.imfit.presentation.ui.progress.ProgressViewModel
import com.diajarkoding.imfit.presentation.ui.progress.RemoteHistoryViewModel
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.util.TimeZone

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OnlineMutationInvalidationTest {
    private val dispatcher = StandardTestDispatcher()
    private val viewModels = ViewModelStore()
    private val reads = ScriptedReadGateway()
    private val changes = OnlineMutationChanges(reads.auth)
    private val originalTimezone = TimeZone.getDefault()

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
    }
    @After fun cleanup() {
        viewModels.clear()
        reads.repository.close()
        TimeZone.setDefault(originalTimezone)
        Dispatchers.setMain(Dispatchers.Unconfined)
    }
    private fun <T : ViewModel> keep(vm: T): T {
        viewModels.put(vm.javaClass.name, vm)
        return vm
    }
    private fun workout(requestId: String, startedAt: String) = WorkoutCommitted(
        reads.auth.capture().identity, requestId, "workout-$requestId", startedAt)

    private fun FakeOnlineApi.Request.answer() {
        val window = if (rpc in setOf("get_progress_summary", "get_workout_date_markers"))
            """"start_date":${body["p_start_date"]},"end_date":${body["p_end_date"]},"timezone":${body["p_timezone"]}""" else ""
        reply(when (rpc) {
            "get_home_summary" -> """{"last_workout":null}"""
            "get_template_page", "get_workout_history_page" ->
                """{"data":[],"page_info":{"next_cursor":null,"has_more":false,"limit":${body["p_limit"]}}}"""
            "get_progress_summary" -> """{$window,"workout_count":1,"total_volume":"10.005","total_sets":1,"total_reps":1,"duration_seconds":60}"""
            "get_workout_date_markers" -> """{$window,"dates":[]}"""
            else -> error("Unexpected query $rpc")
        })
    }

    @Test fun `receipt dedup is login scoped and late old epoch never publishes`() = runTest(dispatcher) {
        val received = mutableListOf<OnlineMutationChange>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { changes.events.collect { received += it } }
        val first = workout("receipt", "2026-10-29T12:00:00Z")
        assertTrue(changes.publish(first))
        assertFalse(changes.publish(first))
        assertEquals(listOf(first), received)
        reads.auth.signOut()
        reads.auth.signIn("B", "B-token")
        assertFalse(changes.publish(first))
        reads.auth.signIn("A", "A-new-token")
        assertFalse(changes.publish(ProfileChanged(first.identity)))
        val fresh = workout("receipt", first.startedAt)
        assertTrue(changes.publish(fresh))
        assertEquals(listOf(first, fresh), received)
        val late = mutableListOf<OnlineMutationChange>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { changes.events.collect { late += it } }
        assertTrue(late.isEmpty())
    }

    @Test fun `home workout profile and template changes refresh only their own surfaces`() = runTest(dispatcher) {
        val edits = TemplateEditBridge(reads, LegacyTemplateGuard { _, _ -> })
        keep(HomeViewModel(mockk(relaxed = true), mockk(relaxed = true), reads, edits, changes))
        repeat(2) { reads.api.requests.receive().answer() }
        runCurrent()
        val initialProfiles = reads.profileCalls
        changes.publish(workout("finish", "2026-10-29T12:00:00Z"))
        runCurrent()
        reads.api.requests.receive().also { assertEquals("get_home_summary", it.rpc); it.answer() }
        runCurrent()
        assertEquals(initialProfiles, reads.profileCalls)
        assertTrue(reads.api.requests.tryReceive().isFailure)
        assertFalse(changes.publish(workout("finish", "2026-10-29T12:00:00Z")))
        runCurrent()
        assertTrue(reads.api.requests.tryReceive().isFailure)
        changes.publish(ProfileChanged(reads.auth.capture().identity))
        runCurrent()
        assertEquals(initialProfiles + 1, reads.profileCalls)
        assertTrue(reads.api.requests.tryReceive().isFailure)

        val save = async { edits.save(null, "Routine", emptyList()) }
        reads.api.requests.receive().also { request ->
            assertEquals("save_template_revision", request.rpc)
            request.reply("""{"request_id":${request.body["p_request_id"]},"template_id":${request.body["p_template_id"]},"revision":1,"exercise_count":0,"server_updated_at":"${OnlineFixtures.time}"}""")
        }
        save.await()
        reads.api.requests.receive().also { assertEquals("get_template_page", it.rpc); it.answer() }
        runCurrent()
        assertEquals(initialProfiles + 1, reads.profileCalls)
        assertTrue(reads.api.requests.tryReceive().isFailure)
    }

    @Test fun `progress invalidation uses loaded query timezone and independent selected day bounds`() = runTest(dispatcher) {
        val vm = keep(ProgressViewModel(reads, mockk(relaxed = true), changes))
        vm.selectMonth(YearMonth.of(2026, 10))
        vm.selectDay(LocalDate.of(2026, 10, 31))
        repeat(3) { reads.api.requests.receive().answer() }
        runCurrent()
        val initialProfiles = reads.profileCalls
        // UTC November still belongs to October in the actual query timezone.
        changes.publish(workout("october", "2026-11-01T06:59:59Z"))
        runCurrent()
        val matching = List(3) { reads.api.requests.receive() }
        assertEquals(listOf("get_progress_summary", "get_progress_summary", "get_workout_date_markers"), matching.map { it.rpc }.sorted())
        assertTrue(matching.all { it.body["p_timezone"]!!.jsonPrimitive.content == "America/Los_Angeles" })
        matching.forEach { it.answer() }
        runCurrent()
        assertEquals(initialProfiles, reads.profileCalls)
        changes.publish(workout("exclusive-end", "2026-11-01T07:00:00Z"))
        runCurrent()
        assertTrue(reads.api.requests.tryReceive().isFailure)
        vm.selectDay(LocalDate.of(2026, 11, 1))
        reads.api.requests.receive().answer()
        runCurrent()
        changes.publish(workout("day-only", "2026-11-01T07:00:00Z"))
        runCurrent()
        reads.api.requests.receive().also {
            assertEquals("get_progress_summary", it.rpc)
            assertEquals("2026-11-01", it.body["p_start_date"]!!.jsonPrimitive.content)
            it.answer()
        }
        runCurrent()
        assertTrue(reads.api.requests.tryReceive().isFailure)
        changes.publish(ProfileChanged(reads.auth.capture().identity))
        runCurrent()
        assertEquals(initialProfiles + 1, reads.profileCalls)
        assertTrue(reads.api.requests.tryReceive().isFailure)
    }

    @Test fun `history refreshes matching feed totals and leaves selected detail and profile untouched`() = runTest(dispatcher) {
        val vm = keep(RemoteHistoryViewModel(reads, changes))
        vm.initialize(LocalDate.of(2026, 10, 31))
        repeat(2) { reads.api.requests.receive().answer() }
        runCurrent()
        vm.open("unrelated-detail")
        reads.api.requests.receive().reply("{}", 404)
        runCurrent()
        val selected = vm.selected.value
        changes.publish(workout("outside", "2026-11-01T07:00:00Z"))
        changes.publish(ProfileChanged(reads.auth.capture().identity))
        runCurrent()
        assertTrue(reads.api.requests.tryReceive().isFailure)
        changes.publish(workout("inside", "2026-11-01T06:59:59Z"))
        runCurrent()
        val queries = List(2) { reads.api.requests.receive() }
        assertEquals(setOf("get_workout_history_page", "get_progress_summary"), queries.map { it.rpc }.toSet())
        queries.forEach { it.answer() }
        runCurrent()
        assertEquals(selected, vm.selected.value)
        assertEquals(0, reads.profileCalls)
        assertTrue(reads.api.requests.tryReceive().isFailure)
    }

    @Test fun `queued old profile event cannot trigger extra new owner read and retained VM reloads once`() = runTest(dispatcher) {
        val vm = keep(ProfileViewModel(reads, mockk(relaxed = true), changes, idleSessionStore()))
        runCurrent()
        assertEquals(1, reads.profileCalls)
        val oldIdentity = reads.auth.capture().identity
        changes.publish(ProfileChanged(oldIdentity))
        reads.auth.signOut()
        reads.auth.signIn("B", "B-token")
        runCurrent()
        assertEquals(2, reads.profileCalls)
        assertEquals("B", vm.state.value.data!!.id)
        assertFalse(changes.publish(ProfileChanged(oldIdentity)))
        changes.publish(workout("no-profile-refresh", "2026-10-29T12:00:00Z"))
        runCurrent()
        assertEquals(2, reads.profileCalls)
        assertTrue(reads.api.requests.tryReceive().isFailure)
        reads.auth.signIn("A", "A-new-token")
        runCurrent()
        assertEquals(3, reads.profileCalls)
        assertEquals("A", vm.state.value.data!!.id)
    }
}

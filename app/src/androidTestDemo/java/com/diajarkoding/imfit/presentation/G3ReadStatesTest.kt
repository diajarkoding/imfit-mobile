package com.diajarkoding.imfit.presentation

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.SavedStateHandle
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.data.remote.TemplateWriteRequest
import com.diajarkoding.imfit.data.remote.online.*
import com.diajarkoding.imfit.data.repository.DemoAuthRepository
import com.diajarkoding.imfit.domain.model.Exercise
import com.diajarkoding.imfit.domain.model.MuscleCategory
import com.diajarkoding.imfit.presentation.ui.exercise.*
import com.diajarkoding.imfit.presentation.ui.home.*
import com.diajarkoding.imfit.presentation.ui.profile.*
import com.diajarkoding.imfit.presentation.ui.progress.*
import com.diajarkoding.imfit.presentation.ui.workout.*
import com.diajarkoding.imfit.theme.IMFITTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.*
import java.util.Collections
import java.util.Locale
import dagger.hilt.android.EntryPointAccessors
import com.diajarkoding.imfit.di.RestTimerEntryPoint

/** Actual route/VM renderers with a scripted in-memory transport; no hosted account or DAO writes. */
class G3ReadStatesTest {
    private val stubWorkoutRepo = object : com.diajarkoding.imfit.domain.repository.WorkoutRepository {
        override suspend fun getTemplateById(templateId: String) = null
        override suspend fun createTemplate(userId: String, name: String, exercises: List<com.diajarkoding.imfit.domain.model.TemplateExercise>) = error("")
        override suspend fun updateTemplate(templateId: String, name: String, exercises: List<com.diajarkoding.imfit.domain.model.TemplateExercise>) = null
        override suspend fun updateTemplateExercises(templateId: String, exercises: List<com.diajarkoding.imfit.domain.model.TemplateExercise>) = null
        override suspend fun updateTemplateExercise(templateId: String, exerciseId: String, sets: Int, reps: Int, restSeconds: Int) = null
        override suspend fun deleteTemplate(templateId: String) = true
        override suspend fun getActiveSessionForTimer() = null
        override suspend fun isActiveSessionForTimer(userId: String, workoutId: String) = false
        override suspend fun startWorkout(template: com.diajarkoding.imfit.domain.model.WorkoutTemplate) = error("")
        override suspend fun getActiveSession() = null
        override suspend fun getPrefillForExercise(exerciseId: String, userId: String, defaults: com.diajarkoding.imfit.domain.model.ExerciseInputDefaults) = emptyMap<Int, Pair<Float, Int>>()
        override suspend fun finishWorkout() = null
        override suspend fun cancelWorkout() {}
        override suspend fun updateSessionRestOverride(seconds: Int) {}
        override suspend fun getSessionRestOverride() = null
        override suspend fun getWorkoutLogById(logId: String) = null
        override suspend fun getLastWorkoutLog(userId: String) = null
    }
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val store = ViewModelStore()
    private val gateway = ScreenGateway()
    private val changes = OnlineMutationChanges(gateway.auth)
    private val sessions by lazy {
        EntryPointAccessors.fromApplication(rule.activity.applicationContext, RestTimerEntryPoint::class.java).sessions()
    }
    private val authRepository = DemoAuthRepository()
    private val recomposition = mutableIntStateOf(0)
    private fun <T : ViewModel> keep(vm: T): T { store.put(vm.javaClass.name, vm); return vm }
    @After fun clear() { rule.runOnUiThread { store.clear() } }
    private fun text(id: Int, vararg args: Any) = rule.activity.createConfigurationContext(
        Configuration(rule.activity.resources.configuration).apply { setLocale(Locale.US) }).getString(id, *args)
    private fun waitText(value: String) = rule.waitUntil(15_000) { rule.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty() }
    private fun waitFor(predicate: () -> Boolean) = rule.waitUntil(15_000, predicate)
    private fun list() = rule.onNode(hasScrollToNodeAction())
    private fun scrollText(value: String) { list().performScrollToNode(hasText(value)) }
    private fun refresh() { list().performScrollToIndex(0).performTouchInput { swipeDown() } }
    private fun show(fontScale: Float = 1.5f, content: @Composable () -> Unit) = rule.setContent {
        val context = LocalContext.current
        val configuration = remember { Configuration(context.resources.configuration).apply {
            setLocale(Locale.US); screenWidthDp = 320; this.fontScale = fontScale
        } }
        val resources = remember { context.createConfigurationContext(configuration).resources }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalConfiguration provides configuration, LocalResources provides resources,
                LocalDensity provides Density(constraints.maxWidth / 320f, fontScale)) {
                IMFITTheme {
                    content()
                    if (recomposition.intValue > 0) Text("Recomposition ${recomposition.intValue}")
                }
            }
        }
    }
    private fun assertNoReloadOnRecomposition() {
        rule.waitForIdle()
        val before = gateway.calls.size
        rule.runOnIdle { recomposition.intValue++ }
        rule.waitForIdle()
        assertEquals(before, gateway.calls.size)
    }
    private fun assertTextFits(value: String) {
        for (node in rule.onAllNodesWithText(value, useUnmergedTree = true).fetchSemanticsNodes()) {
            val layouts = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            layouts.forEach { assertFalse("Text must wrap at 320dp/font150: $value", it.hasVisualOverflow) }
        }
    }

    @Test fun homeLoadingErrorEmptyContentAndStaleRefreshAtCompactLargeFont() {
        val pending = gateway.stage(fail = true, hold = true)
        val vm = keep(HomeViewModel(authRepository, stubWorkoutRepo, gateway,
            TemplateEditBridge(gateway, LegacyTemplateGuard { _, _ -> }), changes))
        show { HomeScreen({}, viewModel = vm) }
        waitFor { vm.state.value.refreshing }; assertTrue(vm.state.value.isLoading)
        pending.complete(Unit); waitFor { vm.state.value.hasReadError && !vm.state.value.refreshing }
        rule.onAllNodesWithText(text(R.string.home_empty_templates)).assertCountEquals(0)
        gateway.stage(empty = true)
        scrollText(text(R.string.routine_retry)); rule.onNodeWithText(text(R.string.routine_retry)).performClick()
        waitFor { vm.state.value.homeLoaded && !vm.state.value.refreshing }
        scrollText(text(R.string.home_empty_templates)); rule.onNodeWithText(text(R.string.home_empty_templates)).assertExists()
        gateway.stage(); refresh(); waitFor { vm.state.value.templateSummaries.isNotEmpty() && !vm.state.value.refreshing }
        scrollText("Synthetic routine"); rule.onNodeWithText("Synthetic routine").assertExists()
        gateway.stage(fail = true); refresh(); waitFor { vm.state.value.stale && !vm.state.value.refreshing }
        scrollText(text(R.string.remote_read_stale)); assertTextFits(text(R.string.remote_read_stale))
        scrollText("Synthetic routine"); rule.onNodeWithText("Synthetic routine").assertExists()
        assertNoReloadOnRecomposition()
        assertTrue(gateway.calls.none { it.startsWith("upsert_") })
    }

    @Test fun historyErrorEmptyRefreshStaleAndDetailRetryAtCompactLargeFont() {
        val pending = gateway.stage(fail = true, hold = true)
        val vm = keep(RemoteHistoryViewModel(gateway, changes))
        show { WorkoutHistoryDetailScreen("2026-10-01", {}, vm) }
        waitFor { vm.page.value.refreshing }; rule.onNodeWithTag("workout_history_loading").assertExists()
        pending.complete(Unit); waitFor { vm.page.value.refreshError != null }
        rule.onNodeWithTag("workout_history_empty").assertDoesNotExist()
        gateway.stage(empty = true); refresh(); waitFor { vm.page.value.hasLoaded && !vm.page.value.refreshing }
        rule.onNodeWithTag("workout_history_empty").assertExists()
        gateway.stage(); refresh(); waitFor { vm.page.value.items.isNotEmpty() && !vm.page.value.refreshing }
        assertEquals(602L, vm.totals.value.data!!.workoutCount) // Not the single loaded summary.
        rule.onAllNodesWithText("Synthetic bench").assertCountEquals(0)
        gateway.stage(fail = true)
        scrollText(text(R.string.workout_day_card_view_details)); rule.onNodeWithText(text(R.string.workout_day_card_view_details)).performClick()
        waitFor { vm.selected.value.error != null }
        gateway.stage(); scrollText(text(R.string.routine_retry)); rule.onNodeWithText(text(R.string.routine_retry)).performClick()
        waitFor { vm.selected.value.data != null }
        scrollText("Synthetic bench"); rule.onNodeWithText("Synthetic bench").assertExists()
        gateway.stage(fail = true); refresh(); waitFor { vm.page.value.stale && vm.page.value.refreshError != null }
        scrollText("Synthetic session"); rule.onNodeWithText("Synthetic session").assertExists()
        assertNoReloadOnRecomposition()
        assertEquals(2, gateway.calls.count { it == "get_workout_detail" })
    }

    @Test fun progressAndYearCalendarKeepAggregatesAndMarkersThroughFailures() {
        val pending = gateway.stage(fail = true, hold = true)
        val vm = keep(ProgressViewModel(gateway, authRepository, changes))
        val yearly = mutableStateOf(false)
        show { if (yearly.value) YearlyCalendarScreen({}, {}, viewModel = vm) else ProgressScreen(viewModel = vm) }
        waitFor { vm.state.value.isLoading && gateway.calls.isNotEmpty() }
        pending.complete(Unit); waitFor { vm.state.value.hasError && !vm.state.value.isLoading }
        waitText(text(R.string.progress_overview_load_error))
        gateway.stage(empty = true); refresh(); waitFor { vm.state.value.aggregate != null && !vm.state.value.refreshing }
        assertEquals(0L, vm.state.value.aggregate!!.workoutCount)
        assertTrue(vm.state.value.markersLoaded)
        gateway.stage(); refresh(); waitFor { vm.state.value.aggregate?.workoutCount == 602L && !vm.state.value.refreshing }
        gateway.stage(fail = true); refresh(); waitFor { vm.state.value.stale && !vm.state.value.refreshing }
        assertEquals(602L, vm.state.value.aggregate!!.workoutCount)
        assertTrue(vm.state.value.markerCounts.isNotEmpty())
        assertNoReloadOnRecomposition()
        gateway.stage(empty = true); rule.runOnIdle { yearly.value = true }
        waitFor { vm.state.value.aggregate?.startDate?.endsWith("01-01") == true && !vm.state.value.refreshing }
        rule.onNodeWithContentDescription(text(R.string.calendar_previous_year)).assertExists()
        assertTrue(vm.state.value.markersLoaded); assertTrue(vm.state.value.markerCounts.isEmpty())
        gateway.stage(fail = true); refresh(); waitFor { vm.state.value.hasError && !vm.state.value.refreshing }
        assertTrue(vm.state.value.markersLoaded) // Failed refresh does not erase legitimate empty markers.
        gateway.stage(); rule.onNodeWithText(text(R.string.routine_retry)).performClick()
        waitFor { !vm.state.value.hasError && !vm.state.value.refreshing }
        assertTrue(gateway.calls.none { it == "get_workout_history_page" })
    }

    @Test fun profileLoadingErrorPullRetryAndStaleIdentityAtCompactLargeFont() {
        val pending = gateway.stage(fail = true, hold = true)
        val vm = keep(ProfileViewModel(gateway, authRepository, changes, sessions))
        show { ProfileScreen({}, {}, false, {}, false, {}, vm) }
        waitFor { vm.state.value.loading }; rule.onNodeWithTag("profile_loading").assertExists()
        pending.complete(Unit); waitFor { vm.state.value.error != null }
        rule.onNodeWithTag("profile_retry").assertExists()
        gateway.stage(); refresh(); waitFor { vm.state.value.data != null && !vm.state.value.loading }
        rule.onNodeWithTag("profile_identity").assertExists()
        gateway.stage(fail = true); refresh(); waitFor { vm.state.value.stale && !vm.state.value.loading }
        rule.onNodeWithTag("profile_identity").assertExists()
        rule.onAllNodesWithText("Synthetic profile with a long name").assertCountEquals(2)
        assertTextFits("Synthetic profile with a long name")
        assertTextFits(text(R.string.remote_read_stale))
        assertNoReloadOnRecomposition()
        assertTrue(gateway.calls.all { it == "profile" })
    }

    @Test fun pickerErrorExplicitRetryHiddenSelectionAndStaleLockAtCompactLargeFont() {
        val pending = gateway.stage(fail = true, hold = true)
        val vm = keep(ExerciseSelectionViewModel(gateway))
        show { ExerciseSelectionScreen("", {}, {}, vm, emptySet(), "Synthetic routine") }
        waitFor { vm.state.value.isLoading }; pending.complete(Unit)
        waitFor { vm.state.value.errorRes != null && !vm.state.value.isLoading }
        gateway.stage(empty = true); scrollText(text(R.string.routine_retry)); rule.onNodeWithText(text(R.string.routine_retry)).performClick()
        waitFor { vm.state.value.errorRes == null && !vm.state.value.isLoading }
        assertTrue(vm.state.value.filteredExercises.isEmpty())
        // A successful empty page has no Paging error to retry: this CTA must refresh instead.
        gateway.stage(); scrollText(text(R.string.routine_retry)); rule.onNodeWithText(text(R.string.routine_retry)).performClick()
        waitFor { vm.state.value.filteredExercises.isNotEmpty() && !vm.state.value.isLoading }
        rule.onNode(hasText("Synthetic bench") and isToggleable()).performClick()
        rule.onNodeWithTag("exercise_picker_search").performTextInput("absent")
        waitText(text(R.string.routine_search_empty))
        rule.onNodeWithText(text(R.string.routine_selected_count, 1)).assertExists()
        assertEquals(1, vm.state.value.selectedExercises.size)
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        // The compact large-font results viewport is too short for the default pull threshold;
        // explicit retry remains the accessible alternative. Gesture coverage is separate below.
        gateway.stage(fail = true); rule.runOnUiThread { vm.refresh() }
        waitFor { vm.state.value.stale && !vm.state.value.refreshing }
        rule.onNodeWithText(text(R.string.routine_add_count, 1)).assertIsNotEnabled()
        assertTextFits(text(R.string.remote_read_stale))
        assertNoReloadOnRecomposition()
    }

    @Test fun pickerEmptyAndErrorSupportPullAtStandardFont() {
        val pending = gateway.stage(fail = true, hold = true)
        val vm = keep(ExerciseSelectionViewModel(gateway))
        show(fontScale = 1f) { ExerciseSelectionScreen("", {}, {}, vm, emptySet(), "Synthetic routine") }
        waitFor { vm.state.value.isLoading }; pending.complete(Unit)
        waitFor { vm.state.value.errorRes != null && !vm.state.value.isLoading }
        gateway.stage(empty = true); refresh()
        waitFor { vm.state.value.errorRes == null && !vm.state.value.isLoading }
        assertTrue(vm.state.value.filteredExercises.isEmpty())
        gateway.stage(); refresh(); waitFor { vm.state.value.filteredExercises.isNotEmpty() }
    }

    @Test fun templateDetailErrorEmptyStaleAndRetryNeverStartsAWorkout() {
        val pending = gateway.stage(fail = true, hold = true)
        val vm = keep(WorkoutDetailViewModel(stubWorkoutRepo, SavedStateHandle(), gateway,
            TemplateEditBridge(gateway, LegacyTemplateGuard { _, _ -> })))
        var starts = 0
        show { WorkoutDetailScreen("template", {}, {}, { starts++ }, viewModel = vm) }
        waitFor { vm.state.value.refreshing }; pending.complete(Unit)
        waitFor { vm.state.value.errorRes != null && !vm.state.value.refreshing }
        rule.onNodeWithText(text(R.string.action_start_workout)).assertIsNotEnabled()
        gateway.stage(empty = true); refresh(); waitFor { vm.state.value.workout != null && !vm.state.value.refreshing }
        rule.onNodeWithText(text(R.string.action_start_workout)).assertIsNotEnabled()
        assertTrue(vm.state.value.workout!!.exercises.isEmpty())
        gateway.stage(); refresh(); waitFor { vm.state.value.workout?.exercises?.isNotEmpty() == true && !vm.state.value.refreshing }
        gateway.stage(fail = true); refresh(); waitFor { vm.state.value.stale && !vm.state.value.refreshing }
        scrollText(text(R.string.remote_read_stale)); rule.onNodeWithText(text(R.string.remote_read_stale)).assertExists()
        rule.onNodeWithText(text(R.string.action_start_workout)).assertIsNotEnabled()
        gateway.stage(); scrollText(text(R.string.routine_retry)); rule.onNodeWithText(text(R.string.routine_retry)).performClick()
        waitFor { !vm.state.value.stale && !vm.state.value.refreshing }
        assertNoReloadOnRecomposition(); assertEquals(0, starts)
    }

    @Test fun editorRetryRetainsDirtyDraftAndAuthChangeRekeysRawExerciseFields() {
        val pending = gateway.stage(fail = true, hold = true)
        val vm = keep(EditWorkoutViewModel(SavedStateHandle(), gateway,
            TemplateEditBridge(gateway, LegacyTemplateGuard { _, _ -> })))
        show { EditWorkoutScreen("template", {}, vm) }
        waitFor { vm.state.value.refreshing }; pending.complete(Unit)
        waitFor { vm.state.value.errorRes != null && !vm.state.value.refreshing }
        gateway.stage(empty = true); refresh(); waitFor { vm.state.value.hasLoaded && !vm.state.value.refreshing }
        assertTrue(vm.state.value.exercises.isEmpty())
        gateway.stage(); refresh(); waitFor { vm.state.value.exercises.isNotEmpty() && !vm.state.value.refreshing }
        scrollText(text(R.string.workout_label_sets))
        rule.onNodeWithContentDescription(text(R.string.workout_label_sets)).performTextReplacement("9")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        val calls = gateway.calls.size
        refresh(); rule.waitForIdle()
        assertEquals("Dirty refresh must not overwrite inputs", 9, vm.state.value.exercises.single().sets)
        assertEquals(calls, gateway.calls.size)
        rule.runOnIdle { gateway.auth.signOut(); gateway.auth.signIn("screen-owner", "new-epoch") }
        try {
            waitFor { vm.state.value.hasLoaded && vm.state.value.exercises.singleOrNull()?.sets == 3 }
        } catch (failure: Throwable) {
            val value = vm.state.value
            throw AssertionError("Synthetic editor reload: loaded=${value.hasLoaded}, loading=${value.isLoading}, refreshing=${value.refreshing}, error=${value.errorRes}, generation=${value.sourceRevision}, sets=${value.exercises.map { it.sets }}, requests=${gateway.calls.toList()}", failure)
        }
        scrollText(text(R.string.workout_label_sets))
        rule.onNodeWithContentDescription(text(R.string.workout_label_sets)).assertTextEquals("3")
        gateway.stage(fail = true); refresh(); waitFor { vm.state.value.stale && !vm.state.value.refreshing }
        rule.onNodeWithText(text(R.string.action_save_changes)).assertIsNotEnabled()
        assertNoReloadOnRecomposition()
    }

    @Test fun completedSummaryErrorAndStaleRetryKeepCanonicalDetail() {
        val pending = gateway.stage(fail = true, hold = true)
        val vm = keep(WorkoutSummaryViewModel(gateway))
        show { WorkoutSummaryScreen("workout", {}, vm) }
        waitFor { vm.state.value.loading }; pending.complete(Unit)
        waitFor { vm.state.value.error != null && !vm.state.value.loading }
        gateway.stage(); refresh(); waitFor { vm.state.value.data != null && !vm.state.value.loading }
        scrollText("Synthetic session"); rule.onNodeWithText("Synthetic session").assertExists()
        gateway.stage(fail = true); refresh(); waitFor { vm.state.value.stale && !vm.state.value.loading }
        scrollText(text(R.string.remote_read_stale)); rule.onNodeWithText(text(R.string.remote_read_stale)).assertExists()
        assertEquals("workout", vm.state.value.data!!.id)
        gateway.stage(); scrollText(text(R.string.routine_retry)); rule.onNodeWithText(text(R.string.routine_retry)).performClick()
        waitFor { vm.state.value.error == null && !vm.state.value.loading }
        assertNoReloadOnRecomposition()
    }

    @Test fun homeAndHistoryAppendErrorsLatchUntilFooterRetryWithoutLosingRows() {
        gateway.stage(more = true, failAppend = true)
        val home = keep(HomeViewModel(authRepository, stubWorkoutRepo, gateway,
            TemplateEditBridge(gateway, LegacyTemplateGuard { _, _ -> }), changes))
        val history = keep(RemoteHistoryViewModel(gateway, changes))
        val historyRoute = mutableStateOf(false)
        show { if (historyRoute.value) WorkoutHistoryDetailScreen("2026-10-01", {}, history) else HomeScreen({}, viewModel = home) }
        waitFor { home.state.value.templateSummaries.isNotEmpty() }
        scrollText("Synthetic routine")
        rule.runOnUiThread { home.more() }; waitFor { home.state.value.appendError }
        val homeCalls = gateway.calls.count { it == "get_template_page" }
        scrollText(text(R.string.redesign_retry)); list().performScrollToIndex(0); scrollText(text(R.string.redesign_retry))
        assertEquals(homeCalls, gateway.calls.count { it == "get_template_page" })
        assertEquals(1, home.state.value.templateSummaries.size)
        gateway.stage(); rule.onNodeWithText(text(R.string.redesign_retry)).performClick()
        waitFor { !home.state.value.appendError && home.state.value.endReached }
        gateway.stage(more = true, failAppend = true); rule.runOnIdle { historyRoute.value = true }
        waitFor { history.page.value.items.isNotEmpty() }
        scrollText("Synthetic session")
        rule.runOnUiThread { history.more() }; waitFor { history.page.value.appendError != null }
        val historyCalls = gateway.calls.count { it == "get_workout_history_page" }
        scrollText(text(R.string.redesign_retry)); list().performScrollToIndex(0); scrollText(text(R.string.redesign_retry))
        assertEquals(historyCalls, gateway.calls.count { it == "get_workout_history_page" })
        assertEquals(602L, history.totals.value.data!!.workoutCount)
        gateway.stage(); rule.onNodeWithText(text(R.string.redesign_retry)).performClick()
        waitFor { history.page.value.appendError == null && history.page.value.endReached }
        assertEquals(1, history.page.value.items.size) // Stable-ID dedup on terminal replay.
    }
}

/** Fixed synthetic responses only; deliberately not a backend implementation or persistence layer. */
private class ScreenGateway : RemoteReadGateway {
    override val auth = OnlineAuth().also { it.signIn("screen-owner", "synthetic-token") }
    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private data class Reply(val fail: Boolean, val empty: Boolean, val gate: CompletableDeferred<Unit>, val more: Boolean = false, val failAppend: Boolean = false)
    @Volatile private var reply = Reply(false, false, CompletableDeferred(Unit))
    fun stage(fail: Boolean = false, empty: Boolean = false, hold: Boolean = false, more: Boolean = false, failAppend: Boolean = false): CompletableDeferred<Unit> {
        val gate = CompletableDeferred<Unit>().also { if (!hold) it.complete(Unit) }
        reply = Reply(fail, empty, gate, more, failAppend)
        return gate
    }
    private suspend fun request(name: String): Reply {
        val current = reply; calls += name; current.gate.await()
        if (current.fail) throw OnlineFailure(RemoteError.Network)
        return current
    }
    override suspend fun profile(): RemoteProfile {
        request("profile")
        return RemoteProfile("screen-owner", "Synthetic profile with a long name", "fixture@example.invalid", null, null)
    }
    override suspend fun updateProfile(patch: com.diajarkoding.imfit.domain.model.ProfilePatch): RemoteProfile =
        throw UnsupportedOperationException("Read-only screen fixture")
    override suspend fun exercises(ids: List<String>): List<Exercise> {
        request("exercises")
        return ids.map { Exercise(it, "Synthetic bench", MuscleCategory.CHEST, "") }
    }
    override suspend fun saveTemplate(request: TemplateWriteRequest) = error("No writes in read-state acceptance")
    override suspend fun <T> read(call: RpcCall<T>): T {
        val response = request(call.name)
        val empty = response.empty
        val p = call.parameters
        if (response.failAppend && p["p_cursor"] != null && p["p_cursor"] != JsonNull) throw OnlineFailure(RemoteError.Network)
        fun summary() = """{"id":"workout","template_id":null,"template_name":"Synthetic session","start_time":"2026-10-01T00:00:00Z","end_time":"2026-10-01T01:00:00Z","duration_seconds":3600,"total_volume":"655.005","total_sets":1,"total_reps":10}"""
        fun page(data: String): String {
            val more = response.more && (p["p_cursor"] == null || p["p_cursor"] == JsonNull)
            return """{"data":$data,"page_info":{"next_cursor":${if (more) "\"next\"" else "null"},"has_more":$more,"limit":${p["p_limit"]}}}"""
        }
        val body = when (call.name) {
            "get_template_page" -> page(if (empty) "[]" else """[{"id":"template","name":"Synthetic routine","revision":7,"exercise_count":1,"updated_at":"2026-10-01T00:00:00Z"}]""")
            "get_home_summary" -> """{"last_workout":${if (empty) "null" else summary()}}"""
            "get_workout_history_page" -> page(if (empty) "[]" else "[${summary()}]")
            "get_workout_detail" -> """${summary().dropLast(1)},"exercise_logs":[{"id":"child","exercise_id":"bench","exercise_name":"Synthetic bench","muscle_category":"Chest","order_index":0,"input_defaults":null,"total_volume":"655.005","total_sets":1,"total_reps":10,"sets":[{"id":"set","set_number":1,"weight":"65.5005","reps":10,"is_completed":true}]}]}"""
            "get_progress_summary" -> """{"start_date":${p["p_start_date"]},"end_date":${p["p_end_date"]},"timezone":${p["p_timezone"]},"workout_count":${if (empty) 0 else 602},"total_volume":"${if (empty) "0" else "655.005"}","total_sets":${if (empty) 0 else 602},"total_reps":${if (empty) 0 else 6020},"duration_seconds":${if (empty) 0 else 36000}}"""
            "get_workout_date_markers" -> """{"start_date":${p["p_start_date"]},"end_date":${p["p_end_date"]},"timezone":${p["p_timezone"]},"dates":${if (empty) "[]" else """[{"date":${p["p_start_date"]},"workout_count":602}]"""}}"""
            "get_exercise_catalog_page" -> page(if (empty || p["p_search"]?.jsonPrimitive?.content == "absent") "[]" else """[{"id":"bench","muscle_category_id":1,"name":"Synthetic bench","image_url":null,"difficulty":null,"equipment":null}]""")
            "get_template_detail" -> """{"id":"template","name":"Synthetic routine","revision":7,"updated_at":"2026-10-01T00:00:00Z","exercises":${if (empty) "[]" else """[{"id":"child","exercise_id":"bench","order_index":0,"sets":3,"reps":10,"rest_seconds":60}]"""}}"""
            else -> error("Unexpected read ${call.name}")
        }
        return onlineJson.decodeFromString(call.serializer, body).also(call.validate)
    }
}

package com.diajarkoding.imfit

import android.content.Intent
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.diajarkoding.imfit.data.remote.TemplateWriteExercise
import com.diajarkoding.imfit.data.remote.TemplateWriteRequest
import com.diajarkoding.imfit.data.remote.online.*
import com.diajarkoding.imfit.di.RestTimerEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URI
import java.time.LocalDate
import java.util.UUID

/** Local production SDK/HTTP fixtures only. Ports arrange routines and observe; workout input,
 * completion and finish always cross the same Compose controls used by a person. */
@RunWith(AndroidJUnit4::class)
class OnlineOnlyReadyTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val fixture by lazy { ProductionReadyFixture() }
    private var activity: ActivityScenario<MainActivity>? = null
    private val context get() = fixture.context
    private val ports get() = fixture.ports
    private fun text(id: Int, vararg values: Any): String {
        var result: String? = null
        requireNotNull(activity).onActivity { result = it.getString(id, *values) }
        return requireNotNull(result)
    }
    private fun launch() {
        activity?.close()
        activity = ActivityScenario.launch(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    private fun waitNode(matcher: SemanticsMatcher) {
        compose.waitUntil(30_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun clickText(id: Int) {
        val matcher = hasText(text(id)) and hasClickAction() and isEnabled()
        waitNode(matcher)
        compose.onNode(matcher).performClick()
    }
    private fun tag(tag: String) { waitNode(hasTestTag(tag)); compose.onNodeWithTag(tag).assertIsDisplayed() }
    private fun scrollTo(matcher: SemanticsMatcher, container: SemanticsMatcher = hasScrollToNodeAction()) {
        compose.waitUntil(30_000) {
            try {
                compose.onNode(container).performScrollToNode(matcher)
                true
            } catch (_: AssertionError) { false }
        }
    }

    private suspend fun loginThroughUi() {
        fixture.guard()
        fixture.requireAllowedAccount()
        if (ports.auth().getLocalUserId() != null) ports.auth().logout()
        launch()
        waitNode(hasTestTag("auth_language_en"))
        compose.onNodeWithTag("auth_language_en").performClick()
        waitNode(hasText(text(R.string.placeholder_email)))
        compose.onNode(hasSetTextAction() and hasText(text(R.string.placeholder_email)))
            .performTextReplacement(fixture.argument("readyEmail"))
        compose.onNode(hasSetTextAction() and hasText(text(R.string.placeholder_password)))
            .performTextReplacement(fixture.argument("readyPassword"))
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        clickText(R.string.login_button)
        fixture.await { ports.auth().getLocalUserId() == fixture.owner }
        tag("home_refresh")
        assertEquals(fixture.owner, ports.reads().profile().id)
    }

    private suspend fun routineThroughUi(): Pair<String, String> {
        val exercise = ports.reads().read(OnlineRpc.catalog(limit = 1)).data.single()
        val name = "UI routine ${UUID.randomUUID().toString().take(8)}"
        scrollTo(hasText(text(R.string.home_add_day)) and hasClickAction())
        clickText(R.string.home_add_day)
        waitNode(hasSetTextAction() and hasText(text(R.string.home_workout_placeholder)))
        compose.onNode(hasSetTextAction()).performTextReplacement(name)
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        clickText(R.string.routine_create_action)
        tag("routine_detail_list")
        clickText(R.string.action_add_exercise)
        tag("exercise_picker_search")
        compose.onNodeWithTag("exercise_picker_search").performTextReplacement(exercise.name)
        val row = hasText(exercise.name) and hasClickAction() and !hasSetTextAction()
        waitNode(row)
        compose.onNode(row).performClick()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        val add = hasText(text(R.string.routine_add_count, 1)) and hasClickAction()
        waitNode(add)
        compose.onNode(add).performClick()
        tag("routine_detail_list")
        var saved: TemplateSummary? = null
        fixture.await {
            saved = ports.reads().read(OnlineRpc.templates()).data.singleOrNull { it.name == name }
            saved?.exerciseCount == 1
        }
        return requireNotNull(saved).id to name
    }

    private suspend fun openRoutine(throughUi: Boolean = false): SessionSnapshot {
        val routine = if (throughUi) routineThroughUi() else fixture.routine("UI")
        // Recreate the actual activity so its initial bounded routine query includes the new fixture.
        if (!throughUi) {
            launch()
            tag("home_refresh")
            scrollTo(hasText(routine.second))
            compose.onNodeWithText(routine.second).performClick()
        }
        tag("routine_detail_list")
        compose.waitUntil(30_000) {
            compose.onAllNodes(hasText(text(R.string.action_start_workout)) and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        clickText(R.string.action_start_workout)
        tag("active_workout_screen")
        fixture.await { ports.sessions().state.value.snapshot?.templateId == routine.first }
        return requireNotNull(ports.sessions().state.value.snapshot)
    }

    private suspend fun startAndComplete(throughUi: Boolean = false): SessionSnapshot {
        val session = openRoutine(throughUi)
        val exercise = session.exercises.single()
        val set = exercise.sets.first()
        if (throughUi) {
            compose.onNodeWithContentDescription(text(R.string.action_pause)).performClick()
            fixture.await { ports.sessions().state.value.snapshot?.status == SessionStatus.PAUSED }
            waitNode(hasContentDescription(text(R.string.action_resume)) and isEnabled())
            compose.onNodeWithContentDescription(text(R.string.action_resume)).performClick()
            fixture.await { ports.sessions().state.value.snapshot?.status == SessionStatus.ACTIVE }
        }
        val weightLabel = text(R.string.active_session_weight_input, exercise.name, set.setNumber)
        val repsLabel = text(R.string.active_session_reps_input, exercise.name, set.setNumber)
        scrollTo(hasContentDescription(weightLabel), hasTestTag("active_workout_list"))
        compose.onNodeWithContentDescription(weightLabel).performTextReplacement("12.25")
        compose.onNodeWithContentDescription(repsLabel).performTextReplacement("7")
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        val complete = hasContentDescription(text(R.string.active_session_complete_set, exercise.name, set.setNumber))
        waitNode(complete)
        compose.onNode(complete).performClick()
        fixture.await {
            ports.sessions().state.value.snapshot?.exercises?.single()?.sets?.first()?.let {
                it.isCompleted && it.weight.toBigDecimal().compareTo("12.25".toBigDecimal()) == 0 && it.reps == 7
            } == true
        }
        assertNull(ports.sessions().state.value.pendingRequestId)
        if (exercise.restSeconds > 0) {
            tag("rest_timer_countdown_dialog")
            clickText(R.string.action_skip)
        }
        return requireNotNull(ports.sessions().state.value.snapshot)
    }

    private suspend fun finishThroughUi(): SessionMutationReceipt {
        clickText(R.string.action_finish_workout)
        fixture.await { ports.sessions().state.value.lastReceipt?.operation == "finish_active_session" }
        val receipt = requireNotNull(ports.sessions().state.value.lastReceipt)
        assertEquals(SessionStatus.COMPLETED, receipt.status)
        assertNotNull(receipt.workoutId)
        tag("workout_summary_screen")
        scrollTo(hasText("12.25", substring = true), hasTestTag("workout_summary_list"))
        compose.onNode(hasText("12.25", substring = true)).assertIsDisplayed()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            .takeScreenshot(java.io.File(context.filesDir, "ready-summary.png"))
        val detail = ports.reads().read(OnlineRpc.workoutDetail(requireNotNull(receipt.workoutId)))
        assertEquals(1L, detail.totalSets)
        assertEquals(7L, detail.totalReps)
        assertEquals(0, detail.totalVolume.toBigDecimal().compareTo("85.75".toBigDecimal()))
        return receipt
    }

    @Test fun onlineUiJourney() = runBlocking {
        try {
            loginThroughUi()
            startAndComplete(throughUi = true)
            val receipt = finishThroughUi()
            check(context.getSharedPreferences("online_ready_observations", 0).edit()
                .putString("finishedWorkout", receipt.workoutId).putString("finishedName", receipt.snapshot.templateName)
                .putInt("finishedPid", Process.myPid()).commit())
            clickText(R.string.action_done)
            clickText(R.string.nav_progress)
            waitNode(hasText(text(R.string.progress_overview_volume)))
            val today = LocalDate.now()
            val day = DateWindow(today.toString(), today.plusDays(1).toString(), java.time.ZoneId.systemDefault().id)
            assertTrue(ports.reads().read(OnlineRpc.progress(day)).workoutCount >= 1)
            // Calendar date is an accessible button; the marker-backed day opens actual history UI.
            val calendarDate = today.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", java.util.Locale.ENGLISH))
            val calendar = hasContentDescription(calendarDate, substring = true) and hasClickAction()
            scrollTo(calendar)
            compose.onNode(calendar).performClick()
            tag("workout_history_list")
            scrollTo(hasText(receipt.snapshot.templateName), hasTestTag("workout_history_list"))
            compose.onNodeWithText(receipt.snapshot.templateName).assertIsDisplayed()
            val history = ports.reads().read(OnlineRpc.history(day))
            assertTrue(history.data.any { it.id == receipt.workoutId })
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
            clickText(R.string.nav_home)
            waitNode(hasContentDescription(text(R.string.redesign_profile)))
            compose.onNodeWithContentDescription(text(R.string.redesign_profile)).performClick()
            tag("profile_information")
            waitNode(hasText(fixture.argument("readyEmail")))
            assertEquals(fixture.owner, ports.reads().profile().id)
        } finally { activity?.close() }
    }

    @Test fun verifyFinishedRestart() = runBlocking<Unit> {
        fixture.guard()
        val marker = context.getSharedPreferences("online_ready_observations", 0)
        val id = requireNotNull(marker.getString("finishedWorkout", null))
        val name = requireNotNull(marker.getString("finishedName", null))
        assertNotEquals(marker.getInt("finishedPid", -1), Process.myPid())
        fixture.await { ports.auth().getLocalUserId() == fixture.owner }
        assertEquals(id, ports.reads().read(OnlineRpc.workoutDetail(id)).id)
        try {
            launch()
            tag("home_refresh")
            clickText(R.string.nav_progress)
            val today = LocalDate.now()
            val calendarDate = today.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", java.util.Locale.ENGLISH))
            val calendar = hasContentDescription(calendarDate, substring = true) and hasClickAction()
            scrollTo(calendar)
            compose.onNode(calendar).performClick()
            tag("workout_history_list")
            scrollTo(hasText(name), hasTestTag("workout_history_list"))
            compose.onNodeWithText(name).assertIsDisplayed()
        } finally { activity?.close() }
    }

    @Test fun conflictReloadExposesExplicitReviewWithoutUnlockingMutations() = runBlocking<Unit> {
        try {
            loginThroughUi()
            var session = openRoutine()
            val exercise = session.exercises.single()
            val set = exercise.sets.first()
            for (useServer in listOf(true, false)) {
                fixture.serverCommand(session, if (session.status == SessionStatus.PAUSED) SessionCommand.Resume else SessionCommand.Pause)
                val input = hasContentDescription(text(R.string.active_session_weight_input, exercise.name, set.setNumber))
                scrollTo(input, hasTestTag("active_workout_list"))
                compose.onNode(input).performTextReplacement("25")
                UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
                fixture.await { ports.sessions().state.value.savePhase == SessionSavePhase.CONFLICT }
                assertTrue(ports.sessions().state.value.requiresVerification)
                compose.onAllNodesWithText(text(R.string.session_use_server)).assertCountEquals(0)
                compose.onAllNodesWithText(text(R.string.session_reapply)).assertCountEquals(0)
                scrollTo(hasText(text(R.string.session_reload)), hasTestTag("active_workout_list"))
                clickText(R.string.session_reload)
                fixture.await { !ports.sessions().state.value.requiresVerification && ports.sessions().state.value.savePhase == SessionSavePhase.CONFLICT }
                waitNode(hasText(text(R.string.session_use_server)))
                waitNode(hasText(text(R.string.session_reapply)))
                val action = if (useServer) R.string.session_use_server else R.string.session_reapply
                clickText(action)
                if (useServer) clickText(R.string.action_continue)
                fixture.await { ports.sessions().state.value.savePhase == SessionSavePhase.CONFIRMED }
                session = requireNotNull(ports.sessions().state.value.snapshot)
                assertEquals(if (useServer) "0.00" else "25.00", session.exercises.single().sets.first().weight)
            }
            ports.sessions().cancel(session.id)
        } finally { activity?.close() }
    }

    /** Host SIGKILLs this exact process while instrumentation is still running. */
    @Test fun prepareProcessRestart() = runBlocking<Unit> {
        loginThroughUi()
        val session = startAndComplete()
        val saved = context.getSharedPreferences("online_ready_observations", 0).edit()
            .putString("session", session.id).putLong("version", session.version)
            .putInt("pid", Process.myPid()).commit()
        check(saved)
        Log.i("IMFITReady", "event=restart_armed pid=${Process.myPid()}")
        awaitCancellation()
    }

    @Test fun verifyProcessRestart() = runBlocking {
        fixture.guard()
        val marker = context.getSharedPreferences("online_ready_observations", 0)
        val id = requireNotNull(marker.getString("session", null))
        assertNotEquals(marker.getInt("pid", -1), Process.myPid())
        fixture.await { ports.auth().getLocalUserId() == fixture.owner }
        val session = requireNotNull(ports.sessions().reload(id))
        assertEquals(id, session.id)
        assertTrue(session.version >= marker.getLong("version", -1))
        assertTrue(session.exercises.single().sets.single().isCompleted)
        try {
            launch()
            // Home resumes only the existing confirmed session, never creates from a template ID.
            tag("home_refresh")
            scrollTo(hasText(session.templateName))
            compose.onNodeWithText(session.templateName).performClick()
            tag("active_workout_screen")
            assertEquals(id, ports.sessions().state.value.snapshot?.id)
            finishThroughUi()
            check(marker.edit().clear().commit())
        } finally { activity?.close() }
    }

    @Test fun lostResponseAndAccountSwitch() = runBlocking {
        try {
            loginThroughUi()
            val session = startAndComplete()
            val control = fixture.proxyControl()
            fixture.control(control + "/arm?operation=finish_active_session", "POST")
            clickText(R.string.action_finish_workout)
            var observed: JsonObject? = null
            fixture.await(60_000) {
                observed = fixture.control(control + "/status")
                observed?.get("committed")?.jsonPrimitive?.booleanOrNull == true &&
                    observed?.get("dropped")?.jsonPrimitive?.booleanOrNull == true
            }
            fixture.await(60_000) { ports.sessions().state.value.pendingRequestId != null && !ports.sessions().state.value.busy }
            val requestId = requireNotNull(ports.sessions().state.value.pendingRequestId)
            assertEquals(session.id, ports.sessions().state.value.snapshot?.id)
            assertTrue(ports.sessions().state.value.snapshot?.status != SessionStatus.COMPLETED)
            // Resolution is an explicit user action, not a port that manufactures a receipt.
            scrollTo(hasText(text(R.string.session_resolve)), hasTestTag("active_workout_list"))
            clickText(R.string.session_resolve)
            fixture.await { ports.sessions().state.value.lastReceipt?.operation == "finish_active_session" && ports.sessions().state.value.pendingRequestId == null }
            val receipt = requireNotNull(ports.sessions().state.value.lastReceipt)
            assertEquals(requestId, receipt.requestId)
            val upstream = requireNotNull(observed?.get("receipt")).jsonObject
            assertEquals(upstream["request_id"]?.jsonPrimitive?.content, receipt.requestId)
            assertEquals(upstream["workout_id"]?.jsonPrimitive?.content, receipt.workoutId)
            tag("workout_summary_screen")
            val oldEpoch = ports.reads().auth.capture().identity
            ports.auth().logout()
            fixture.loginOther()
            assertNotEquals(oldEpoch, ports.reads().auth.capture().identity)
            assertEquals(fixture.argument("readyOtherOwner"), ports.reads().profile().id)
            assertNull(ports.sessions().state.value.snapshot)
            assertNull(ports.sessions().state.value.pendingRequestId)
            assertNull(ports.sessions().reload())
            launch()
            tag("home_refresh")
            compose.onAllNodesWithText(session.templateName).assertCountEquals(0)
            val today = LocalDate.now()
            assertTrue(ports.reads().read(OnlineRpc.history(DateWindow(today.toString(), today.plusDays(1).toString(), "UTC"))).data.none { it.id == receipt.workoutId })
            ports.auth().logout()
            fixture.login()
            assertEquals(fixture.owner, ports.reads().profile().id)
            assertEquals(receipt.workoutId, ports.reads().read(OnlineRpc.workoutDetail(requireNotNull(receipt.workoutId))).id)
        } finally {
            activity?.close()
            fixture.guard()
            fixture.requireAllowedAccount()
            ports.auth().logout()
            assertNull(ports.auth().getLocalUserId())
        }
    }
}

/** Shared real SDK fixture. No token import, DAO or fixture-specific production endpoint. */
internal class ProductionReadyFixture {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    val context get() = instrumentation.targetContext
    val ports: RestTimerEntryPoint by lazy { EntryPointAccessors.fromApplication(context, RestTimerEntryPoint::class.java) }
    val owner get() = argument("readyOwner")
    fun argument(name: String): String = requireNotNull(InstrumentationRegistry.getArguments().getString(name)) { "Missing fixture argument $name" }
    fun guard() {
        val internalPreview = InstrumentationRegistry.getArguments().getString("readyInternalPreview") == "true" &&
            BuildConfig.DEBUG && context.packageName == "com.diajarkoding.imfit.modaldiagnostic"
        check(!BuildConfig.DEBUG || internalPreview) { "Release or explicitly isolated productionDebug preview required" }
        check(argument("allowSyntheticSession") == "true")
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone")) { "Emulator only" }
        val user = argument("androidUser").toInt()
        check(user > 0 && Process.myUid() / 100_000 == user) { "Disposable non-owner user required" }
        val legacy = context.getDatabasePath("imfit_database")
        check(!legacy.exists() && !java.io.File(legacy.path + "-wal").exists()) {
            "Refusing existing legacy data; installed-upgrade fixtures use their own explicit harness"
        }
        UUID.fromString(owner)
        val backend = URI(argument("readyBackend"))
        check(backend.scheme == "http" && backend.host == "127.0.0.1" && backend.port == 54321 && backend.userInfo == null)
        check(BuildConfig.SUPABASE_URL.trimEnd('/') == argument("readyBackend").trimEnd('/')) { "Fixture must use actual local backend" }
        check(argument("readyEmail").startsWith("imfit-") && argument("readyEmail").endsWith("@example.invalid"))
        check(argument("readyPassword").length >= 6)
    }
    suspend fun requireAllowedAccount() {
        await(10_000) { ports.auth().localAuthStatusName() != "Initializing" }
        val current = ports.auth().getLocalUserId()
        check(current == null || current == owner || current == InstrumentationRegistry.getArguments().getString("readyOtherOwner")) { "Refusing existing nonfixture account" }
    }
    suspend fun login() {
        guard(); requireAllowedAccount()
        val user = ports.auth().login(argument("readyEmail"), argument("readyPassword")).getOrThrow()
        check(user.id == owner)
        await { ports.reads().auth.capture().identity.ownerId == owner }
    }
    suspend fun loginOther() {
        val email = argument("readyOtherEmail")
        check(email.startsWith("imfit-") && email.endsWith("@example.invalid"))
        val user = ports.auth().login(email, argument("readyOtherPassword")).getOrThrow()
        check(user.id == argument("readyOtherOwner"))
        await { ports.reads().auth.capture().identity.ownerId == user.id }
    }
    suspend fun routine(label: String, restSeconds: Int = 0): Pair<String, String> {
        val exercise = ports.reads().read(OnlineRpc.catalog(limit = 1)).data.single()
        val id = UUID.randomUUID().toString()
        val name = "IMFIT fixture $label ${id.take(8)}"
        val receipt = ports.reads().saveTemplate(TemplateWriteRequest(UUID.randomUUID().toString(), id, null,
            name, listOf(TemplateWriteExercise(exercise.id, 0, 1, 7, restSeconds)), emptyList(), ownerId = owner))
        check(receipt.templateId == id && receipt.exerciseCount == 1)
        return id to name
    }
    suspend fun confirmedSession(): SessionSnapshot {
        login()
        check(ports.sessions().reload() == null) { "Fixture requires no existing active workout" }
        val routine = routine("timer", 60)
        val detail = ports.reads().read(OnlineRpc.templateDetail(routine.first))
        val created = ports.sessions().start(detail.id, detail.revision)
        val exercise = created.exercises.single()
        val set = exercise.sets.single()
        ports.sessions().command(created.id, SessionCommand.EditSet(exercise.id, set.id, "12.25", 7))
        val confirmed = ports.sessions().command(created.id, SessionCommand.CompleteSet(exercise.id, set.id))
        check(confirmed.exercises.single().sets.single().isCompleted)
        check(ports.sessions().state.value.pendingRequestId == null)
        check(ports.sessions().state.value.lastReceipt?.version == confirmed.version)
        return confirmed
    }
    /** A second real HTTP client changes the server only; it never updates the app's store. */
    suspend fun serverCommand(session: SessionSnapshot, command: SessionCommand) = withContext(Dispatchers.IO) {
        fun post(path: String, body: JsonObject, token: String? = null): JsonObject {
            val connection = URI(argument("readyBackend") + path).toURL().openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 10_000; connection.readTimeout = 10_000
                connection.setRequestProperty("apikey", argument("readyAnonKey"))
                connection.setRequestProperty("Content-Type", "application/json")
                token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
                check(connection.responseCode == 200) { "Synthetic second-client request failed: ${connection.responseCode}" }
                return Json.parseToJsonElement(connection.inputStream.bufferedReader().use { it.readText() }).jsonObject
            } finally { connection.disconnect() }
        }
        guard()
        val login = post("/auth/v1/token?grant_type=password", buildJsonObject {
            put("email", argument("readyEmail")); put("password", argument("readyPassword"))
        })
        val call = OnlineRpc.updateSession(UUID.randomUUID().toString(), session.id, session.version, command)
        post("/rest/v1/rpc/update_active_session", call.parameters, login.getValue("access_token").jsonPrimitive.content)
    }
    suspend fun await(timeoutMs: Long = 30_000, condition: suspend () -> Boolean) {
        withTimeout(timeoutMs) { while (!condition()) delay(50) }
    }
    fun proxyControl(): String {
        val value = argument("responseLossControlUrl")
        val uri = URI(value)
        check(uri.scheme == "http" && uri.host == "127.0.0.1" && uri.port == 54321 && uri.path.startsWith("/__fixture/") && uri.userInfo == null)
        return value.trimEnd('/')
    }
    suspend fun control(url: String, method: String = "GET"): JsonObject = withContext(Dispatchers.IO) {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 10_000; connection.readTimeout = 10_000
            if (method == "POST") { connection.doOutput = true; connection.outputStream.use { } }
            check(connection.responseCode in 200..299) { "Fixture proxy control failed" }
            Json.parseToJsonElement(connection.inputStream.bufferedReader().use { it.readText() }).jsonObject
        } finally { connection.disconnect() }
    }
}

package com.diajarkoding.imfit

import android.os.Build
import android.os.Process
import android.util.Log
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.diajarkoding.imfit.data.legacy.*
import com.diajarkoding.imfit.di.RestTimerEntryPoint
import com.diajarkoding.imfit.data.remote.online.*
import com.diajarkoding.imfit.presentation.ui.recovery.LegacyRecoveryScreen
import com.diajarkoding.imfit.theme.IMFITTheme
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicReference

/** Exercises an APK-installed baseline through recovery UI; never writes the SQLite source. */
@RunWith(AndroidJUnit4::class)
class LegacyUpgradeReadyTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private fun required(name: String) = requireNotNull(args.getString(name)) { "Missing fixture argument $name" }
    private fun digest(file: File): String? = if (!file.exists()) null else MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private val ports by lazy { EntryPointAccessors.fromApplication(context, RestTimerEntryPoint::class.java) }
    private val resumed = AtomicReference<Pair<String, String>?>(null)
    private fun label(id: Int) = compose.activity.getString(id)
    private data class Installed(
        val owner: String, val other: String, val scenario: String, val marker: JSONObject,
        val database: File, val hashes: Map<String, String?>,
        val reader: AndroidLegacyInventoryReader, val inventory: LegacyInventory,
    )

    private suspend fun installed(): Installed {
        check(!BuildConfig.DEBUG) { "Minified productionRelease required" }
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone"))
        check(required("androidUser").toInt() > 0 && Process.myUid() / 100_000 == required("androidUser").toInt())
        check(required("readyBackend") == "http://127.0.0.1:54321" && BuildConfig.SUPABASE_URL.trimEnd('/') == required("readyBackend"))
        check(required("readyEmail").startsWith("imfit-") && required("readyEmail").endsWith("@example.invalid"))
        val owner = UUID.fromString(required("readyOwner")).toString()
        val other = UUID.fromString(required("readyOtherOwner")).toString()
        val marker = JSONObject(File(context.filesDir, "online-ready-fixture.json").readText())
        assertEquals(required("fixtureId"), marker.getString("fixture"))
        assertEquals(owner, marker.getString("owner"))
        val scenario = required("fixtureScenario")
        assertEquals(scenario, marker.getString("scenario"))
        val database = context.getDatabasePath("imfit_database")
        val reader = AndroidLegacyInventoryReader(database)
        val source = Installed(owner, other, scenario, marker, database, sourceHashes(database), reader, reader.inspect(owner))
        check(ports.auth().getLocalUserId().let { it == null || it == owner || it == other }) { "Nonfixture account refused" }
        return source
    }

    private fun sourceHashes(database: File) =
        listOf(database, File(database.path + "-wal")).associate { it.name to digest(it) }

    private suspend fun assertSourceUnchanged(source: Installed) {
        assertEquals("Database and committed WAL must remain byte-preserved; operational SHM is excluded",
            source.hashes, sourceHashes(source.database))
        assertEquals("Recovery must not mutate or prune the original owner inventory", source.inventory, source.reader.inspect(source.owner))
    }

    private suspend fun await(timeoutMs: Long = 30_000, condition: suspend () -> Boolean) =
        withTimeout(timeoutMs) { while (!condition()) delay(50) }

    private fun waitNode(matcher: SemanticsMatcher) {
        compose.waitUntil(30_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun scroll(matcher: SemanticsMatcher, container: SemanticsMatcher = hasTestTag("legacy_recovery_list")) {
        compose.waitUntil(30_000) {
            try { compose.onNode(container).performScrollToNode(matcher); true }
            catch (_: AssertionError) { false }
        }
    }

    private fun clickRecovery(id: Int) {
        val matcher = hasText(label(id)) and hasClickAction() and isEnabled()
        scroll(matcher)
        compose.onNode(matcher).performClick()
    }

    private fun confirm() {
        val matcher = hasText(label(R.string.action_continue)) and hasClickAction() and isEnabled()
        waitNode(matcher)
        compose.onNode(matcher).performClick()
    }

    private fun showRecovery(inventory: LegacyInventory) {
        compose.runOnUiThread {
            compose.activity.setContent {
                IMFITTheme {
                    LegacyRecoveryScreen(onBack = {}, onResumeSession = { id, template -> resumed.set(id to template) })
                }
            }
        }
        val expected = when (inventory) {
            is LegacyInventory.Absent -> label(R.string.legacy_recovery_empty)
            is LegacyInventory.Unknown -> label(R.string.legacy_recovery_failed)
            is LegacyInventory.Present -> compose.activity.getString(R.string.legacy_recovery_schema, inventory.schemaVersion)
        }
        scroll(hasText(expected))
    }

    /** Observe only the committed sidecar; never repair AtomicFile backups or write recovery state. */
    private suspend fun sidecar(owner: String, directory: String): JsonObject? = withContext(Dispatchers.IO) {
        val name = MessageDigest.getInstance("SHA-256").digest(owner.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val base = File(context.noBackupFilesDir, "$directory/$name.json")
        val backup = File(base.path + ".bak")
        val authority = if (backup.exists()) backup else base
        if (!authority.exists()) return@withContext null
        val value = try { Json.parseToJsonElement(authority.readText()).jsonObject }
        catch (_: java.io.IOException) { return@withContext null }
        catch (_: kotlinx.serialization.SerializationException) { return@withContext null }
        assertEquals(owner, value.getValue("ownerId").jsonPrimitive.content)
        assertEquals(1, value.getValue("formatVersion").jsonPrimitive.int)
        value
    }

    private suspend fun checkpoint(owner: String) = sidecar(owner, "legacy_recovery")
    private suspend fun archive(owner: String) = sidecar(owner, "legacy_archive")

    private suspend fun awaitAction(owner: String, operation: String, sourceId: String, state: String): JsonObject {
        var found: JsonObject? = null
        await(60_000) {
            found = checkpoint(owner)?.get("actions")?.jsonArray?.map { it.jsonObject }?.lastOrNull {
                it["operation"]?.jsonPrimitive?.content == operation && it["sourceId"]?.jsonPrimitive?.content == sourceId
            }
            found?.get("state")?.jsonPrimitive?.content == state
        }
        return requireNotNull(found)
    }

    private suspend fun importSelected(source: Installed): JsonObject {
        val workoutId = source.marker.getString("workoutId")
        val expected = args.getString("fixtureExpectedImportOutcome") ?: "IMPORTED"
        val existing = if (expected == "CONFLICT") ports.reads().read(OnlineRpc.workoutDetail(workoutId)) else null
        val button = hasTestTag("legacy_import_$workoutId") and isEnabled()
        scroll(button)
        compose.onNode(button).performClick()
        waitNode(hasText(label(R.string.legacy_import_confirmation)))
        confirm()
        val action = awaitAction(source.owner, "import_legacy_workout", workoutId, if (expected == "CONFLICT") "CONFLICT" else "CONFIRMED")
        if (expected == "CONFLICT") {
            assertEquals("LEGACY_IMPORT_CONFLICT", action.getValue("failure").jsonPrimitive.content)
            assertEquals(JsonNull, action.getValue("validatedReceipt"))
            assertEquals(OperationState.NOT_FOUND,
                ports.reads().read(OnlineRpc.operationStatus(action.getValue("requestId").jsonPrimitive.content)).state)
            assertEquals("Rejected import must not rewrite an existing target", existing, ports.reads().read(OnlineRpc.workoutDetail(workoutId)))
            assertSourceUnchanged(source)
        } else {
            assertImported(source, action, expected)
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("legacy_import_$workoutId").fetchSemanticsNodes().isEmpty() }
        }
        return action
    }

    private suspend fun assertImported(source: Installed, action: JsonObject, expectedOutcome: String) {
        val marker = source.marker
        val workoutId = marker.getString("workoutId")
        val logId = marker.getString("exerciseLogId")
        val setIds = marker.getJSONArray("setIds").let { values -> (0 until values.length()).map { values.getString(it) }.toSet() }
        val expectedIds = setIds + workoutId + logId
        val receipt = action.getValue("validatedReceipt").jsonObject
        assertEquals(source.owner, action.getValue("ownerId").jsonPrimitive.content)
        assertEquals(source.inventory.let { (it as LegacyInventory.Present).schemaVersion }, action.getValue("sourceSchemaVersion").jsonPrimitive.int)
        assertEquals("WORKOUT", action.getValue("sourceKind").jsonPrimitive.content)
        assertEquals(workoutId, action.getValue("sourceId").jsonPrimitive.content)
        assertEquals(action.getValue("requestId").jsonPrimitive.content, receipt.getValue("request_id").jsonPrimitive.content)
        assertEquals("import_legacy_workout", receipt.getValue("operation").jsonPrimitive.content)
        assertEquals(workoutId, receipt.getValue("workout_id").jsonPrimitive.content)
        assertEquals(expectedOutcome, receipt.getValue("outcome").jsonPrimitive.content)
        assertEquals(action.getValue("aggregateFingerprint"), receipt.getValue("source_fingerprint"))
        assertEquals(action.getValue("serverPayloadDigest"), receipt.getValue("payload_digest"))
        assertTrue(receipt.getValue("payload_digest").jsonPrimitive.content.matches(Regex("[0-9a-f]{64}")))
        assertEquals(1, receipt.getValue("exercise_count").jsonPrimitive.int)
        assertEquals(2, receipt.getValue("set_count").jsonPrimitive.int)
        val mapping = action.getValue("idMapping").jsonObject
        assertEquals(expectedIds, mapping.keys)
        mapping.forEach { (id, remote) -> assertEquals(id, remote.jsonPrimitive.content) }
        assertEquals(mapping, receipt.getValue("id_mapping"))
        val request = action.getValue("requestJson").jsonObject
        assertEquals(source.owner, request.getValue("p_workout").jsonObject.getValue("user_id").jsonPrimitive.content)
        assertEquals(JsonNull, request.getValue("p_exercises").jsonArray.single().jsonObject.getValue("input_defaults"))
        val sourceItem = (source.inventory as LegacyInventory.Present).items.single { it.kind == LegacyKind.WORKOUT }
        val rawParent = source.reader.record(source.owner, sourceItem)
        val parent = request.getValue("p_workout").jsonObject
        for (field in listOf("start_time", "end_time", "created_at", "updated_at")) {
            assertEquals("Import must preserve the stored $field, never substitute now", rawParent.getValue(field)!!.toLong(),
                Instant.parse(parent.getValue(field).jsonPrimitive.content).toEpochMilli())
        }
        assertEquals(Instant.ofEpochMilli(rawParent.getValue("date")!!.toLong()).atOffset(ZoneOffset.UTC).toLocalDate().toString(),
            parent.getValue("date").jsonPrimitive.content)
        val detail = ports.reads().read(OnlineRpc.workoutDetail(workoutId))
        assertEquals(workoutId, detail.id)
        assertEquals(marker.getString("templateId"), detail.templateId)
        assertEquals(1L, detail.totalSets)
        assertEquals(1L, detail.totalReps)
        assertEquals(0, "12.345".toBigDecimal().compareTo(detail.totalVolume.toBigDecimal()))
        val exercise = detail.exerciseLogs.single()
        assertEquals(logId, exercise.id)
        assertEquals(marker.getString("exerciseId"), exercise.exerciseId)
        assertNull(exercise.inputDefaults)
        assertEquals(setIds, exercise.sets.map { it.id }.toSet())
        val completed = exercise.sets.single { it.isCompleted }
        val uncompleted = exercise.sets.single { !it.isCompleted }
        assertEquals(marker.getJSONArray("setIds").getString(0), completed.id)
        assertEquals(marker.getJSONArray("setIds").getString(1), uncompleted.id)
        assertEquals(0, "12.345".toBigDecimal().compareTo(completed.weight.toBigDecimal()))
        assertEquals(0, "1.2345".toBigDecimal().compareTo(uncompleted.weight.toBigDecimal()))
        assertEquals(1, completed.reps)
        assertEquals(2, uncompleted.reps)
        val date = Instant.parse(detail.startTime).atOffset(ZoneOffset.UTC).toLocalDate()
        val history = ports.reads().read(OnlineRpc.history(DateWindow(date.toString(), date.plusDays(1).toString(), "UTC"), limit = 100))
        assertEquals("Replay/restore must not duplicate the selected workout", 1, history.data.count { it.id == workoutId })
        assertSourceUnchanged(source)
    }

    private suspend fun keepHistory(source: Installed) {
        clickRecovery(R.string.legacy_keep_history)
        waitNode(hasText(label(R.string.legacy_keep_confirmation)))
        confirm()
        val historical = (source.inventory as LegacyInventory.Present).items.filter { it.kind in LegacyArchiveReview.historicalKinds }
        val fingerprints = historical.associate { "${it.kind}:${it.id}" to it.fingerprint }
        var saved: JsonObject? = null
        await {
            saved = archive(source.owner)
            saved?.get("entries")?.jsonArray?.map { it.jsonObject }?.associate {
                "${it.getValue("kind").jsonPrimitive.content}:${it.getValue("id").jsonPrimitive.content}" to it.getValue("fingerprint").jsonPrimitive.content
            } == fingerprints
        }
        val entries = saved!!.getValue("entries").jsonArray.map { it.jsonObject }
        assertEquals(historical.map { it.kind.name to it.id }.toSet(),
            entries.map { it.getValue("kind").jsonPrimitive.content to it.getValue("id").jsonPrimitive.content }.toSet())
        entries.forEach { entry ->
            val item = historical.single { it.kind.name == entry.getValue("kind").jsonPrimitive.content && it.id == entry.getValue("id").jsonPrimitive.content }
            assertEquals(item.fingerprint, entry.getValue("fingerprint").jsonPrimitive.content)
        }
        assertTrue(entries.none { it.getValue("kind").jsonPrimitive.content in setOf("ACTIVE_SESSION", "TEMPLATE_INTENT") })
        assertNull(archive(source.other))
        assertSourceUnchanged(source)
    }

    private suspend fun replayIntent(source: Installed) {
        val requestId = source.marker.getString("intentRequestId")
        val item = (source.inventory as LegacyInventory.Present).items.single { it.kind == LegacyKind.TEMPLATE_INTENT }
        assertEquals(requestId, item.requestId)
        assertEquals(OperationState.COMMITTED, ports.reads().read(OnlineRpc.operationStatus(requestId)).state)
        val card = hasTestTag("legacy_item_TEMPLATE_INTENT:${item.id}")
        val replay = hasText(label(R.string.legacy_retry_original)) and hasAnyAncestor(card) and isEnabled()
        scroll(replay)
        compose.onNode(replay).performClick()
        confirm()
        val action = awaitAction(source.owner, "save_template_revision", item.id, "CONFIRMED")
        assertEquals(requestId, action.getValue("requestId").jsonPrimitive.content)
        assertEquals(Json.parseToJsonElement(source.marker.getJSONObject("intentPayload").toString()), action.getValue("requestJson"))
        val receipt = action.getValue("validatedReceipt").jsonObject
        assertEquals(requestId, receipt.getValue("request_id").jsonPrimitive.content)
        assertEquals(source.marker.getString("templateId"), receipt.getValue("template_id").jsonPrimitive.content)
        assertEquals(source.marker.getLong("intentExpectedRevision"), receipt.getValue("revision").jsonPrimitive.long)
        assertEquals(receipt.getValue("revision").jsonPrimitive.long,
            ports.reads().read(OnlineRpc.templateDetail(source.marker.getString("templateId"))).revision)
        assertSourceUnchanged(source)
    }

    private suspend fun abandonLocalActive(source: Installed) {
        val id = source.marker.getString("activeId")
        assertEquals("Simpan sebagai arsip lama, tidak dapat dilanjutkan", label(R.string.legacy_abandon_active))
        val before = checkpoint(source.owner)
        assertTrue(before?.get("localResolutions")?.jsonArray.orEmpty().isEmpty())
        val button = hasTestTag("legacy_abandon_$id") and isEnabled()
        scroll(button)
        compose.onNode(button).performClick()
        waitNode(hasText(label(R.string.legacy_abandon_confirmation)))
        confirm()
        var resolution: JsonObject? = null
        await {
            resolution = checkpoint(source.owner)?.get("localResolutions")?.jsonArray?.map { it.jsonObject }?.singleOrNull {
                it.getValue("sourceId").jsonPrimitive.content == id
            }
            resolution != null
        }
        assertEquals("PRESERVE_NON_RESUMABLE_ARCHIVE", resolution!!.getValue("decision").jsonPrimitive.content)
        val item = (source.inventory as LegacyInventory.Present).items.single { it.kind == LegacyKind.ACTIVE_SESSION && it.id == id }
        assertEquals(item.fingerprint, resolution!!.getValue("sourceFingerprint").jsonPrimitive.content)
        assertEquals(source.owner, resolution!!.getValue("ownerId").jsonPrimitive.content)
        assertTrue(source.reader.record(source.owner, item).getValue("session_data_json")!!.contains(id))
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("legacy_abandon_$id").fetchSemanticsNodes().isEmpty() }
        assertSourceUnchanged(source)
    }

    private suspend fun resolveRemote(source: Installed) {
        val id = source.marker.getString("remoteSessionId")
        when (source.marker.getString("remoteState")) {
            "PAUSED" -> {
                val before = ports.reads().read(OnlineRpc.activeSession(id)).session!!
                assertEquals(SessionStatus.PAUSED, before.effectiveStatus)
                clickRecovery(R.string.action_resume)
                confirm()
                await { resumed.get()?.first == id }
                assertEquals(id to before.templateId, resumed.get())
                val resumedSession = requireNotNull(ports.sessions().state.value.snapshot)
                assertEquals(id, resumedSession.id)
                assertEquals(SessionStatus.ACTIVE, resumedSession.effectiveStatus)
                assertTrue(resumedSession.version > before.version)
                clickRecovery(R.string.action_cancel_workout)
                confirm()
                await { ports.sessions().state.value.lastReceipt?.let { it.sessionId == id && it.status == SessionStatus.CANCELLED } == true }
            }
            "EXPIRED" -> {
                val before = ports.reads().read(OnlineRpc.activeSession(id)).session!!
                assertEquals(SessionStatus.EXPIRED, before.effectiveStatus)
                assertTrue(before.reviewRequired)
                clickRecovery(R.string.legacy_acknowledge_expiry)
                waitNode(hasText(label(R.string.legacy_expiry_confirmation)))
                confirm()
                await {
                    ports.sessions().state.value.lastReceipt?.let {
                        it.sessionId == id && it.version > before.version && it.snapshot.effectiveStatus == SessionStatus.EXPIRED && !it.snapshot.reviewRequired
                    } == true
                }
                assertFalse(ports.reads().read(OnlineRpc.startRecoveryStatus()).blockers.any { it.resourceId == id })
            }
            "LEGACY" -> {
                val original = ports.reads().read(OnlineRpc.legacyRecoveryPage(limit = 100)).data.single { it.sessionId == id }
                assertNull(original.resolution)
                val pageButton = hasTestTag("legacy_server_page") and isEnabled()
                scroll(pageButton)
                compose.onNode(pageButton).performClick()
                clickRecovery(R.string.legacy_keep_server)
                confirm()
                val action = awaitAction(source.owner, "resolve_legacy_session", id, "CONFIRMED")
                val receipt = action.getValue("validatedReceipt").jsonObject
                assertEquals("KEEP_ARCHIVED", receipt.getValue("resolution").jsonPrimitive.content)
                assertEquals(original.sourceFingerprint, receipt.getValue("source_fingerprint").jsonPrimitive.content)
                val archived = ports.reads().read(OnlineRpc.legacyRecoveryPage(limit = 100)).data.single { it.sessionId == id }
                assertEquals("KEEP_ARCHIVED", archived.resolution)
                assertEquals(original.sourceFingerprint, archived.sourceFingerprint)
                assertEquals(original.rawSource, archived.rawSource)
                assertFalse(ports.reads().read(OnlineRpc.startRecoveryStatus()).blockers.any { it.resourceId == id })
            }
            else -> error("Unsupported remote fixture state")
        }
    }

    /** All workout actions below are actual root navigation and Compose controls, not test ports. */
    private suspend fun startThroughUi(templateId: String, expectedOwner: String, ready: Boolean) {
        val detail = ports.reads().read(OnlineRpc.templateDetail(templateId))
        compose.activityRule.scenario.recreate()
        waitNode(hasTestTag("home_refresh"))
        scroll(hasText(detail.name), hasScrollToNodeAction())
        compose.onNodeWithText(detail.name).performClick()
        waitNode(hasTestTag("routine_detail_list"))
        val start = hasText(label(R.string.action_start_workout)) and hasClickAction() and isEnabled()
        waitNode(start)
        compose.onNode(start).performClick()
        if (!ready) {
            await { ports.sessions().state.value.recovery != null }
            val state = ports.sessions().state.value
            assertEquals(expectedOwner, state.recovery!!.identity.ownerId)
            assertNotEquals(LegacyStartDecision.READY, state.recovery!!.decision)
            assertNull(state.snapshot)
            assertNull(state.pendingRequestId)
            return
        }
        await { ports.sessions().state.value.snapshot?.let { it.templateId == templateId && it.effectiveStatus == SessionStatus.ACTIVE } == true }
        val state = ports.sessions().state.value
        assertEquals(expectedOwner, state.identity?.ownerId)
        assertEquals("create_active_session", state.lastReceipt?.operation)
        assertNull(state.pendingRequestId)
        val sessionId = requireNotNull(state.snapshot).id
        val cancel = hasContentDescription(label(R.string.action_cancel)) and hasAnyAncestor(hasTestTag("active_workout_top_bar")) and isEnabled()
        waitNode(cancel)
        compose.onNode(cancel).performClick()
        waitNode(hasTestTag("cancel_workout_dialog"))
        compose.onNode(hasText(label(R.string.active_session_cancel_confirm)) and hasAnyAncestor(hasTestTag("cancel_workout_dialog"))).performClick()
        await { ports.sessions().state.value.lastReceipt?.let { it.sessionId == sessionId && it.status == SessionStatus.CANCELLED } == true }
    }

    @Test fun installedSourceRemainsOwnerScopedAndReadableThroughRecovery() = runBlocking {
        val source = installed()
        val inventory = source.inventory
        when (source.scenario) {
            "fresh" -> { assertTrue(inventory is LegacyInventory.Absent); assertFalse(source.database.exists()) }
            "unknown4", "unknown8", "corrupt" -> assertTrue("Unknown source must fail closed, never become fresh", inventory is LegacyInventory.Unknown)
            else -> {
                assertTrue("Installed exported schema must be readable: $inventory", inventory is LegacyInventory.Present)
                inventory as LegacyInventory.Present
                assertEquals(required("fixtureSchema").toInt(), inventory.schemaVersion)
                assertEquals(2, inventory.items.count { it.kind == LegacyKind.SET })
                val otherInventory = source.reader.inspect(source.other) as LegacyInventory.Present
                assertTrue(inventory.items.map { it.id }.intersect(otherInventory.items.map { it.id }.toSet()).isEmpty())
                inventory.items.forEach { assertFalse("Foreign owner record leaked", source.reader.record(source.owner, it)["user_id"] == source.other) }
                val weights = inventory.items.filter { it.kind == LegacyKind.SET }.map { source.reader.record(source.owner, it)["weight"] }
                assertTrue(weights.contains(source.marker.optString("completedWeight", "12.345")))
                assertTrue(weights.contains("1.2345"))
            }
        }
        assertEquals(source.owner, ports.auth().login(required("readyEmail"), required("readyPassword")).getOrThrow().id)
        showRecovery(inventory)
        if (inventory is LegacyInventory.Present) {
            if (args.getString("fixtureRestorePhase") == "restored") {
                assertNull("Restored DB alone must not restore recovery authority", checkpoint(source.owner))
                assertNull(archive(source.owner))
            }
            val workout = inventory.items.single { it.kind == LegacyKind.WORKOUT }
            val previousActions = if (args.getString("fixtureRestorePhase") == "changed") {
                val previous = requireNotNull(checkpoint(source.owner)).getValue("actions").jsonArray
                val confirmed = previous.map { it.jsonObject }.last { it.getValue("sourceId").jsonPrimitive.content == workout.id }
                assertEquals("CONFIRMED", confirmed.getValue("state").jsonPrimitive.content)
                assertNotEquals("Changed restored source must not inherit old receipt coverage",
                    workout.fingerprint, confirmed.getValue("sourceFingerprint").jsonPrimitive.content)
                assertNotNull(archive(source.owner))
                previous
            } else null
            scroll(hasTestTag("legacy_item_WORKOUT:${workout.id}"))
            compose.onNodeWithTag("legacy_item_WORKOUT:${workout.id}").performClick()
            scroll(hasTestTag("legacy_record_fields"))
            compose.onNodeWithTag("legacy_record_fields").assertTextContains(workout.id, substring = true)
            compose.onNodeWithTag("legacy_record_fields").assertTextContains(source.owner, substring = true)
            compose.onNodeWithTag("legacy_record_fields").assertTextContains(source.marker.optString("completedWeight", "12.345"), substring = true)
            importSelected(source)
            previousActions?.let {
                assertEquals("Re-review must retain the immutable original confirmed action", it,
                    JsonArray(requireNotNull(checkpoint(source.owner)).getValue("actions").jsonArray.take(it.size)))
            }
            keepHistory(source)
            if (source.marker.has("intentRequestId")) replayIntent(source)
            if (source.marker.has("activeId")) abandonLocalActive(source)
            if (source.marker.has("remoteSessionId")) resolveRemote(source)
            assertNull("A review must not create B recovery authority", checkpoint(source.other))
            startThroughUi(source.marker.getString("templateId"), source.owner, ready = true)
            assertSourceUnchanged(source)
            ports.auth().logout()
            assertEquals(source.other, ports.auth().login(required("readyOtherEmail"), required("readyOtherPassword")).getOrThrow().id)
            val otherInventory = source.reader.inspect(source.other)
            showRecovery(otherInventory)
            scroll(hasText(source.marker.getString("otherWorkoutId")))
            compose.onAllNodesWithText(workout.id).assertCountEquals(0)
            assertNull(checkpoint(source.other))
            assertNull(archive(source.other))
            val foreign = try {
                ports.reads().read(OnlineRpc.workoutDetail(workout.id))
                null
            } catch (failure: OnlineFailure) { failure.reason }
            assertEquals("B must not read the confirmed or conflicting A aggregate", RemoteError.NotFound, foreign)
            startThroughUi(source.marker.getString("otherTemplateId"), source.other, ready = false)
            assertEquals("Unresolved B must stay untouched while resolved A can Start", otherInventory, source.reader.inspect(source.other))
            assertEquals("Logout/account switch must not prune A", inventory, source.reader.inspect(source.owner))
        } else {
            startThroughUi(source.marker.getString("templateId"), source.owner, ready = inventory is LegacyInventory.Absent)
            if (inventory is LegacyInventory.Unknown) assertEquals(LegacyStartDecision.UNKNOWN, ports.sessions().state.value.recovery?.decision)
        }
        ports.auth().logout()
        assertSourceUnchanged(source)
    }

    /** A real upstream commit with a lost HTTP response; host kills the exact live process. */
    @Test fun prepareLegacyImportResponseLoss() = runBlocking<Unit> {
        val source = installed()
        check(source.scenario == "crash-import")
        check(source.inventory is LegacyInventory.Present)
        assertNull(checkpoint(source.owner))
        assertEquals(source.owner, ports.auth().login(required("readyEmail"), required("readyPassword")).getOrThrow().id)
        showRecovery(source.inventory)
        val proxy = ProductionReadyFixture()
        val control = proxy.proxyControl()
        proxy.control("$control/arm?operation=import_legacy_workout", "POST")
        val workoutId = source.marker.getString("workoutId")
        val button = hasTestTag("legacy_import_$workoutId") and isEnabled()
        scroll(button); compose.onNode(button).performClick(); confirm()
        var observed: JsonObject? = null
        await(60_000) {
            observed = proxy.control("$control/status")
            observed?.get("committed")?.jsonPrimitive?.booleanOrNull == true && observed?.get("dropped")?.jsonPrimitive?.booleanOrNull == true
        }
        val action = awaitAction(source.owner, "import_legacy_workout", workoutId, "SUBMITTED")
        val receipt = requireNotNull(observed).getValue("receipt").jsonObject
        assertEquals(action.getValue("requestId"), receipt.getValue("request_id"))
        assertEquals(workoutId, receipt.getValue("workout_id").jsonPrimitive.content)
        assertSourceUnchanged(source)
        val evidence = buildJsonObject {
            put("pid", Process.myPid()); put("action", action); put("receipt", receipt)
            put("hashes", buildJsonObject { source.hashes.forEach { (name, hash) -> put(name, hash?.let(::JsonPrimitive) ?: JsonNull) } })
        }
        java.io.FileOutputStream(File(context.noBackupFilesDir, "legacy_ready_crash_observation.json")).use {
            it.write(evidence.toString().toByteArray(Charsets.UTF_8)); it.fd.sync()
        }
        Log.i("IMFITReady", "event=legacy_restart_armed pid=${Process.myPid()}")
        awaitCancellation()
    }

    @Test fun verifyLegacyImportResponseLoss() = runBlocking {
        val source = installed()
        check(source.scenario == "crash-import")
        val evidence = Json.parseToJsonElement(File(context.noBackupFilesDir, "legacy_ready_crash_observation.json").readText()).jsonObject
        assertNotEquals(evidence.getValue("pid").jsonPrimitive.int, Process.myPid())
        val original = evidence.getValue("action").jsonObject
        val workoutId = source.marker.getString("workoutId")
        val pending = awaitAction(source.owner, "import_legacy_workout", workoutId, "SUBMITTED")
        assertEquals(original, pending)
        source.hashes.forEach { (name, hash) -> assertEquals(hash, evidence.getValue("hashes").jsonObject.getValue(name).jsonPrimitive.contentOrNull) }
        assertEquals(source.owner, ports.auth().login(required("readyEmail"), required("readyPassword")).getOrThrow().id)
        showRecovery(source.inventory)
        val resolve = hasTestTag("legacy_resolve_${original.getValue("actionId").jsonPrimitive.content}")
        scroll(resolve); compose.onNode(resolve).performClick(); confirm()
        val confirmed = awaitAction(source.owner, "import_legacy_workout", workoutId, "CONFIRMED")
        for (field in listOf("actionId", "ownerId", "sourceId", "sourceFingerprint", "aggregateFingerprint", "dependencyFingerprint", "requestId", "requestJson", "bodyDigest", "idMapping")) {
            assertEquals("Crash recovery must preserve immutable $field", original.getValue(field), confirmed.getValue(field))
        }
        assertEquals(evidence.getValue("receipt"), confirmed.getValue("validatedReceipt"))
        assertImported(source, confirmed, "IMPORTED")
        ports.auth().logout()
        assertSourceUnchanged(source)
    }

    /** Host attaches an fsync/transport barrier before opening the phase gate; all import actions
     * remain ordinary UI actions. The host records the durable AtomicFile authority, not a pending write. */
    @Test fun prepareLegacyCrashWindow() = runBlocking<Unit> {
        val source = installed()
        val point = required("fixtureCrashPoint")
        check(point in setOf("prepared", "submitted", "response-before-checkpoint"))
        check(source.scenario == "crash-$point" && source.inventory is LegacyInventory.Present)
        assertNull(checkpoint(source.owner))
        assertEquals(source.owner, ports.auth().login(required("readyEmail"), required("readyPassword")).getOrThrow().id)
        showRecovery(source.inventory)
        val proxy = ProductionReadyFixture()
        val control = proxy.proxyControl()
        Log.i("IMFITReady", "event=legacy_crash_ready pid=${Process.myPid()}")
        await(60_000) { proxy.control("$control/phase")["armed"]?.jsonPrimitive?.booleanOrNull == true }
        val button = hasTestTag("legacy_import_${source.marker.getString("workoutId")}") and isEnabled()
        scroll(button)
        compose.onNode(button).performClick()
        waitNode(hasText(label(R.string.legacy_import_confirmation)))
        confirm()
        awaitCancellation()
    }

    @Test fun verifyLegacyCrashWindow() = runBlocking {
        val source = installed()
        val point = required("fixtureCrashPoint")
        check(point in setOf("prepared", "submitted", "response-before-checkpoint"))
        check(source.scenario == "crash-$point")
        val evidence = Json.parseToJsonElement(File(context.noBackupFilesDir, "legacy_ready_crash_observation.json").readText()).jsonObject
        assertNotEquals(evidence.getValue("pid").jsonPrimitive.int, Process.myPid())
        val original = evidence.getValue("action").jsonObject
        val workoutId = source.marker.getString("workoutId")
        val durableState = if (point == "prepared") "PREPARED" else "SUBMITTED"
        val pending = awaitAction(source.owner, "import_legacy_workout", workoutId, durableState)
        assertEquals("Process restart must restore the original durable action", original, pending)
        source.hashes.forEach { (name, hash) ->
            assertEquals(hash, evidence.getValue("hashes").jsonObject.getValue(name).jsonPrimitive.contentOrNull)
        }
        assertEquals(source.owner, ports.auth().login(required("readyEmail"), required("readyPassword")).getOrThrow().id)
        val requestId = original.getValue("requestId").jsonPrimitive.content
        val lookup = ports.reads().read(OnlineRpc.operationStatus(requestId))
        assertEquals(if (point == "response-before-checkpoint") OperationState.COMMITTED else OperationState.NOT_FOUND, lookup.state)
        showRecovery(source.inventory)
        val actionId = original.getValue("actionId").jsonPrimitive.content
        val resolve = hasTestTag("legacy_resolve_$actionId") and isEnabled()
        scroll(resolve)
        compose.onNode(resolve).performClick()
        confirm()
        if (lookup.state == OperationState.NOT_FOUND) {
            scroll(hasText(label(R.string.legacy_retry_required)))
            assertEquals("Lookup absence must not manufacture a fresh action", original,
                awaitAction(source.owner, "import_legacy_workout", workoutId, durableState))
            val retry = hasTestTag("legacy_retry_$actionId") and isEnabled()
            scroll(retry)
            compose.onNode(retry).performClick()
            confirm()
        }
        val confirmed = awaitAction(source.owner, "import_legacy_workout", workoutId, "CONFIRMED")
        for (field in listOf("actionId", "ownerId", "sourceSchemaVersion", "sourceKind", "sourceId", "sourceFingerprint",
            "aggregateFingerprint", "dependencyFingerprint", "operation", "requestId", "requestJson", "bodyDigest", "idMapping", "reviewDecision")) {
            assertEquals("Recovery must replay the immutable original $field", original.getValue(field), confirmed.getValue(field))
        }
        val observedReceipt = evidence.getValue("receipt")
        if (point == "response-before-checkpoint") {
            assertTrue(observedReceipt is JsonObject)
            assertEquals(observedReceipt, confirmed.getValue("validatedReceipt"))
        } else assertEquals(JsonNull, observedReceipt)
        assertImported(source, confirmed, "IMPORTED")
        ports.auth().logout()
        assertSourceUnchanged(source)
    }
}

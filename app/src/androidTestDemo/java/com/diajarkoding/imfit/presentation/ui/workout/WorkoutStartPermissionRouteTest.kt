package com.diajarkoding.imfit.presentation.ui.workout

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.diajarkoding.imfit.MainActivity
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.core.service.WorkoutService
import com.diajarkoding.imfit.data.local.FakeExerciseDataSource
import com.diajarkoding.imfit.di.RestTimerEntryPoint
import com.diajarkoding.imfit.theme.LocaleManager
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Permission changes are restricted to the disposable diagnostic package; no production data. */
class WorkoutStartPermissionRouteTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @Test fun allowIsRequestedBeforeSessionCreation() = scenario("allow")
    @Test fun denialStillStartsSessionWithoutTheOldWarning() = scenario("deny")
    @Test fun alreadyGrantedStartsWithoutPermissionPopup() = scenario("granted")
    @Test fun previouslyDeniedDoesNotPromptAgain() = scenario("asked")
    @Test fun systemDenialFromOlderBuildDoesNotPromptAgain() = scenario("system-denied")
    @Test fun resumingDoesNotPromptOrCreateAnotherSession() = scenario("resume")

    private fun scenario(mode: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val pkg = context.packageName
        assumeTrue(pkg.endsWith(".modaldiagnostic"))
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        val automation = instrumentation.uiAutomation
        // The host prepares denial/clears permission flags BEFORE instrumentation starts.
        // Revoking inside the target process can kill the instrumentation itself.
        assumeTrue("Prepare notification permission on the isolated package before running this case",
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
        if (mode == "granted") automation.grantRuntimePermission(pkg, Manifest.permission.POST_NOTIFICATIONS)
        val prefs = context.getSharedPreferences(WORKOUT_NOTIFICATION_PREFERENCES, Context.MODE_PRIVATE)
        prefs.edit().clear().putBoolean(WORKOUT_NOTIFICATION_ASKED, mode == "asked").commit()
        val ports = EntryPointAccessors.fromApplication(rule.activity.application, RestTimerEntryPoint::class.java)
        val sessions = ports.sessions()
        val reads = ports.reads()
        val user = waitForOwner(ports.auth())
        val workout = FakeExerciseDataSource.exercises.first()
        waitFor(text(R.string.redesign_ready))
        val templateId = UUID.randomUUID().toString()
        val name = "Permission fixture $mode"
        val receipt = runBlocking {
            sessions.reload()?.let { sessions.cancel(it.id) }
            reads.saveTemplate(com.diajarkoding.imfit.data.remote.TemplateWriteRequest(UUID.randomUUID().toString(), templateId, null,
                name, listOf(com.diajarkoding.imfit.data.remote.TemplateWriteExercise(workout.id, 0, 3, 10, 60)), emptyList(), ownerId = user))
        }
        try {
            rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(name))
            rule.onNodeWithText(name).performClick()
            waitFor(text(R.string.action_start_workout))
            val resumed = if (mode == "resume") runBlocking { sessions.start(templateId, receipt.revision) } else null
            if (resumed != null) {
                // Enter via the real Home resume affordance. An externally seeded session does
                // not itself emit an observeTemplate update to the already open Detail fixture.
                rule.onNodeWithContentDescription(text(R.string.action_back)).performClick()
                waitFor(text(R.string.nav_home))
                rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(name))
                rule.waitUntil(10_000) { rule.onAllNodesWithText(text(R.string.home_resume), substring = true).fetchSemanticsNodes().isNotEmpty() }
                rule.onNodeWithText(name).performClick()
            } else rule.onNodeWithText(text(R.string.action_start_workout)).performClick()
            val device = UiDevice.getInstance(instrumentation)
            val allow = By.res("com.android.permissioncontroller", "permission_allow_button")
            if (mode in listOf("allow", "deny")) {
                assertTrue("Permission dialog expected", device.wait(Until.hasObject(allow), 5_000))
                assertNull("Session must not exist before consent result", sessions.state.value.snapshot)
                capture("permission-$mode-before-session")
                device.findObject(if (mode == "allow") allow else By.res("com.android.permissioncontroller", "permission_deny_button")).click()
            }
            rule.waitUntil(15_000) { sessions.state.value.snapshot != null }
            val session = runBlocking { requireNotNull(sessions.reload()) }
            if (resumed != null) assertEquals(resumed.id, session.id)
            rule.waitForIdle()
            assertFalse(device.hasObject(allow))
            rule.onAllNodesWithText(text(R.string.rest_alert_limited)).assertCountEquals(0)
            capture("permission-$mode-active")
        } finally {
            runBlocking {
                sessions.state.value.snapshot?.takeIf {
                    it.effectiveStatus == com.diajarkoding.imfit.data.remote.online.SessionStatus.ACTIVE ||
                        it.effectiveStatus == com.diajarkoding.imfit.data.remote.online.SessionStatus.PAUSED
                }?.let { sessions.cancel(it.id) }
                reads.saveTemplate(com.diajarkoding.imfit.data.remote.TemplateWriteRequest(UUID.randomUUID().toString(), templateId,
                    receipt.revision, name, listOf(com.diajarkoding.imfit.data.remote.TemplateWriteExercise(workout.id, 0, 3, 10, 60)),
                    emptyList(), isDeleted = true, ownerId = user))
            }
            context.stopService(Intent(context, WorkoutService::class.java))
            prefs.edit().clear().commit()
        }
    }

    private fun waitForOwner(auth: com.diajarkoding.imfit.domain.repository.AuthRepository): String {
        val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
        while (true) {
            val value: String? = runBlocking { auth.getLocalUserId() }
            if (value != null) return value
            require(android.os.SystemClock.elapsedRealtime() < deadline) { "Auth identity missing" }
            Thread.sleep(250)
        }
    }

    private fun waitFor(value: String) = rule.waitUntil(15_000) { rule.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty() }
    private fun text(id: Int) = LocaleManager.getUpdatedContext(rule.activity).getString(id)
    private fun capture(name: String) {
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val dir = File(rule.activity.filesDir, "polish-evidence").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

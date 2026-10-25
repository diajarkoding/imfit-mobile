package com.diajarkoding.imfit.core.timer

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.diajarkoding.imfit.BuildConfig
import com.diajarkoding.imfit.MainActivity
import com.diajarkoding.imfit.core.notification.NotificationChannels
import com.diajarkoding.imfit.core.service.WorkoutService
import com.diajarkoding.imfit.data.local.entity.ActiveSessionEntity
import com.diajarkoding.imfit.di.LocalSessionEntryPoint
import com.diajarkoding.imfit.di.RestTimerEntryPoint
import dagger.hilt.android.EntryPointAccessors
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Real production Hilt/auth/Room/AlarmManager/FGS, with a local synthetic session and no network.
 * Refuses existing accounts/workouts. Never calls remote login, sync, finishWorkout, or signOut.
 */
@RunWith(AndroidJUnit4::class)
class RestTimerProductionReleaseTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val device get() = UiDevice.getInstance(instrumentation)

    private fun shell(command: String): String {
        val user = InstrumentationRegistry.getArguments().getString("androidUser") ?: "0"
        check(android.os.Process.myUid() / 100_000 == user.toInt()) { "Instrumentation user mismatch" }
        val scoped = command.replace("pm grant ", "pm grant --user $user ")
            .replace("appops get ", "appops get --user $user ")
            .replace("appops set ", "appops set --user $user ")
        return instrumentation.uiAutomation.executeShellCommand(scoped).use { fd ->
        java.io.FileInputStream(fd.fileDescriptor).use { String(it.readBytes()) }
        }
    }

    private suspend fun awaitCondition(timeoutMs: Long = 10_000, condition: suspend () -> Boolean) {
        withTimeout(timeoutMs) { while (!condition()) delay(50) }
    }

    private fun snapshot(scenario: String, phase: String, state: RestTimerState) {
        Log.i("IMFITRestTest", "event=test_snapshot scenario=$scenario phase=$phase " +
            "restInstanceId=${RestTimerCorrelation.instanceId(state.timerId)} generation=${state.generation} " +
            "pid=${android.os.Process.myPid()} now=${SystemClock.elapsedRealtime()} deadlineElapsedMs=${state.deadlineElapsedMs}")
    }

    private suspend fun scenario(name: String, screenOff: Boolean = false, doze: Boolean = false,
        exact: Boolean = true, cancel: Boolean = false, receiverOnly: Boolean = false,
        brokenRendering: Boolean = false) {
        assertFalse("Must test minified productionRelease, not debug", BuildConfig.DEBUG)
        check(InstrumentationRegistry.getArguments().getString("allowSyntheticSession") == "true")
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone")) { "Emulator only" }
        awaitCondition { context.getSystemService(ConnectivityManager::class.java).activeNetwork == null }
        val local = EntryPointAccessors.fromApplication(context, LocalSessionEntryPoint::class.java)
        val auth = local.supabase().auth
        withTimeout(10_000) { auth.awaitInitialization() }
        check(auth.currentUserOrNull()?.id.let { it == null || it == OWNER }) { "Refusing an existing account" }
        val dao = local.database().activeSessionDao()
        check(dao.getAnyActiveSession()?.userId.let { it == null || it == OWNER }) { "Refusing an existing workout" }
        auth.stopAutoRefreshForCurrentSession()
        auth.importSession(UserSession(accessToken = "offline-synthetic-not-a-server-token",
            refreshToken = "offline-synthetic-not-a-refresh-token", expiresIn = 86_400, tokenType = "bearer",
            user = UserInfo(aud = "authenticated", id = OWNER, email = "rest-regression@example.invalid")), autoRefresh = false)
        val ports = EntryPointAccessors.fromApplication(context, RestTimerEntryPoint::class.java)
        val timer = ports.timer()
        val runtime = ports.runtime()
        val manager = context.getSystemService(NotificationManager::class.java)
        val sessionId = UUID.randomUUID().toString()
        val started = System.currentTimeMillis()
        dao.deleteSession(OWNER)
        dao.insertSession(ActiveSessionEntity(id = sessionId, userId = OWNER, templateId = "offline-template",
            templateName = "Offline Regression", startTime = started,
            sessionDataJson = """{"id":"$sessionId","templateId":"offline-template","templateName":"Offline Regression","startTime":$started,"exerciseLogs":[]}"""))
        val originalFsi = shell("appops get ${context.packageName} USE_FULL_SCREEN_INTENT")
        var forcedIdle = false
        try {
            if (Build.VERSION.SDK_INT >= 33) shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
            // Configure exact access from the host BEFORE instrumentation: revocation kills the target process.
            assertEquals("Host must configure exact access before starting instrumentation", exact, runtime.exactAlarmAvailable())
            if (Build.VERSION.SDK_INT >= 34) shell("appops set ${context.packageName} USE_FULL_SCREEN_INTENT deny")
            device.wakeUp()
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            assertTrue(device.wait(Until.hasObject(By.pkg(context.packageName).depth(0)), 10_000))
            if (!receiverOnly) {
                // Framework call; ContextCompat may be inlined away from the minified app classpath.
                context.startForegroundService(Intent(context, WorkoutService::class.java).apply {
                    action = WorkoutService.ACTION_START_WORKOUT
                })
                awaitCondition { withContext(Dispatchers.Main) { runtime.foregroundServiceActive } }
            }
            withContext(Dispatchers.Main) {
                timer.start(RestTimerState("", userId = OWNER, workoutId = sessionId, templateId = "offline-template",
                    exerciseId = "offline-exercise", setId = UUID.randomUUID().toString(), exerciseName = "Offline Exercise",
                    durationMs = if (brokenRendering) 3000 else 60_000,
                    deadlineElapsedMs = 0, bootIdentity = null, startedAtEpochMs = 0))
                if (!brokenRendering) { timer.extend(); timer.extend() }
            }
            val rest = requireNotNull(timer.state.value)
            assertEquals(if (brokenRendering) 0L else 2L, rest.generation)
            assertEquals(if (exact) SchedulingMode.EXACT else SchedulingMode.BEST_EFFORT, rest.schedulingMode)
            if (brokenRendering) {
                // Isolate the real FGS ticker. This fault-injection test is NOT an AlarmManager E2E assertion.
                val row = requireNotNull(dao.getSessionById(sessionId))
                dao.updateSession(row.copy(sessionDataJson = "broken-rendering-json"))
                withContext(Dispatchers.Main) { runtime.cancelAlarm(rest) }
            }
            snapshot(name, "armed", rest)
            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            assertTrue(device.wait(Until.hasObject(By.pkg("com.android.settings").depth(0)), 10_000))
            if (screenOff) device.sleep()
            if (doze) {
                shell("dumpsys battery unplug")
                forcedIdle = true
                shell("dumpsys deviceidle force-idle")
                assertTrue("Doze entry must be observed", shell("dumpsys deviceidle get deep").contains("IDLE"))
            }
            if (cancel) withContext(Dispatchers.Main) { timer.cancel("REGRESSION_CANCEL") }
            // Deadline-based observation, not a sleep used to assume startup or completion.
            awaitCondition(125_000) { SystemClock.elapsedRealtime() >= rest.deadlineElapsedMs - 2000 }
            snapshot(name, "before_deadline", rest)
            if (cancel) {
                awaitCondition(15_000) { SystemClock.elapsedRealtime() >= rest.deadlineElapsedMs + 3000 }
                assertEquals(TimerStatus.CANCELLED, timer.state.value?.status)
                assertTrue(manager.activeNotifications.none { it.id == NotificationChannels.REST_FINISHED_NOTIFICATION_ID })
            } else {
                awaitCondition(if (brokenRendering) 10_000 else 120_000) { timer.state.value?.status == TimerStatus.EXPIRED }
                awaitCondition { manager.activeNotifications.any { it.id == NotificationChannels.REST_FINISHED_NOTIFICATION_ID } }
                assertEquals(rest.deadlineElapsedMs, timer.state.value?.deadlineElapsedMs)
                // Notification posting is asserted, not System UI heads-up visibility.
                if (exact && !doze) assertTrue("Exact commit lateness exceeded 5s",
                    SystemClock.elapsedRealtime() - rest.deadlineElapsedMs <= 5000)
                if (!screenOff && !doze) assertTrue(device.hasObject(By.pkg("com.android.settings").depth(0)))
            }
            snapshot(name, "after_deadline", rest)
        } finally {
            if (forcedIdle) { shell("dumpsys deviceidle unforce"); shell("dumpsys battery reset") }
            device.wakeUp()
            withContext(Dispatchers.Main) { timer.cancel("REGRESSION_CLEANUP", endWorkout = true) }
            context.stopService(Intent(context, WorkoutService::class.java))
            dao.deleteSession(OWNER)
            auth.clearSession()
            if (Build.VERSION.SDK_INT >= 34) {
                val mode = Regex("USE_FULL_SCREEN_INTENT: (allow|deny|ignore|default)").find(originalFsi)?.groupValues?.get(1) ?: "default"
                shell("appops set ${context.packageName} USE_FULL_SCREEN_INTENT $mode")
            }
        }
    }

    @Test fun backgroundExactProductionRelease() = runBlocking { scenario("background-exact") }
    @Test fun screenOffExactProductionRelease() = runBlocking { scenario("screen-off", screenOff = true) }
    @Test fun forcedDozeExactProductionRelease() = runBlocking { scenario("doze", screenOff = true, doze = true) }
    @Test fun exactDeniedUsesInexactProductionRelease() = runBlocking { scenario("inexact", exact = false) }
    @Test fun cancelledRestNeverAlertsProductionRelease() = runBlocking { scenario("cancel", cancel = true) }
    @Test fun receiverWithoutServiceProductionRelease() = runBlocking { scenario("receiver-only", receiverOnly = true) }
    @Test fun sessionRenderingReadFailureMustNotBlockExpiry() = runBlocking {
        scenario("session-read-failure", brokenRendering = true)
    }

    /** Two-phase host test: deliberately leaves ONLY synthetic local state for a real cold receiver. */
    @Test fun prepareColdProcessFixture() = runBlocking<Unit> {
        assertFalse(BuildConfig.DEBUG)
        check(InstrumentationRegistry.getArguments().getString("allowSyntheticSession") == "true")
        check(Build.MODEL.contains("sdk_gphone"))
        awaitCondition { context.getSystemService(ConnectivityManager::class.java).activeNetwork == null }
        val local = EntryPointAccessors.fromApplication(context, LocalSessionEntryPoint::class.java)
        val auth = local.supabase().auth
        withTimeout(10_000) { auth.awaitInitialization() }
        check(auth.currentUserOrNull()?.id.let { it == null || it == OWNER })
        val dao = local.database().activeSessionDao()
        check(dao.getAnyActiveSession()?.userId.let { it == null || it == OWNER })
        auth.stopAutoRefreshForCurrentSession()
        auth.importSession(UserSession("offline-synthetic-not-a-server-token", "offline-synthetic-not-a-refresh-token",
            expiresIn = 86_400, tokenType = "bearer", user = UserInfo(aud = "authenticated", id = OWNER)), autoRefresh = false)
        val sessionId = UUID.randomUUID().toString()
        val started = System.currentTimeMillis()
        dao.deleteSession(OWNER)
        dao.insertSession(ActiveSessionEntity(id = sessionId, userId = OWNER, templateId = "offline-template",
            templateName = "Offline Regression", startTime = started,
            sessionDataJson = """{"id":"$sessionId","templateId":"offline-template","templateName":"Offline Regression","startTime":$started,"exerciseLogs":[]}"""))
        val ports = EntryPointAccessors.fromApplication(context, RestTimerEntryPoint::class.java)
        assertTrue(ports.runtime().exactAlarmAvailable())
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        shell("appops set ${context.packageName} USE_FULL_SCREEN_INTENT deny")
        withContext(Dispatchers.Main) {
            ports.timer().start(RestTimerState("", userId = OWNER, workoutId = sessionId, templateId = "offline-template",
                exerciseId = "offline-exercise", setId = "cold-set", exerciseName = "Offline Exercise",
                durationMs = 60_000, deadlineElapsedMs = 0, bootIdentity = null, startedAtEpochMs = 0))
        }
        val rest = requireNotNull(ports.timer().state.value)
        context.getSharedPreferences("rest_regression", Context.MODE_PRIVATE).edit()
            .putString("timer_id", rest.timerId).putInt("prepared_pid", android.os.Process.myPid()).commit()
        snapshot("cold", "armed", rest)
        // Finishing instrumentation produces REASON_USER_REQUESTED ("finished inst").
        // Remain alive until the host kills this exact PID, without force-stopping the app.
        awaitCancellation()
    }

    @Test fun verifyAndCleanColdProcessFixture() = runBlocking {
        check(InstrumentationRegistry.getArguments().getString("allowSyntheticSession") == "true")
        val local = EntryPointAccessors.fromApplication(context, LocalSessionEntryPoint::class.java)
        withTimeout(10_000) { local.supabase().auth.awaitInitialization() }
        check(local.supabase().auth.currentUserOrNull()?.id == OWNER)
        val ports = EntryPointAccessors.fromApplication(context, RestTimerEntryPoint::class.java)
        val prefs = context.getSharedPreferences("rest_regression", Context.MODE_PRIVATE)
        try {
            withContext(Dispatchers.Main) { ports.timer().restore() } // Read only; never reconcile to make the test pass.
            val rest = requireNotNull(ports.timer().state.value)
            assertEquals(prefs.getString("timer_id", null), rest.timerId)
            assertEquals(TimerStatus.EXPIRED, rest.status)
            assertTrue(rest.presentationHandled)
            assertNotEquals(prefs.getInt("prepared_pid", -1), android.os.Process.myPid())
            snapshot("cold", "verified", rest)
        } finally {
            withContext(Dispatchers.Main) { ports.timer().cancel("COLD_TEST_CLEANUP", endWorkout = true) }
            context.stopService(Intent(context, WorkoutService::class.java))
            local.database().activeSessionDao().deleteSession(OWNER)
            local.supabase().auth.clearSession()
            prefs.edit().clear().commit()
        }
    }

    companion object { private const val OWNER = "00000000-0000-4000-8000-000000000034" }
}

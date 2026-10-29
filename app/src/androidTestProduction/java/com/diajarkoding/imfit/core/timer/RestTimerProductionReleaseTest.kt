package com.diajarkoding.imfit.core.timer

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.diajarkoding.imfit.MainActivity
import com.diajarkoding.imfit.ProductionReadyFixture
import com.diajarkoding.imfit.core.notification.NotificationChannels
import com.diajarkoding.imfit.core.service.WorkoutService
import com.diajarkoding.imfit.data.remote.online.SessionStatus
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real local Auth/PostgREST receipts and the production store/AlarmManager/FGS. Host cuts
 * connectivity only after the confirmed trace and SIGKILLs only the reported exact fixture PID. */
@RunWith(AndroidJUnit4::class)
class RestTimerProductionReleaseTest {
    private val fixture by lazy { ProductionReadyFixture() }
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = fixture.context
    private val ports get() = fixture.ports
    private val device get() = UiDevice.getInstance(instrumentation)
    private val preferences get() = context.getSharedPreferences("rest_regression", Context.MODE_PRIVATE)
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val scenarioName get() = InstrumentationRegistry.getArguments().getString("timerScenario") ?: "warm"
    private fun offline() = context.getSystemService(ConnectivityManager::class.java).activeNetwork == null

    private fun shell(command: String): String {
        fixture.guard()
        val user = fixture.argument("androidUser")
        val scoped = command.replace("pm grant ", "pm grant --user $user ")
            .replace("appops get ", "appops get --user $user ")
            .replace("appops set ", "appops set --user $user ")
        return instrumentation.uiAutomation.executeShellCommand(scoped).use { fd ->
            java.io.FileInputStream(fd.fileDescriptor).use { String(it.readBytes()) }
        }
    }

    private fun snapshot(name: String, phase: String, state: RestTimerState, extra: String = "") {
        Log.i("IMFITRestTest", "event=test_snapshot scenario=$name phase=$phase runId=${fixture.argument("timerRunId")} " +
            "restInstanceId=${RestTimerCorrelation.instanceId(state.timerId)} generation=${state.generation} " +
            "pid=${Process.myPid()} now=${SystemClock.elapsedRealtime()} deadlineElapsedMs=${state.deadlineElapsedMs} " +
            "status=${state.status} fixture=true confirmed=true backend=loopback $extra")
    }

    private suspend fun arm(name: String, cold: Boolean = false): RestTimerState {
        val session = fixture.confirmedSession()
        assertEquals(SessionStatus.ACTIVE, session.effectiveStatus)
        assertFalse(session.reviewRequired)
        val exercise = session.exercises.single()
        val completed = exercise.sets.single()
        assertTrue(completed.isCompleted)
        withContext(Dispatchers.Main) {
            ports.timer().start(RestTimerState("", userId = fixture.owner, workoutId = session.id,
                templateId = requireNotNull(session.templateId), exerciseId = exercise.exerciseId,
                setId = completed.id, exerciseName = exercise.name, durationMs = 60_000,
                deadlineElapsedMs = 0, bootIdentity = null, startedAtEpochMs = 0))
            if (!cold) { ports.timer().extend(); ports.timer().extend() }
        }
        val rest = requireNotNull(ports.timer().state.value)
        assertEquals(session.id, rest.workoutId)
        assertEquals(completed.id, rest.setId)
        assertEquals(TimerStatus.RUNNING, rest.status)
        assertTrue(rest.deadlineElapsedMs > SystemClock.elapsedRealtime())
        check(preferences.edit().putString("timer_id", rest.timerId).putString("session_id", session.id)
            .putInt("prepared_pid", Process.myPid()).putLong("deadline", rest.deadlineElapsedMs)
            .putLong("generation", rest.generation).putString("scenario", name).commit())
        snapshot(name, "confirmed", rest)
        return rest
    }

    private suspend fun scenario(name: String, screenOff: Boolean = false, doze: Boolean = false,
        exact: Boolean = true, cancel: Boolean = false, receiverOnly: Boolean = false, warmOffline: Boolean = false) {
        fixture.guard()
        check(!offline()) { "Real login and receipt confirmation must happen online" }
        val runtime = ports.runtime()
        val originalFsi = shell("appops get ${context.packageName} USE_FULL_SCREEN_INTENT")
        var forcedIdle = false
        var armedForCleanup: RestTimerState? = null
        try {
            if (Build.VERSION.SDK_INT >= 33) shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
            assertEquals("Host configures exact access before instrumentation", exact, runtime.exactAlarmAvailable())
            if (Build.VERSION.SDK_INT >= 34) shell("appops set ${context.packageName} USE_FULL_SCREEN_INTENT deny")
            device.wakeUp()
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            assertTrue(device.wait(Until.hasObject(By.pkg(context.packageName).depth(0)), 10_000))
            val rest = arm(name)
            armedForCleanup = rest
            assertEquals(2L, rest.generation)
            assertEquals(if (exact) SchedulingMode.EXACT else SchedulingMode.BEST_EFFORT, rest.schedulingMode)
            if (warmOffline) fixture.await { offline() } // Host reacts to confirmed, never before SDK login.
            repeat(10_000) { assertEquals(rest.workoutId, ports.sessions().peek()?.id) }
            snapshot(name, "hot_peeks", rest, "peekCount=10000")
            if (!receiverOnly) {
                context.startForegroundService(Intent(context, WorkoutService::class.java).apply { action = WorkoutService.ACTION_START_WORKOUT })
                fixture.await { withContext(Dispatchers.Main) { runtime.foregroundServiceActive } }
            }
            snapshot(name, "armed", rest)
            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            assertTrue(device.wait(Until.hasObject(By.pkg("com.android.settings").depth(0)), 10_000))
            if (screenOff) device.sleep()
            if (doze) {
                shell("dumpsys battery unplug"); forcedIdle = true
                shell("dumpsys deviceidle force-idle")
                assertTrue(shell("dumpsys deviceidle get deep").contains("IDLE"))
            }
            if (cancel) withContext(Dispatchers.Main) { ports.timer().cancel("REGRESSION_CANCEL") }
            fixture.await(150_000) { SystemClock.elapsedRealtime() >= rest.deadlineElapsedMs - 2000 }
            snapshot(name, "before_deadline", rest)
            if (cancel) {
                fixture.await(15_000) { SystemClock.elapsedRealtime() >= rest.deadlineElapsedMs + 3000 }
                assertEquals(TimerStatus.CANCELLED, ports.timer().state.value?.status)
                assertTrue(manager.activeNotifications.none { it.id == NotificationChannels.REST_FINISHED_NOTIFICATION_ID })
            } else {
                fixture.await(120_000) { ports.timer().state.value?.status == TimerStatus.EXPIRED }
                fixture.await { manager.activeNotifications.any { it.id == NotificationChannels.REST_FINISHED_NOTIFICATION_ID } }
                assertEquals(rest.deadlineElapsedMs, ports.timer().state.value?.deadlineElapsedMs)
                if (exact && !doze) assertTrue("Exact commit lateness exceeded 5s", SystemClock.elapsedRealtime() - rest.deadlineElapsedMs <= 5000)
                if (!screenOff && !doze) assertTrue(device.hasObject(By.pkg("com.android.settings").depth(0)))
            }
            snapshot(name, "after_deadline", requireNotNull(ports.timer().state.value))
        } finally {
            if (forcedIdle) { shell("dumpsys deviceidle unforce"); shell("dumpsys battery reset") }
            device.wakeUp()
            withContext(Dispatchers.Main) { ports.timer().cancel("REGRESSION_CLEANUP", endWorkout = true) }
            context.stopService(Intent(context, WorkoutService::class.java))
            if (!offline()) armedForCleanup?.let { cleanupSession(it.workoutId) }
            // Offline server cleanup is an explicit host-invoked method AFTER restoring the network.
            if (Build.VERSION.SDK_INT >= 34) {
                val mode = Regex("USE_FULL_SCREEN_INTENT: (allow|deny|ignore|default)").find(originalFsi)?.groupValues?.get(1) ?: "default"
                shell("appops set ${context.packageName} USE_FULL_SCREEN_INTENT $mode")
            }
        }
    }

    @Test fun warmProductionRelease() = runBlocking { scenario(scenarioName) }
    @Test fun warmOfflineProductionRelease() = runBlocking { scenario(scenarioName, warmOffline = true) }
    @Test fun backgroundExactProductionRelease() = runBlocking { scenario("background-exact") }
    @Test fun screenOffExactProductionRelease() = runBlocking { scenario("screen-off", screenOff = true) }
    @Test fun forcedDozeExactProductionRelease() = runBlocking { scenario("doze", screenOff = true, doze = true) }
    @Test fun exactDeniedUsesInexactProductionRelease() = runBlocking { scenario("inexact", exact = false) }
    @Test fun cancelledRestNeverAlertsProductionRelease() = runBlocking { scenario("cancel", cancel = true) }
    @Test fun receiverWithoutServiceProductionRelease() = runBlocking { scenario("receiver-only", receiverOnly = true) }

    @Test fun prepareColdProcessFixture() = runBlocking<Unit> {
        fixture.guard()
        check(scenarioName == "cold-offline" || scenarioName == "cold-online")
        assertTrue(ports.runtime().exactAlarmAvailable())
        if (Build.VERSION.SDK_INT >= 33) shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        if (Build.VERSION.SDK_INT >= 34) shell("appops set ${context.packageName} USE_FULL_SCREEN_INTENT deny")
        val rest = arm(scenarioName, cold = true)
        if (scenarioName == "cold-offline") fixture.await { offline() }
        snapshot(scenarioName, "armed", rest)
        // Returning instrumentation would stop the package; host kills only this exact user PID.
        awaitCancellation()
    }

    private suspend fun restored(): RestTimerState {
        fixture.guard()
        fixture.await { ports.auth().getLocalUserId() == fixture.owner }
        withContext(Dispatchers.Main) { ports.timer().restore() }
        val rest = requireNotNull(ports.timer().state.value)
        assertEquals(preferences.getString("timer_id", null), rest.timerId)
        assertEquals(preferences.getString("session_id", null), rest.workoutId)
        assertEquals(preferences.getLong("deadline", -1), rest.deadlineElapsedMs)
        assertEquals(preferences.getLong("generation", -1), rest.generation)
        assertNotEquals(preferences.getInt("prepared_pid", -1), Process.myPid())
        return rest
    }

    @Test fun verifyColdOfflineProcessFixture() = runBlocking {
        check(offline())
        val rest = restored()
        fixture.await(90_000) { SystemClock.elapsedRealtime() >= rest.deadlineElapsedMs + 3000 }
        // Reconcile cannot turn an unverified cold-offline anchor into expiry or an alert.
        withContext(Dispatchers.Main) { ports.timer().reconcile("cold_offline_fixture") }
        val after = requireNotNull(ports.timer().state.value)
        assertEquals(TimerStatus.RUNNING, after.status)
        assertFalse(after.presentationHandled)
        assertEquals(rest.timerId, after.timerId)
        assertEquals(rest.deadlineElapsedMs, after.deadlineElapsedMs)
        assertTrue(manager.activeNotifications.none { it.id == NotificationChannels.REST_FINISHED_NOTIFICATION_ID })
        assertFalse(ports.sessions().state.value.loaded)
        snapshot(preferences.getString("scenario", null)!!, "offline_verified", after, "anchorRecoverable=true")
        // Deliberately retain the recoverable anchor. Host reconnects before the online phase.
    }

    @Test fun verifyColdOnlineProcessFixture() = runBlocking {
        check(!offline())
        val rest = restored()
        val session = requireNotNull(ports.sessions().reload(rest.workoutId))
        assertEquals(rest.workoutId, session.id)
        assertEquals(SessionStatus.ACTIVE, session.effectiveStatus)
        assertFalse(session.reviewRequired)
        snapshot(preferences.getString("scenario", null)!!, "session_verified", rest)
        try {
            withContext(Dispatchers.Main) { ports.timer().reconcile("cold_online_verified_fixture") }
            fixture.await(90_000) {
                withContext(Dispatchers.Main) { ports.timer().reconcile("cold_online_deadline_fixture") }
                ports.timer().state.value?.status == TimerStatus.EXPIRED
            }
            val after = requireNotNull(ports.timer().state.value)
            assertEquals(rest.deadlineElapsedMs, after.deadlineElapsedMs)
            assertTrue(after.presentationHandled)
            assertTrue(manager.activeNotifications.any { it.id == NotificationChannels.REST_FINISHED_NOTIFICATION_ID })
            snapshot(preferences.getString("scenario", null)!!, "online_verified", after)
        } finally {
            withContext(Dispatchers.Main) { ports.timer().cancel("COLD_TEST_CLEANUP", endWorkout = true) }
            context.stopService(Intent(context, WorkoutService::class.java))
            cleanupSession(rest.workoutId)
        }
    }

    private suspend fun cleanupSession(sessionId: String) {
        val session = ports.sessions().reload(sessionId)
        if (session?.effectiveStatus == SessionStatus.ACTIVE || session?.effectiveStatus == SessionStatus.PAUSED) {
            val receipt = ports.sessions().cancel(sessionId)
            assertEquals(SessionStatus.CANCELLED, receipt.status)
        }
        check(preferences.edit().clear().commit())
    }

    @Test fun cleanupProductionFixture() = runBlocking {
        fixture.guard()
        check(!offline()) { "Host must restore connectivity before cleanup" }
        fixture.await { ports.auth().getLocalUserId() == fixture.owner }
        preferences.getString("session_id", null)?.let { cleanupSession(it) }
        withContext(Dispatchers.Main) { ports.timer().cancel("HOST_FIXTURE_CLEANUP", endWorkout = true) }
        context.stopService(Intent(context, WorkoutService::class.java))
        ports.auth().logout()
        assertNull(ports.auth().getLocalUserId())
    }
}

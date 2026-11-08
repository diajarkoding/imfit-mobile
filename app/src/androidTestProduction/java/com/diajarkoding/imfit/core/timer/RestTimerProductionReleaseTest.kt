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
import com.diajarkoding.imfit.data.local.entity.ExerciseLogEntity
import com.diajarkoding.imfit.data.local.entity.WorkoutLogEntity
import com.diajarkoding.imfit.data.local.entity.WorkoutSetEntity
import com.diajarkoding.imfit.domain.model.*
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
        val user = requireNotNull(InstrumentationRegistry.getArguments().getString("androidUser"))
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

    private suspend fun requireIsolatedOfflineFixture() {
        assertFalse("Must test minified productionRelease, not debug", BuildConfig.DEBUG)
        val arguments = InstrumentationRegistry.getArguments()
        check(arguments.getString("allowSyntheticSession") == "true")
        val user = requireNotNull(arguments.getString("androidUser")).toInt()
        check(user != 0 && user != 10 && android.os.Process.myUid() / 100_000 == user) {
            "Production fixtures require an isolated Android user other than 0 or 10"
        }
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk_gphone")) { "Emulator only" }
        awaitCondition { context.getSystemService(ConnectivityManager::class.java).activeNetwork == null }
    }

    private fun syntheticSession() = UserSession(
        accessToken = "offline-synthetic-not-a-server-token",
        refreshToken = "offline-synthetic-not-a-refresh-token",
        expiresIn = 86_400,
        tokenType = "bearer",
        user = UserInfo(aud = "authenticated", id = OWNER, email = "rest-regression@example.invalid")
    )

    private fun snapshot(scenario: String, phase: String, state: RestTimerState) {
        Log.i("IMFITRestTest", "event=test_snapshot scenario=$scenario phase=$phase " +
            "restInstanceId=${RestTimerCorrelation.instanceId(state.timerId)} generation=${state.generation} " +
            "pid=${android.os.Process.myPid()} now=${SystemClock.elapsedRealtime()} deadlineElapsedMs=${state.deadlineElapsedMs}")
    }

    private suspend fun scenario(name: String, screenOff: Boolean = false, doze: Boolean = false,
        exact: Boolean = true, cancel: Boolean = false, receiverOnly: Boolean = false,
        brokenRendering: Boolean = false) {
        requireIsolatedOfflineFixture()
        val local = EntryPointAccessors.fromApplication(context, LocalSessionEntryPoint::class.java)
        val auth = local.supabase().auth
        withTimeout(10_000) { auth.awaitInitialization() }
        check(auth.currentUserOrNull()?.id.let { it == null || it == OWNER }) { "Refusing an existing account" }
        val dao = local.database().activeSessionDao()
        check(dao.getAnyActiveSession()?.userId.let { it == null || it == OWNER }) { "Refusing an existing workout" }
        auth.stopAutoRefreshForCurrentSession()
        auth.importSession(syntheticSession(), autoRefresh = false)
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

    @Test fun desugaredAuthSessionMatchesTargetClasspath() = runBlocking {
        requireIsolatedOfflineFixture()
        // Do not call java.time directly here: that would retain the missing L8 API in
        // test dex and hide whether app-owned UserSession dependencies can link.
        val session = syntheticSession()
        assertEquals(86_400L, session.expiresIn)
        assertEquals(OWNER, session.user?.id)
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
        requireIsolatedOfflineFixture()
        val local = EntryPointAccessors.fromApplication(context, LocalSessionEntryPoint::class.java)
        val auth = local.supabase().auth
        withTimeout(10_000) { auth.awaitInitialization() }
        check(auth.currentUserOrNull()?.id.let { it == null || it == OWNER })
        val dao = local.database().activeSessionDao()
        check(dao.getAnyActiveSession()?.userId.let { it == null || it == OWNER })
        auth.stopAutoRefreshForCurrentSession()
        auth.importSession(syntheticSession(), autoRefresh = false)
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
        requireIsolatedOfflineFixture()
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

    private val executionPrefs
        get() = context.getSharedPreferences("execution_stopwatch_regression", Context.MODE_PRIVATE)

    private fun currentBootIdentity(): Int =
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)

    private suspend fun executionLocal(): LocalSessionEntryPoint {
        requireIsolatedOfflineFixture()
        val local = EntryPointAccessors.fromApplication(context, LocalSessionEntryPoint::class.java)
        withTimeout(10_000) { local.supabase().auth.awaitInitialization() }
        check(local.supabase().auth.currentUserOrNull()?.id == OWNER)
        return local
    }

    private fun WorkoutSession.timedSet(): WorkoutSet =
        exerciseLogs.single { it.exercise.id == EXECUTION_DURATION }.sets.first()

    private fun executionSnapshot(phase: String, session: WorkoutSession) {
        val stopwatch = requireNotNull(session.timedSet().executionStopwatch)
        Log.i("IMFITExecutionTest", "event=execution_fixture phase=$phase sessionId=${session.id} " +
            "pid=${android.os.Process.myPid()} user=${android.os.Process.myUid() / 100_000} " +
            "boot=${currentBootIdentity()} now=${SystemClock.elapsedRealtime()} " +
            "elapsedMs=${stopwatch.elapsedMs} anchorElapsedMs=${stopwatch.anchorElapsedMs} " +
            "running=${stopwatch.isRunning} recoveryRequired=${stopwatch.recoveryRequired}")
    }

    private suspend fun assertExecutionFixture(local: LocalSessionEntryPoint): WorkoutSession {
        val session = requireNotNull(local.workouts().getActiveSession())
        assertEquals(executionPrefs.getString("session_id", null), session.id)
        assertNotNull(local.database().activeSessionDao().getSessionById(session.id))
        assertEquals(listOf(TrackingType.DURATION, TrackingType.DISTANCE_DURATION, TrackingType.WEIGHT_REPS),
            session.exerciseLogs.map { it.trackingType })
        val distance = session.exerciseLogs.single { it.exercise.id == EXECUTION_DISTANCE }.sets.single()
        assertEquals(45, distance.durationSeconds)
        assertEquals(750.0, distance.distanceMeters!!, 0.0)
        val legacy = session.exerciseLogs.single { it.exercise.id == EXECUTION_LEGACY }
        assertEquals("LEGACY_UNVERIFIED", legacy.metricQuality)
        assertEquals(20.0, legacy.sets.single().weightKg!!, 0.0)
        assertEquals(8, legacy.sets.single().reps)
        assertLegacyExecutionRowsUnchanged(local)
        return session
    }

    private suspend fun assertLegacyExecutionRowsUnchanged(local: LocalSessionEntryPoint) {
        val id = requireNotNull(executionPrefs.getString("legacy_id", null))
        val database = local.database()
        assertEquals(executionPrefs.getString("legacy_parent", null),
            requireNotNull(database.workoutLogDao().getWorkoutLogById(id)).toString())
        assertEquals(executionPrefs.getString("legacy_exercises", null),
            database.exerciseLogDao().getExerciseLogsByWorkoutLogId(id).toString())
        assertEquals(executionPrefs.getString("legacy_sets", null),
            database.workoutSetDao().getSetsByWorkoutLogId(id).toString())
    }

    @Test fun prepareExecutionStopwatchFixture() = runBlocking<Unit> {
        requireIsolatedOfflineFixture()
        val local = EntryPointAccessors.fromApplication(context, LocalSessionEntryPoint::class.java)
        val auth = local.supabase().auth
        withTimeout(10_000) { auth.awaitInitialization() }
        check(auth.currentUserOrNull()?.id.let { it == null || it == OWNER })
        check(local.database().activeSessionDao().getAnyActiveSession() == null) { "Refusing an existing workout" }
        check(local.database().workoutLogDao().getWorkoutLogsByUserList(OWNER).isEmpty())
        check(!executionPrefs.contains("session_id")) { "Clean the previous isolated fixture first" }
        auth.stopAutoRefreshForCurrentSession()
        auth.importSession(syntheticSession(), autoRefresh = false)
        val duration = Exercise(EXECUTION_DURATION, "Offline stopwatch", MuscleCategory.CORE, "",
            defaultTrackingType = TrackingType.DURATION, modality = ExerciseModality.ISOMETRIC,
            allowedTrackingTypes = listOf(TrackingType.DURATION))
        val distance = Exercise(EXECUTION_DISTANCE, "Offline distance", MuscleCategory.CARDIO, "",
            defaultTrackingType = TrackingType.DISTANCE_DURATION, modality = ExerciseModality.CARDIO,
            allowedTrackingTypes = listOf(TrackingType.DISTANCE_DURATION))
        val legacy = Exercise(EXECUTION_LEGACY, "Offline legacy", MuscleCategory.CHEST, "")
        val session = local.workouts().startWorkout(WorkoutTemplate(UUID.randomUUID().toString(), OWNER,
            "Offline execution recovery", listOf(
                TemplateExercise(duration, sets = 2, reps = null, targetDurationSeconds = 30),
                TemplateExercise(distance, sets = 1, reps = null, targetDurationSeconds = 45, targetDistanceMeters = 750),
                TemplateExercise(legacy, sets = 1, reps = 8)
            )))
        val legacyId = UUID.randomUUID().toString()
        check(executionPrefs.edit().putString("session_id", session.id).putString("legacy_id", legacyId).commit())
        val database = local.database()
        val parent = WorkoutLogEntity(legacyId, OWNER, null, "Untouched legacy fixture", 1, 1, 2, 70f,
            totalSets = 1, totalReps = 7, syncStatus = "SYNCED", dataQuality = "LEGACY_UNVERIFIED")
        val exerciseRow = ExerciseLogEntity(UUID.randomUUID().toString(), legacyId, EXECUTION_LEGACY,
            "Original legacy snapshot", MuscleCategory.CHEST.name, 0, 70f, 1, 7, syncStatus = "SYNCED")
        val setRow = WorkoutSetEntity(UUID.randomUUID().toString(), exerciseRow.id, legacyId,
            EXECUTION_LEGACY, 1, 10.0, 7, true, syncStatus = "SYNCED")
        database.workoutLogDao().insertWorkoutLog(parent)
        database.exerciseLogDao().insertExerciseLogs(listOf(exerciseRow))
        database.workoutSetDao().insertWorkoutSets(listOf(setRow))
        check(executionPrefs.edit().putString("legacy_parent", parent.toString())
            .putString("legacy_exercises", listOf(exerciseRow).toString())
            .putString("legacy_sets", listOf(setRow).toString()).commit())
        val boot = currentBootIdentity()
        val started = requireNotNull(local.workouts().mutateActiveSession(session.id) { current ->
            current.copy(exerciseLogs = current.exerciseLogs.map { log ->
                when (log.exercise.id) {
                    EXECUTION_DURATION -> log.copy(sets = log.sets.mapIndexed { index, set ->
                        if (index == 0) set.copy(durationSeconds = 0).toggleExecutionStopwatch(SystemClock.elapsedRealtime(), boot)
                        else set
                    })
                    EXECUTION_LEGACY -> log.copy(metricQuality = "LEGACY_UNVERIFIED",
                        sets = log.sets.map { it.copy(weightKg = 20.0, reps = 8, isCompleted = true) })
                    else -> log
                }
            })
        })
        awaitCondition { requireNotNull(started.timedSet().executionStopwatch)
            .elapsedMillis(SystemClock.elapsedRealtime(), boot) >= 1_100 }
        val checkpointAt = SystemClock.elapsedRealtime()
        val checkpointed = requireNotNull(local.workouts().mutateActiveSession(session.id) { current ->
            current.copy(exerciseLogs = current.exerciseLogs.map { log ->
                if (log.exercise.id != EXECUTION_DURATION) log else log.copy(sets = log.sets.mapIndexed { index, set ->
                    if (index == 0) set.checkpointExecutionStopwatch(checkpointAt, boot) else set
                })
            })
        })
        val stopwatch = requireNotNull(checkpointed.timedSet().executionStopwatch)
        check(executionPrefs.edit().putInt("previous_pid", android.os.Process.myPid()).putInt("boot", boot)
            .putLong("checkpoint_elapsed", stopwatch.elapsedMs).putLong("checkpoint_anchor", checkpointAt).commit())
        assertExecutionFixture(local)
        executionSnapshot("running_prepared", checkpointed)
        awaitCancellation()
    }

    @Test fun verifyRunningExecutionStopwatchAndPauseFixture() = runBlocking<Unit> {
        val local = executionLocal()
        assertNotEquals(executionPrefs.getInt("previous_pid", -1), android.os.Process.myPid())
        val session = assertExecutionFixture(local)
        val stopwatch = requireNotNull(session.timedSet().executionStopwatch)
        val boot = currentBootIdentity()
        assertEquals(executionPrefs.getInt("boot", -1), boot)
        assertTrue(stopwatch.isRunning)
        assertEquals(executionPrefs.getLong("checkpoint_anchor", -1), stopwatch.anchorElapsedMs)
        assertEquals(executionPrefs.getLong("checkpoint_elapsed", -1), stopwatch.elapsedMs)
        assertTrue("Same-boot process death must retain elapsed time after the durable checkpoint",
            stopwatch.elapsedMillis(SystemClock.elapsedRealtime(), boot) > stopwatch.elapsedMs)
        val paused = requireNotNull(local.workouts().mutateActiveSession(session.id) { current ->
            current.copy(exerciseLogs = current.exerciseLogs.map { log ->
                if (log.exercise.id != EXECUTION_DURATION) log else log.copy(sets = log.sets.mapIndexed { index, set ->
                    if (index == 0) set.freezeExecutionStopwatch(SystemClock.elapsedRealtime(), boot)
                    else set.copy(durationSeconds = 90, executionStopwatch = null)
                })
            })
        })
        val pausedClock = requireNotNull(paused.timedSet().executionStopwatch)
        assertFalse(pausedClock.isRunning)
        check(executionPrefs.edit().putInt("previous_pid", android.os.Process.myPid())
            .putLong("paused_elapsed", pausedClock.elapsedMs).commit())
        executionSnapshot("paused_prepared", paused)
        awaitCancellation()
    }

    private suspend fun assertPausedExecutionFixture(local: LocalSessionEntryPoint): WorkoutSession {
        assertNotEquals(executionPrefs.getInt("previous_pid", -1), android.os.Process.myPid())
        val session = assertExecutionFixture(local)
        val stopwatch = requireNotNull(session.timedSet().executionStopwatch)
        assertFalse(stopwatch.isRunning)
        assertEquals(executionPrefs.getLong("paused_elapsed", -1), stopwatch.elapsedMs)
        assertEquals(stopwatch.elapsedMs, stopwatch.elapsedMillis(SystemClock.elapsedRealtime(), currentBootIdentity()))
        val manual = session.exerciseLogs.first().sets[1]
        assertEquals(90, manual.durationSeconds)
        assertNull(manual.executionStopwatch)
        return session
    }

    @Test fun verifyPausedExecutionStopwatchAndFinishFixture() = runBlocking {
        val local = executionLocal()
        finishExecutionFixture(local, assertPausedExecutionFixture(local))
    }

    @Test fun verifyPausedExecutionStopwatchAndPrepareRebootFixture() = runBlocking<Unit> {
        val local = executionLocal()
        val session = assertPausedExecutionFixture(local)
        val boot = currentBootIdentity()
        val resumed = requireNotNull(local.workouts().mutateActiveSession(session.id) { current ->
            current.copy(exerciseLogs = current.exerciseLogs.map { log ->
                if (log.exercise.id != EXECUTION_DURATION) log else log.copy(sets = log.sets.mapIndexed { index, set ->
                    if (index == 0) set.toggleExecutionStopwatch(SystemClock.elapsedRealtime(), boot) else set
                })
            })
        })
        val resumedClock = requireNotNull(resumed.timedSet().executionStopwatch)
        awaitCondition { resumedClock.elapsedMillis(SystemClock.elapsedRealtime(), boot) >= resumedClock.elapsedMs + 1_100 }
        val checkpointed = requireNotNull(local.workouts().mutateActiveSession(session.id) { current ->
            current.copy(exerciseLogs = current.exerciseLogs.map { log ->
                if (log.exercise.id != EXECUTION_DURATION) log else log.copy(sets = log.sets.mapIndexed { index, set ->
                    if (index == 0) set.checkpointExecutionStopwatch(SystemClock.elapsedRealtime(), boot) else set
                })
            })
        })
        check(executionPrefs.edit().putInt("boot", boot).putLong("reboot_elapsed",
            requireNotNull(checkpointed.timedSet().executionStopwatch).elapsedMs).commit())
        executionSnapshot("reboot_prepared", checkpointed)
        awaitCancellation()
    }

    @Test fun verifyRebootExecutionStopwatchAndFinishFixture() = runBlocking {
        val local = executionLocal()
        val session = assertExecutionFixture(local)
        val boot = currentBootIdentity()
        assertNotEquals("This gate requires an actual reboot, not a new process", executionPrefs.getInt("boot", -1), boot)
        val durable = executionPrefs.getLong("reboot_elapsed", -1)
        val rawClock = requireNotNull(session.timedSet().executionStopwatch)
        assertEquals(durable, rawClock.elapsedMs)
        assertEquals(durable, rawClock.elapsedMillis(SystemClock.elapsedRealtime(), boot))
        val recovered = requireNotNull(local.workouts().mutateActiveSession(session.id) { current ->
            current.copy(exerciseLogs = current.exerciseLogs.map { log ->
                if (log.exercise.id != EXECUTION_DURATION) log else log.copy(sets = log.sets.mapIndexed { index, set ->
                    if (index == 0) set.checkpointExecutionStopwatch(SystemClock.elapsedRealtime(), boot) else set
                })
            })
        })
        val recoveredClock = requireNotNull(recovered.timedSet().executionStopwatch)
        assertFalse(recoveredClock.isRunning)
        assertTrue(recoveredClock.recoveryRequired)
        assertEquals(durable, recoveredClock.elapsedMs)
        assertEquals((durable / 1000).toInt(), recovered.timedSet().durationSeconds)
        assertEquals(90, recovered.exerciseLogs.first().sets[1].durationSeconds)
        executionSnapshot("reboot_verified", recovered)
        finishExecutionFixture(local, recovered)
    }

    private suspend fun finishExecutionFixture(local: LocalSessionEntryPoint, session: WorkoutSession) {
        val completed = requireNotNull(local.workouts().mutateActiveSession(session.id) { current ->
            current.copy(exerciseLogs = current.exerciseLogs.map { log ->
                log.copy(sets = log.sets.map { it.copy(isCompleted = true) })
            })
        })
        val first = requireNotNull(local.workouts().finishWorkout()) // Local Room commit; never calls SyncRunner/RPC.
        assertEquals(session.id, first.id)
        val database = local.database()
        val beforeParent = database.workoutLogDao().getWorkoutLogById(session.id)
        val beforeExercises = database.exerciseLogDao().getExerciseLogsByWorkoutLogId(session.id)
        val beforeSets = database.workoutSetDao().getSetsByWorkoutLogId(session.id)
        assertNull(local.workouts().finishWorkout())
        assertEquals(beforeParent, database.workoutLogDao().getWorkoutLogById(session.id))
        assertEquals(beforeExercises, database.exerciseLogDao().getExerciseLogsByWorkoutLogId(session.id))
        assertEquals(beforeSets, database.workoutSetDao().getSetsByWorkoutLogId(session.id))
        assertEquals(setOf(session.id, executionPrefs.getString("legacy_id", null)),
            database.workoutLogDao().getWorkoutLogsByUserList(OWNER).map { it.id }.toSet())
        assertNull(database.activeSessionDao().getSessionById(session.id))
        val history = requireNotNull(local.workouts().getWorkoutLogById(session.id))
        assertEquals(completed.exerciseLogs.map { it.trackingType }, history.exerciseLogs.map { it.trackingType })
        assertEquals(completed.exerciseLogs.map { log -> log.sets.map { it.measurement } },
            history.exerciseLogs.map { log -> log.sets.map { it.measurement } })
        assertEquals("LEGACY_UNVERIFIED", history.dataQuality)
        assertEquals("LEGACY_UNVERIFIED", history.exerciseLogs.last().metricQuality)
        assertLegacyExecutionRowsUnchanged(local)
        Log.i("IMFITExecutionTest", "event=execution_fixture phase=finished sessionId=${session.id} " +
            "pid=${android.os.Process.myPid()} user=${android.os.Process.myUid() / 100_000} " +
            "historyRows=1 exerciseRows=${beforeExercises.size} setRows=${beforeSets.size}")
    }

    @Test fun cleanExecutionStopwatchFixture() = runBlocking {
        requireIsolatedOfflineFixture()
        if (!executionPrefs.contains("session_id")) return@runBlocking
        val local = executionLocal()
        val sessionId = requireNotNull(executionPrefs.getString("session_id", null))
        val current = local.database().activeSessionDao().getAnyActiveSession()
        check(current == null || current.id == sessionId && current.userId == OWNER)
        if (current != null) local.workouts().cancelWorkout()
        for (id in listOfNotNull(sessionId, executionPrefs.getString("legacy_id", null))) {
            val row = local.database().workoutLogDao().getWorkoutLogById(id) ?: continue
            check(row.userId == OWNER)
            local.database().workoutSetDao().deleteSetsByWorkoutLog(id)
            local.database().exerciseLogDao().deleteExerciseLogsByWorkout(id)
            local.database().workoutLogDao().deleteWorkoutLog(row)
        }
        local.supabase().auth.clearSession()
        check(executionPrefs.edit().clear().commit())
    }

    companion object {
        private const val OWNER = "00000000-0000-4000-8000-000000000034"
        private const val EXECUTION_DURATION = "phase5-offline-duration"
        private const val EXECUTION_DISTANCE = "phase5-offline-distance"
        private const val EXECUTION_LEGACY = "phase5-offline-legacy"
    }
}

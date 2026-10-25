package com.diajarkoding.imfit.core.timer

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diajarkoding.imfit.MainActivity
import com.diajarkoding.imfit.core.notification.NotificationChannels
import com.diajarkoding.imfit.core.service.WorkoutService
import com.diajarkoding.imfit.di.RestTimerEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import android.os.Build
import org.junit.runner.RunWith

/** Real AlarmManager/service/notification integration; Settings substitutes another app,
 * not the required physical Instagram acceptance test. */
@RunWith(AndroidJUnit4::class)
class RestTimerBackgroundTest {
    private suspend fun awaitCondition(timeoutMs: Long = 10_000, condition: suspend () -> Boolean) {
        withTimeout(timeoutMs) { while (!condition()) delay(50) }
    }

    /** A negative assertion needs an observation interval, not a sleep followed by one snapshot. */
    private suspend fun observeFor(durationMs: Long, assertion: suspend () -> Unit) {
        val until = android.os.SystemClock.elapsedRealtime() + durationMs
        while (android.os.SystemClock.elapsedRealtime() < until) { assertion(); delay(50) }
        assertion()
    }
    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        }
    }

    private fun configureCapabilities(context: Context, fullScreen: String = "allow") {
        if (Build.VERSION.SDK_INT >= 33) shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        if (Build.VERSION.SDK_INT >= 31) shell("appops set ${context.packageName} SCHEDULE_EXACT_ALARM allow")
        if (Build.VERSION.SDK_INT >= 34) shell("appops set ${context.packageName} USE_FULL_SCREEN_INTENT $fullScreen")
    }

    private suspend fun awaitPlayback(runtime: RestTimerAndroidRuntime) {
        withTimeout(5000) {
            while (!withContext(Dispatchers.Main) { runtime.isAlertPlaying() }) delay(50)
        }
    }

    @Test fun deniedFsiFallsBackAndBlockedNotificationsStaySilentWithoutReplay() = runBlocking {
        assumeTrue("Channel removal needs API 26+", Build.VERSION.SDK_INT >= 26)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        configureCapabilities(context, "deny")
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val ports = EntryPointAccessors.fromApplication(context, RestTimerEntryPoint::class.java)
        val timer = ports.timer()
        val repository = ports.workouts()
        val user = requireNotNull(ports.auth().getLocalUserId())
        val session = repository.startWorkout(repository.getTemplates(user).first())
        val exercise = session.exerciseLogs.first().exercise
        val manager = context.getSystemService(NotificationManager::class.java)
        try {
            ContextCompat.startForegroundService(context, Intent(context, WorkoutService::class.java).apply {
                action = WorkoutService.ACTION_START_WORKOUT
            })
            awaitCondition { withContext(Dispatchers.Main) { ports.runtime().foregroundServiceActive } }
            if (android.os.Build.VERSION.SDK_INT >= 34) assertFalse(manager.canUseFullScreenIntent())
            withContext(Dispatchers.Main) {
                timer.start(RestTimerState("", userId = user, workoutId = session.id, templateId = session.templateId,
                    exerciseId = exercise.id, setId = "fsi-denied", exerciseName = exercise.name,
                    durationMs = 1000, deadlineElapsedMs = 0, bootIdentity = null, startedAtEpochMs = 0))
            }
            withTimeout(7000) {
                while (manager.activeNotifications.none { it.id == NotificationChannels.REST_FINISHED_NOTIFICATION_ID }) delay(50)
            }
            assertEquals(TimerStatus.EXPIRED, timer.state.value?.status)
            awaitPlayback(ports.runtime())
            if (Build.VERSION.SDK_INT >= 34) assertNull(manager.activeNotifications.first {
                it.id == NotificationChannels.REST_FINISHED_NOTIFICATION_ID
            }.notification.fullScreenIntent)
            // Removing the channel is a real capability loss without killing the test process
            // (pm revoke POST_NOTIFICATIONS may kill the app and its instrumentation).
            manager.deleteNotificationChannel(NotificationChannels.REST_FINISHED_CHANNEL_ID)
            assertNull(manager.getNotificationChannel(NotificationChannels.REST_FINISHED_CHANNEL_ID))
            withContext(Dispatchers.Main) { timer.reconcile(); ports.runtime().monitorPlayback() }
            withTimeout(5000) { while (timer.state.value?.audioSuppressed != true) delay(50) }
            assertEquals(TimerStatus.EXPIRED, timer.state.value?.status)
            withContext(Dispatchers.Main) { assertFalse(ports.runtime().isAlertPlaying()) }
            NotificationChannels.createNotificationChannels(context)
            withContext(Dispatchers.Main) { timer.capabilitiesChanged() }
            assertEquals(TimerStatus.EXPIRED, timer.state.value?.status)
            withContext(Dispatchers.Main) { assertFalse(ports.runtime().isAlertPlaying()) }
        } finally {
            NotificationChannels.createNotificationChannels(context)
            if (Build.VERSION.SDK_INT >= 34) shell("appops set ${context.packageName} USE_FULL_SCREEN_INTENT allow")
            withContext(Dispatchers.Main) { timer.cancel("CAPABILITY_TEST_FINISHED") }
            repository.cancelWorkout()
            context.stopService(Intent(context, WorkoutService::class.java))
        }
    }

    @Test fun repeatedTwoMinuteRestsWithScreenOffKeepDeadlineAndStopCorrectly() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        configureCapabilities(context)
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val ports = EntryPointAccessors.fromApplication(context, RestTimerEntryPoint::class.java)
        val timer = ports.timer()
        val repository = ports.workouts()
        val user = requireNotNull(ports.auth().getLocalUserId())
        val session = repository.startWorkout(repository.getTemplates(user).first())
        val exercise = session.exerciseLogs.first().exercise
        val manager = context.getSystemService(NotificationManager::class.java)
        try {
            ContextCompat.startForegroundService(context, Intent(context, WorkoutService::class.java).apply {
                action = WorkoutService.ACTION_START_WORKOUT
                putExtra(WorkoutService.EXTRA_WORKOUT_NAME, session.templateName)
                putExtra(WorkoutService.EXTRA_TEMPLATE_ID, session.templateId)
            })
            awaitCondition { withContext(Dispatchers.Main) { ports.runtime().foregroundServiceActive } }
            repeat(2) { index ->
                withContext(Dispatchers.Main) {
                    timer.start(RestTimerState("", userId = user, workoutId = session.id, templateId = session.templateId,
                        exerciseId = exercise.id, setId = "screen-off-$index", exerciseName = exercise.name,
                        durationMs = 120_000, deadlineElapsedMs = 0, bootIdentity = null, startedAtEpochMs = 0))
                }
                shell("input keyevent 223") // KEYCODE_SLEEP; no forced Doze or battery exemption.
                val started = android.os.SystemClock.elapsedRealtime()
                withTimeout(135_000) { while (timer.state.value?.status != TimerStatus.EXPIRED) delay(200) }
                val latency = android.os.SystemClock.elapsedRealtime() - started
                assertTrue("Screen-off expiry took ${latency}ms", latency in 118_000..130_000)
                withTimeout(5000) {
                    while (manager.activeNotifications.none { it.id == NotificationChannels.REST_FINISHED_NOTIFICATION_ID }) delay(50)
                }
                awaitPlayback(ports.runtime())
                manager.activeNotifications.first { it.id == NotificationChannels.REST_FINISHED_NOTIFICATION_ID }
                    .notification.actions.first().actionIntent.send()
                withTimeout(5000) { while (timer.state.value?.status != TimerStatus.ACKNOWLEDGED) delay(50) }
                withContext(Dispatchers.Main) { assertFalse(ports.runtime().isAlertPlaying()) }
                assertNotNull(repository.getActiveSession())
            }
        } finally {
            shell("input keyevent 224")
            withContext(Dispatchers.Main) { timer.cancel("SCREEN_OFF_TEST_FINISHED") }
            repository.cancelWorkout()
            context.stopService(Intent(context, WorkoutService::class.java))
        }
    }

    @Test fun twoMinuteRestInAnotherAppDismissAndStopDoNotReplay() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Connected tests reinstall the APK; configure capability on that installed package.
        configureCapabilities(context)
        val ports = EntryPointAccessors.fromApplication(context, RestTimerEntryPoint::class.java)
        val timer = ports.timer()
        val repository = ports.workouts()
        val userId = requireNotNull(ports.auth().getLocalUserId())
        val template = repository.getTemplates(userId).first()
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val session = repository.startWorkout(template)
        val exercise = session.exerciseLogs.first().exercise
        val manager = context.getSystemService(NotificationManager::class.java)
        try {
            ContextCompat.startForegroundService(context, Intent(context, WorkoutService::class.java).apply {
                action = WorkoutService.ACTION_START_WORKOUT
                putExtra(WorkoutService.EXTRA_WORKOUT_NAME, session.templateName)
                putExtra(WorkoutService.EXTRA_TEMPLATE_ID, session.templateId)
            })
            awaitCondition { withContext(Dispatchers.Main) { ports.runtime().foregroundServiceActive } }
            withContext(Dispatchers.Main) {
                timer.start(RestTimerState("", userId = userId, workoutId = session.id, templateId = session.templateId,
                    exerciseId = exercise.id, setId = "integration-set-1", exerciseName = exercise.name,
                    durationMs = 120_000, deadlineElapsedMs = 0, bootIdentity = null, startedAtEpochMs = 0))
            }
            assertEquals(TimerStatus.RUNNING, timer.state.value?.status)
            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val restDeadline = requireNotNull(timer.state.value).deadlineElapsedMs
            awaitCondition(95_000) { android.os.SystemClock.elapsedRealtime() >= restDeadline - 30_000 }
            val remaining = requireNotNull(timer.state.value).remainingSeconds(android.os.SystemClock.elapsedRealtime())
            assertTrue("At 90s should have about 30s remaining, got $remaining", remaining in 28..31)
            withTimeout(45_000) {
                while (timer.state.value?.status != TimerStatus.EXPIRED) delay(200)
            }
            withTimeout(5000) {
                while (manager.activeNotifications.none { it.id == NotificationChannels.REST_FINISHED_NOTIFICATION_ID }) delay(50)
            }
            awaitPlayback(ports.runtime())
            val finished = manager.activeNotifications.first { it.id == NotificationChannels.REST_FINISHED_NOTIFICATION_ID }
            if (ports.runtime().fullScreenIntentAvailable()) assertNotNull(finished.notification.fullScreenIntent)
            else assertNull(finished.notification.fullScreenIntent)
            assertFalse(finished.notification.extras.getBoolean(android.app.Notification.EXTRA_SHOW_CHRONOMETER))
            assertEquals(timer.state.value!!.timerId, finished.notification.extras.getString(
                com.diajarkoding.imfit.core.notification.WorkoutNotificationManager.EXTRA_REST_TIMER_ID))
            withTimeout(5000) {
                while (manager.activeNotifications.none {
                    it.id == NotificationChannels.WORKOUT_NOTIFICATION_ID &&
                        !it.notification.extras.getBoolean(android.app.Notification.EXTRA_SHOW_CHRONOMETER)
                }) delay(50)
            }
            assertEquals(0, finished.notification.flags and android.app.Notification.FLAG_ONGOING_EVENT)
            if (Build.VERSION.SDK_INT >= 26) assertEquals(0L, finished.notification.timeoutAfter)
            assertNotEquals(finished.notification.deleteIntent, finished.notification.actions.first().actionIntent)
            assertFalse("Expected controlled audio instead of silent recovery", timer.state.value!!.audioSuppressed)
            withContext(Dispatchers.Main) { assertTrue(ports.runtime().isAlertPlaying()) }
            // Verify card and playback survive >60s; visible heads-up persistence still needs OEM testing.
            observeFor(65_000) {
                assertEquals(TimerStatus.EXPIRED, timer.state.value?.status)
                assertTrue(manager.activeNotifications.any { it.id == NotificationChannels.REST_FINISHED_NOTIFICATION_ID })
            }
            assertTrue(manager.activeNotifications.any { it.id == NotificationChannels.REST_FINISHED_NOTIFICATION_ID })
            withContext(Dispatchers.Main) { assertTrue(ports.runtime().isAlertPlaying()) }
            val device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            device.openNotification()
            assertTrue(device.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.text(exercise.name)), 5000))
            device.findObject(androidx.test.uiautomator.By.text(exercise.name)).click()
            awaitCondition { InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow?.packageName == context.packageName }
            withTimeout(5000) { while (timer.state.value?.status != TimerStatus.ACKNOWLEDGED) delay(50) }
            observeFor(1500) {
                assertFalse(manager.activeNotifications.any { it.id == NotificationChannels.REST_FINISHED_NOTIFICATION_ID })
            }
            assertNotNull(repository.getActiveSession())
            withContext(Dispatchers.Main) { assertFalse(ports.runtime().isAlertPlaying()) }

            withContext(Dispatchers.Main) {
                timer.start(timer.state.value!!.copy(timerId = "", setId = "integration-set-2", durationMs = 2000,
                    status = TimerStatus.RUNNING, audioSuppressed = false))
            }
            withTimeout(7000) { while (timer.state.value?.status != TimerStatus.EXPIRED) delay(100) }
            withTimeout(5000) {
                while (manager.activeNotifications.none { it.id == NotificationChannels.REST_FINISHED_NOTIFICATION_ID }) delay(50)
            }
            val nextNotification = manager.activeNotifications.first { it.id == NotificationChannels.REST_FINISHED_NOTIFICATION_ID }.notification
            if (finished.notification.fullScreenIntent != null && nextNotification.fullScreenIntent != null)
                assertNotEquals(finished.notification.fullScreenIntent, nextNotification.fullScreenIntent)
            assertNotEquals(finished.notification.contentIntent, nextNotification.contentIntent)
            assertNotEquals(finished.notification.actions.first().actionIntent, nextNotification.actions.first().actionIntent)
            assertNotEquals(finished.notification.deleteIntent, nextNotification.deleteIntent)
            finished.notification.deleteIntent.send()
            finished.notification.actions.first().actionIntent.send()
            observeFor(500) { assertEquals(TimerStatus.EXPIRED, timer.state.value?.status) }
            nextNotification.actions.first().actionIntent.send()
            withTimeout(5000) { while (timer.state.value?.status != TimerStatus.ACKNOWLEDGED) delay(50) }
            observeFor(1500) {
                assertFalse(manager.activeNotifications.any { it.id == NotificationChannels.REST_FINISHED_NOTIFICATION_ID })
            }
            withContext(Dispatchers.Main) { assertFalse(ports.runtime().isAlertPlaying()) }
        } finally {
            withContext(Dispatchers.Main) { timer.cancel("TEST_FINISHED") }
            repository.cancelWorkout()
            context.stopService(Intent(context, WorkoutService::class.java))
        }
    }
}

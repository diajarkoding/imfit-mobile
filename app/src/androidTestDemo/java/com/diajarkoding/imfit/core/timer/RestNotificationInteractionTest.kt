package com.diajarkoding.imfit.core.timer

import android.app.NotificationManager
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.diajarkoding.imfit.MainActivity
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.theme.LocaleManager
import com.diajarkoding.imfit.core.notification.NotificationChannels
import com.diajarkoding.imfit.core.service.WorkoutService
import com.diajarkoding.imfit.di.RestTimerEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class RestNotificationInteractionTest {
    private suspend fun await(label: String = "condition", test: suspend () -> Boolean) {
        try { withTimeout(12_000) { while (!test()) delay(50) } }
        catch (e: TimeoutCancellationException) { throw AssertionError("Timed out: $label", e) }
    }

    @Test fun bodyStopsAlarmAndOpensSameSessionWhileStopStaysInBackground() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(context.packageName.endsWith(".modaldiagnostic"))
        val ports = EntryPointAccessors.fromApplication(context.applicationContext, RestTimerEntryPoint::class.java)
        val user = requireNotNull(ports.auth().getLocalUserId())
        val reads = ports.reads()
        val sessions = ports.sessions()
        val template = reads.read(com.diajarkoding.imfit.data.remote.online.OnlineRpc.templates(limit = 20)).data.first { it.exerciseCount > 0 }
        val session = sessions.start(template.id, template.revision)
        val timer = ports.timer()
        val manager = context.getSystemService(NotificationManager::class.java)
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        fun resumedActivity() = device.executeShellCommand("dumpsys activity activities").lineSequence()
            .firstOrNull { it.contains("topResumedActivity=") }?.substringAfter(" u0 ")?.substringBefore(" t")
        try {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            assertTrue(device.wait(Until.hasObject(By.pkg(context.packageName).depth(0)), 10_000))
            ContextCompat.startForegroundService(context, Intent(context, WorkoutService::class.java).apply {
                action = WorkoutService.ACTION_START_WORKOUT
                putExtra(WorkoutService.EXTRA_TEMPLATE_ID, template.id)
                putExtra(WorkoutService.EXTRA_SESSION_ID, session.id)
                putExtra(WorkoutService.EXTRA_WORKOUT_NAME, template.name)
            })
            await { withContext(Dispatchers.Main) { ports.runtime().foregroundServiceActive } }
            for (body in listOf(false, true)) {
                withContext(Dispatchers.Main) {
                    timer.start(RestTimerState("", userId = user, workoutId = session.id, templateId = template.id,
                        exerciseId = session.exercises.first().exerciseId, setId = session.exercises.first().sets.first().id, exerciseName = "Synthetic rest",
                        durationMs = 1000, deadlineElapsedMs = 0, bootIdentity = null, startedAtEpochMs = 0))
                }
                device.pressHome()
                await("expired notification body=$body") { timer.state.value?.status == TimerStatus.EXPIRED && manager.activeNotifications.any {
                    it.id == NotificationChannels.REST_FINISHED_NOTIFICATION_ID } }
                await("playback body=$body") { withContext(Dispatchers.Main) { ports.runtime().isAlertPlaying() } }
                val backgroundActivity = resumedActivity()
                val notification = manager.activeNotifications.first { it.id == NotificationChannels.REST_FINISHED_NOTIFICATION_ID }.notification
                if (body) {
                    // A real SystemUI tap grants background-activity launch privileges. Sending an
                    // activity PendingIntent from a background test process is intentionally blocked on API 34+.
                    device.openNotification()
                    assertTrue(device.wait(Until.hasObject(By.text("Synthetic rest")), 5000))
                    device.findObject(By.text("Synthetic rest")).click()
                } else notification.actions.first().actionIntent.send()
                await("acknowledged body=$body") { timer.state.value?.status == TimerStatus.ACKNOWLEDGED }
                await("effects stopped body=$body") { !withContext(Dispatchers.Main) { ports.runtime().isAlertPlaying() } && manager.activeNotifications.none {
                    it.id == NotificationChannels.REST_FINISHED_NOTIFICATION_ID } }
                assertEquals(session.id, sessions.peek()?.id)
                if (body) {
                    val label = LocaleManager.getUpdatedContext(context).getString(R.string.active_session_sets_completed)
                    val visible = device.wait(Until.hasObject(By.text(label)), 12_000)
                    if (!visible) device.dumpWindowHierarchy(java.io.File(context.filesDir, "notification-navigation.xml"))
                    assertTrue("Active session metric '$label' missing; top=${resumedActivity()}", visible)
                } else {
                    assertNotNull(backgroundActivity)
                    assertFalse(backgroundActivity!!.startsWith(context.packageName))
                    assertEquals(backgroundActivity, resumedActivity())
                }
            }
        } finally {
            withContext(Dispatchers.Main) { timer.cancel("INTERACTION_TEST_FINISHED") }
            sessions.cancel(session.id)
            context.stopService(Intent(context, WorkoutService::class.java))
        }
    }
}

package com.diajarkoding.imfit.core.timer

import android.app.NotificationManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.core.notification.NotificationChannels
import com.diajarkoding.imfit.core.notification.WorkoutNotificationManager
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Host prepares/restores system night mode. No app preferences or alarm UI are changed. */
class RestNotificationThemeTest {
    @Test fun restNotificationUsesNativeSystemDecorationAndReadableDefaultText() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(context.packageName.endsWith(".modaldiagnostic"))
        val manager = context.getSystemService(NotificationManager::class.java)
        val notifications = WorkoutNotificationManager(context)
        val device = UiDevice.getInstance(instrumentation)
        try {
            assertTrue(notifications.showRestFinished(RestTimerState("theme-fixture", userId = "fixture", workoutId = "fixture",
                templateId = "fixture", exerciseId = "fixture", setId = "fixture", exerciseName = "Synthetic theme check",
                durationMs = 1000, deadlineElapsedMs = 0, bootIdentity = null, startedAtEpochMs = 0,
                status = TimerStatus.EXPIRED), false))
            // NotificationManager posting is asynchronous; wait for the actual SystemUI row.
            device.openNotification()
            assertTrue(device.wait(Until.hasObject(By.text("Synthetic theme check")), 5000))
            val posted = manager.activeNotifications.first { it.id == NotificationChannels.REST_FINISHED_NOTIFICATION_ID }.notification
            assertNull(posted.contentView)
            assertNull(posted.bigContentView)
            assertNull(posted.headsUpContentView)
            val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            val node = requireNotNull(device.findObject(By.text("Synthetic theme check")))
            val bounds = node.visibleBounds
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            val sample = bitmap.getPixel((bounds.left - 8).coerceAtLeast(0), bounds.centerY())
            val brightness = (Color.red(sample) + Color.green(sample) + Color.blue(sample)) / 3
            if (dark) assertTrue("Dark native card sample=$brightness", brightness < 100)
            else assertTrue("Light native card sample=$brightness", brightness > 180)
            val dir = File(context.filesDir, "notification-theme-evidence").apply { mkdirs() }
            // Only the fixture text area: do not retain other apps' notification contents.
            val cropTop = (bounds.top - 96).coerceAtLeast(0)
            val cropped = Bitmap.createBitmap(bitmap, (bounds.left - 16).coerceAtLeast(0), cropTop,
                bounds.width() + 16, (bounds.bottom + 96).coerceAtMost(bitmap.height) - cropTop)
            File(dir, "system-${if (dark) "dark" else "light"}.png").outputStream().use { cropped.compress(Bitmap.CompressFormat.PNG, 100, it) }
            File(dir, "system-${if (dark) "dark" else "light"}.txt").writeText("sample=$sample bounds=$bounds native=true\n")
            bitmap.recycle()
            cropped.recycle()
        } finally { notifications.dismissRestFinished(); device.pressBack() }
    }
}

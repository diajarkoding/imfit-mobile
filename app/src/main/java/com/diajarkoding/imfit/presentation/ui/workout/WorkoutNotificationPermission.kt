package com.diajarkoding.imfit.presentation.ui.workout

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

internal const val WORKOUT_NOTIFICATION_PREFERENCES = "workout_notification_permission"
internal const val WORKOUT_NOTIFICATION_ASKED = "asked_before_session"

internal fun shouldRequestWorkoutNotifications(api: Int, granted: Boolean, asked: Boolean, resuming: Boolean): Boolean =
    api >= 33 && !granted && !asked && !resuming

internal data class WorkoutStartRequest(val start: () -> Unit, val pending: Boolean)

/** Consent is requested before navigation/session creation, never a prerequisite for exercise. */
@Composable
internal fun rememberWorkoutStartRequest(workoutId: String, resuming: Boolean, onStart: () -> Unit): WorkoutStartRequest {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences(WORKOUT_NOTIFICATION_PREFERENCES, Context.MODE_PRIVATE) }
    val activity = remember(context) {
        generateSequence(context) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()
    }
    val latestStart by rememberUpdatedState(onStart)
    var permissionPending by rememberSaveable(workoutId) { mutableStateOf(false) }
    var navigationIssued by remember(workoutId) { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (!permissionPending) navigationIssued = false
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Denial/dismissal does not block the already requested workout. Ignore duplicate results.
        if (permissionPending && !navigationIssued) {
            permissionPending = false
            navigationIssued = true
            latestStart()
        }
    }
    return WorkoutStartRequest(start = {
        if (!permissionPending && !navigationIssued) {
            val granted = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            // Respect a denial from an older app build that did not yet store our marker.
            val previouslyDenied = Build.VERSION.SDK_INT >= 33 && activity != null &&
                ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)
            if (previouslyDenied) preferences.edit { putBoolean(WORKOUT_NOTIFICATION_ASKED, true) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                shouldRequestWorkoutNotifications(Build.VERSION.SDK_INT, granted,
                    preferences.getBoolean(WORKOUT_NOTIFICATION_ASKED, false) || previouslyDenied, resuming)) {
                permissionPending = true
                // Persist before launching so recreation/denial never loops the automatic prompt.
                preferences.edit { putBoolean(WORKOUT_NOTIFICATION_ASKED, true) }
                try {
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } catch (_: IllegalStateException) {
                    permissionPending = false
                    navigationIssued = true
                    latestStart()
                }
            } else {
                navigationIssued = true
                latestStart()
            }
        }
    }, pending = permissionPending || navigationIssued)
}

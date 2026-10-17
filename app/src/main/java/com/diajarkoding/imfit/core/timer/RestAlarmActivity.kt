package com.diajarkoding.imfit.core.timer

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.diajarkoding.imfit.MainActivity
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.core.notification.WorkoutNotificationManager
import com.diajarkoding.imfit.theme.IMFITTheme
import com.diajarkoding.imfit.theme.ThemeViewModel
import com.diajarkoding.imfit.theme.IMFITColors
import com.diajarkoding.imfit.theme.IMFITTypography
import com.diajarkoding.imfit.theme.IMFITSizes
import com.diajarkoding.imfit.theme.IMFITCornerRadius
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Lock-screen destination only. Playback remains owned by the workout service. */
@AndroidEntryPoint
class RestAlarmActivity : ComponentActivity() {
    @Inject lateinit var timer: RestTimerCoordinator
    private var observation: Job? = null
    private val theme: ThemeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        observeAlarm(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        observeAlarm(intent)
    }

    private fun observeAlarm(intent: Intent) {
        observation?.cancel()
        val timerId = intent.getStringExtra("timer_id")
        val generation = intent.getLongExtra("generation", -1)
        // Data is part of PendingIntent identity, and must agree with its payload.
        if (timerId == null || intent.data?.toString() != "imfit-rest://full-screen/$timerId/$generation") {
            finish()
            return
        }
        observation = lifecycleScope.launch {
            timer.restore()
            if (timer.state.value?.matchesExpired(timerId, generation) != true) {
                finish()
                return@launch
            }
            timer.reconcile(source = "alarm_activity")
            timer.state.collect { state ->
                if (state?.matchesExpired(timerId, generation) != true) {
                    finish()
                } else {
                    setContent {
                        val themeState by theme.uiState.collectAsStateWithLifecycle()
                        IMFITTheme(darkTheme = themeState.isDarkMode) {
                            RestAlarmScreen(exerciseName = state.exerciseName, darkTheme = themeState.isDarkMode) {
                                startActivity(Intent(this@RestAlarmActivity, MainActivity::class.java).apply {
                                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                    putExtra(WorkoutNotificationManager.EXTRA_OPEN_WORKOUT, true)
                                    putExtra(WorkoutNotificationManager.EXTRA_TEMPLATE_ID, state.templateId)
                                })
                                finish() // Navigation never acknowledges the timer.
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RestAlarmScreen(exerciseName: String, darkTheme: Boolean, onOpenWorkout: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier.size(64.dp).background(
                        IMFITColors.alarmIllustration, CircleShape
                    ),
                    contentAlignment = Alignment.Center
                ) {
                    RestBellIllustration()
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.rest_finished),
                    style = IMFITTypography.titleLarge,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(24.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            stringResource(R.string.rest_alarm_exercise_label), style = IMFITTypography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(exerciseName, style = IMFITTypography.alarmExercise)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            stringResource(R.string.rest_alarm_notification_title),
                            style = IMFITTypography.bodyMedium
                        )
                        Text(
                            stringResource(R.string.rest_alarm_notification_stop),
                            style = IMFITTypography.alarmBody,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Button(
                onClick = onOpenWorkout,
                modifier = Modifier.fillMaxWidth().heightIn(min = IMFITSizes.buttonHeight),
                shape = RoundedCornerShape(IMFITCornerRadius.template)
            ) {
                Text(stringResource(R.string.rest_open_workout), style = IMFITTypography.alarmAction, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.rest_open_workout_hint), style = IMFITTypography.alarmHint,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center
            )
        }
    }
}

/** Decorative vector drawn in-place; no image asset or icon in the text cards. */
@Composable
private fun RestBellIllustration() {
    val tint = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(24.dp)) {
        scale(size.width / 24f, size.height / 24f, pivot = androidx.compose.ui.geometry.Offset.Zero) {
            val bell = Path().apply {
                moveTo(5f, 17f)
                lineTo(7f, 14f)
                lineTo(7f, 10f)
                cubicTo(7f, 3f, 17f, 3f, 17f, 10f)
                lineTo(17f, 14f)
                lineTo(19f, 17f)
                close()
                moveTo(10f, 20f)
                cubicTo(10f, 22.5f, 14f, 22.5f, 14f, 20f)
                moveTo(12f, 3f)
                lineTo(12f, 5f)
            }
            drawPath(bell, tint, style = Stroke(width = 1.8f, cap = androidx.compose.ui.graphics.StrokeCap.Round,
                join = androidx.compose.ui.graphics.StrokeJoin.Round))
        }
    }
}

@Preview(name = "Rest alarm · small", widthDp = 320, heightDp = 568, locale = "in")
@Composable
private fun RestAlarmSmallPreview() {
    IMFITTheme { RestAlarmScreen("Barbell Bench Press", darkTheme = false) {} }
}

@Preview(name = "Rest alarm · dark large text", widthDp = 320, heightDp = 480, fontScale = 2f, locale = "in")
@Composable
private fun RestAlarmDarkLargeTextPreview() {
    IMFITTheme(darkTheme = true) {
        RestAlarmScreen("Incline Dumbbell Bench Press — latihan dengan nama panjang", darkTheme = true) {}
    }
}

package com.diajarkoding.imfit.presentation.ui.workout

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import kotlinx.coroutines.launch
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.domain.model.ExerciseLog
import com.diajarkoding.imfit.domain.model.WorkoutSet
import com.diajarkoding.imfit.presentation.components.common.IMFITButton
import com.diajarkoding.imfit.presentation.components.common.IMFITDialog
import com.diajarkoding.imfit.presentation.components.common.IMFITDialogType
import com.diajarkoding.imfit.presentation.components.common.IMFITSecondaryButton
import com.diajarkoding.imfit.theme.DeletePink
import com.diajarkoding.imfit.theme.IMFITShapes
import com.diajarkoding.imfit.theme.IMFITSizes
import com.diajarkoding.imfit.theme.IMFITSpacing
import com.diajarkoding.imfit.theme.Primary
import com.diajarkoding.imfit.theme.PrimaryLight
import com.diajarkoding.imfit.core.service.WorkoutService
import com.diajarkoding.imfit.core.notification.WorkoutNotificationReceiver
import com.diajarkoding.imfit.core.notification.NotificationChannels
import com.diajarkoding.imfit.theme.SetComplete
import androidx.core.content.ContextCompat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveWorkoutScreen(
    templateId: String,
    onNavigateBack: () -> Unit,
    onWorkoutFinished: (String) -> Unit,
    viewModel: ActiveWorkoutViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    var showRestConfigSheet by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    
    // Notification permission launcher for Android 13+
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            android.util.Log.d("ActiveWorkout", "Notification permission granted")
        } else {
            android.util.Log.w("ActiveWorkout", "Notification permission denied")
        }
    }
    
    // Request notification permission on Android 13+
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            
            if (!hasPermission) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
    
    // Service binding
    var workoutService by remember { mutableStateOf<WorkoutService?>(null) }
    var isBound by remember { mutableStateOf(false) }
    
    val serviceConnection = remember {
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val binder = service as? WorkoutService.WorkoutBinder
                workoutService = binder?.getService()
                isBound = true
                
                // Restore rest timer state from service
                workoutService?.getRestTimerState()?.let { restState ->
                    if (restState.isActive) {
                        viewModel.restoreRestTimerState(
                            remainingSeconds = restState.remainingSeconds,
                            exerciseName = restState.exerciseName
                        )
                    }
                }
            }
            
            override fun onServiceDisconnected(name: ComponentName?) {
                workoutService = null
                isBound = false
            }
        }
    }
    
    // Bind to service
    DisposableEffect(Unit) {
        val intent = Intent(context, WorkoutService::class.java)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        
        onDispose {
            if (isBound) {
                context.unbindService(serviceConnection)
                isBound = false
            }
        }
    }
    
    // Listen for rest timer finished from service
    LaunchedEffect(workoutService) {
        workoutService?.restTimerFinished?.collect {
            viewModel.onRestTimerFinished()
        }
    }
    
    // Poll rest timer state from service to update UI
    LaunchedEffect(workoutService, state.isRestTimerActive) {
        if (workoutService != null && state.isRestTimerActive) {
            while (state.isRestTimerActive) {
                val restState = workoutService?.getRestTimerState()
                if (restState != null && restState.isActive) {
                    viewModel.updateRestTimerSeconds(restState.remainingSeconds)
                }
                kotlinx.coroutines.delay(500)
            }
        }
    }
    
    // Listen for notification action broadcasts
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.getStringExtra(WorkoutNotificationReceiver.EXTRA_COMMAND)) {
                    WorkoutNotificationReceiver.COMMAND_STOP -> {
                        viewModel.showCancelDialog()
                    }
                    WorkoutNotificationReceiver.COMMAND_PAUSE -> {
                        viewModel.pauseWorkout()
                        val pauseIntent = Intent(context, WorkoutService::class.java).apply {
                            action = WorkoutService.ACTION_PAUSE_WORKOUT
                        }
                        context.startService(pauseIntent)
                    }
                    WorkoutNotificationReceiver.COMMAND_RESUME -> {
                        viewModel.resumeWorkout()
                        val resumeIntent = Intent(context, WorkoutService::class.java).apply {
                            action = WorkoutService.ACTION_RESUME_WORKOUT
                        }
                        context.startService(resumeIntent)
                    }
                    WorkoutNotificationReceiver.COMMAND_SKIP_REST -> {
                        viewModel.skipRestTimer()
                    }
                }
            }
        }
        
        val filter = IntentFilter(WorkoutNotificationReceiver.ACTION_WORKOUT_COMMAND)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        
        onDispose {
            context.unregisterReceiver(receiver)
        }
    }
    
    // Show pause error via snackbar
    LaunchedEffect(state.pauseError) {
        state.pauseError?.let { error ->
            coroutineScope.launch {
                snackbarHostState.showSnackbar(error)
                viewModel.clearPauseError()
            }
        }
    }

    LaunchedEffect(templateId) {
        viewModel.startWorkout(templateId)
    }
    
    // Start foreground service when workout session is available
    // Only start if service is not already bound/running
    LaunchedEffect(state.session?.id, isBound) {
        state.session?.let { session ->
            // Skip if service is already running (bound means it's running)
            if (isBound && workoutService?.isRunning?.value == true) {
                android.util.Log.d("ActiveWorkout", "Service already running, skipping start")
                return@let
            }
            
            val intent = Intent(context, WorkoutService::class.java).apply {
                action = WorkoutService.ACTION_START_WORKOUT
                putExtra(WorkoutService.EXTRA_WORKOUT_NAME, session.templateName)
                putExtra(WorkoutService.EXTRA_TEMPLATE_ID, session.templateId)
                putExtra(WorkoutService.EXTRA_START_TIME, session.startTime)
                putExtra(WorkoutService.EXTRA_TOTAL_PAUSED_TIME, session.totalPausedTimeMs)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
    
    // Send workout timer updates to service
    LaunchedEffect(Unit) {
        viewModel.workoutTimerUpdates.collect { update ->
            val intent = Intent(context, WorkoutService::class.java).apply {
                action = WorkoutService.ACTION_UPDATE_WORKOUT
                putExtra(WorkoutService.EXTRA_WORKOUT_NAME, update.workoutName)
                putExtra(WorkoutService.EXTRA_ELAPSED_SECONDS, update.elapsedSeconds)
                putExtra(WorkoutService.EXTRA_COMPLETED_SETS, update.completedSets)
                putExtra(WorkoutService.EXTRA_TOTAL_SETS, update.totalSets)
                putExtra(WorkoutService.EXTRA_TOTAL_VOLUME, update.totalVolume)
                putExtra(WorkoutService.EXTRA_IS_PAUSED, update.isPaused)
            }
            context.startService(intent)
        }
    }
    
    // Start rest timer in service
    LaunchedEffect(Unit) {
        viewModel.startRestTimer.collect { request ->
            val intent = Intent(context, WorkoutService::class.java).apply {
                action = WorkoutService.ACTION_START_REST
                putExtra(WorkoutService.EXTRA_REMAINING_SECONDS, request.seconds)
                putExtra(WorkoutService.EXTRA_EXERCISE_NAME, request.exerciseName)
            }
            context.startService(intent)
        }
    }
    
    // Skip rest timer in service
    LaunchedEffect(Unit) {
        viewModel.skipRestTimerFlow.collect {
            val intent = Intent(context, WorkoutService::class.java).apply {
                action = WorkoutService.ACTION_SKIP_REST
            }
            context.startService(intent)
        }
    }

    LaunchedEffect(state.workoutLogId) {
        state.workoutLogId?.let { logId ->
            // Stop service when workout is finished
            val stopIntent = Intent(context, WorkoutService::class.java).apply {
                action = WorkoutService.ACTION_STOP_WORKOUT
            }
            context.startService(stopIntent)
            onWorkoutFinished(logId)
        }
    }

    if (state.isRestTimerActive) {
        RestTimerDialog(
            remainingSeconds = state.restTimerSeconds,
            onDismiss = { viewModel.skipRestTimer() }
        )
    }

    if (showRestConfigSheet) {
        // Get current rest time (session override or default from first exercise)
        val currentRestTime = viewModel.getCurrentRestTime()
        
        RestTimerConfigSheet(
            currentRestSeconds = currentRestTime,
            onDismiss = { showRestConfigSheet = false },
            onConfirm = { seconds ->
                // Set session-level override - applies to ALL rest timers for this session
                viewModel.setSessionRestOverride(seconds)
                showRestConfigSheet = false
            }
        )
    }

    if (state.showCancelDialog) {
        IMFITDialog(
            onDismissRequest = { viewModel.dismissCancelDialog() },
            title = stringResource(R.string.active_workout_cancel_title),
            message = stringResource(R.string.active_workout_cancel_message),
            icon = Icons.Default.Close,
            type = IMFITDialogType.DESTRUCTIVE,
            confirmText = stringResource(R.string.action_cancel_workout),
            dismissText = stringResource(R.string.action_continue),
            onConfirm = {
                // Stop service when workout is cancelled
                val stopIntent = Intent(context, WorkoutService::class.java).apply {
                    action = WorkoutService.ACTION_STOP_WORKOUT
                }
                context.startService(stopIntent)
                viewModel.cancelWorkout()
                onNavigateBack()
            },
            onDismiss = { viewModel.dismissCancelDialog() }
        )
    }

    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = if (state.isPaused) "PAUSED" else (state.session?.templateName ?: "Workout"),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (state.isPaused) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = formatElapsedTime(state.elapsedSeconds),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (state.isPaused) MaterialTheme.colorScheme.error else SetComplete,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.showCancelDialog() }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.action_cancel)
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                if (state.isPaused) {
                                    viewModel.resumeWorkout()
                                    val resumeIntent = Intent(context, WorkoutService::class.java).apply {
                                        action = WorkoutService.ACTION_RESUME_WORKOUT
                                    }
                                    context.startService(resumeIntent)
                                } else {
                                    viewModel.pauseWorkout()
                                    val pauseIntent = Intent(context, WorkoutService::class.java).apply {
                                        action = WorkoutService.ACTION_PAUSE_WORKOUT
                                    }
                                    context.startService(pauseIntent)
                                }
                            }
                        ) {
                            Icon(
                                if (state.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                contentDescription = if (state.isPaused) stringResource(R.string.action_resume) else stringResource(R.string.action_pause)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )

                // Progress Chips in AppBar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = IMFITSpacing.screenHorizontal)
                        .padding(bottom = IMFITSpacing.md),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    ProgressChip(
                        label = stringResource(R.string.workout_label_sets),
                        value = "${state.session?.totalCompletedSets ?: 0}/${state.session?.totalSets ?: 0}"
                    )
                    ProgressChip(
                        label = stringResource(R.string.label_volume),
                        value = "${String.format("%.0f", state.session?.totalVolume ?: 0f)} kg",
                        isPrimary = true
                    )
                }
            }
        },
        bottomBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(8.dp, spotColor = Color.Black.copy(alpha = 0.1f))
                    .background(MaterialTheme.colorScheme.surface)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(IMFITSpacing.screenHorizontal)
                    .padding(vertical = IMFITSpacing.xs)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(IMFITSpacing.md)
                ) {
                    // Timer Button - Icon only with proper proportions
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(IMFITShapes.Button)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { showRestConfigSheet = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Timer,
                            contentDescription = stringResource(R.string.active_workout_rest_timer),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    // Finish Button
                    Box(modifier = Modifier.weight(1f)) {
                        IMFITButton(
                            text = stringResource(R.string.action_finish_workout),
                            onClick = { viewModel.finishWorkout() },
                            enabled = (state.session?.totalCompletedSets ?: 0) > 0 && !state.isFinishing,
                            isLoading = state.isFinishing,
                            icon = Icons.Default.Check
                        )
                    }
                }
            }
        }
    ) { padding ->
        if (state.session == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stringResource(R.string.loading_workout),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(IMFITSpacing.screenHorizontal),
                verticalArrangement = Arrangement.spacedBy(IMFITSpacing.lg)
            ) {
                state.session?.exerciseLogs?.forEachIndexed { exerciseIndex, exerciseLog ->
                    item(key = "exercise_${exerciseIndex}_${exerciseLog.exercise.id}") {
                        ExerciseSection(
                            exerciseLog = exerciseLog,
                            exerciseIndex = exerciseIndex,
                            onSetUpdate = { setIndex, weight, reps ->
                                viewModel.updateSet(exerciseIndex, setIndex, weight, reps)
                            },
                            onSetComplete = { setIndex ->
                                viewModel.completeSet(exerciseIndex, setIndex)
                            },
                            onAddSet = { viewModel.addSet(exerciseIndex) },
                            onRemoveSet = { setIndex ->
                                viewModel.removeSet(
                                    exerciseIndex,
                                    setIndex
                                )
                            }
                        )
                    }
                }

                item(key = "spacer_bottom") { Spacer(modifier = Modifier.height(IMFITSpacing.huge)) }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun RestTimerDialogPreview() {
    com.diajarkoding.imfit.theme.IMFITTheme(darkTheme = false) {
        RestTimerDialog(
            remainingSeconds = 90,
            onDismiss = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ProgressChipPreview() {
    com.diajarkoding.imfit.theme.IMFITTheme(darkTheme = false) {
        ProgressChip(
            label = "Sets",
            value = "5/12"
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ProgressChipPrimaryPreview() {
    com.diajarkoding.imfit.theme.IMFITTheme(darkTheme = false) {
        ProgressChip(
            label = "Volume",
            value = "1250 kg",
            isPrimary = true
        )
    }
}

@Composable
private fun RestTimerConfigSheet(
    currentRestSeconds: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    // Convert seconds to hours, minutes, seconds
    val initialHours = currentRestSeconds / 3600
    val initialMinutes = (currentRestSeconds % 3600) / 60
    val initialSeconds = currentRestSeconds % 60

    var hours by remember { mutableIntStateOf(initialHours) }
    var minutes by remember { mutableIntStateOf(initialMinutes) }
    var seconds by remember { mutableIntStateOf(initialSeconds) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = IMFITShapes.Dialog,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .padding(IMFITSpacing.xl)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.active_workout_rest_timer),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(IMFITSpacing.xl))

                // Scrollable Time Picker
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Hours
                    TimePickerColumn(
                        value = hours,
                        onValueChange = { hours = it },
                        range = 0..23,
                        label = "hours"
                    )
                    
                    Text(
                        text = ":",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = IMFITSpacing.sm)
                    )
                    
                    // Minutes
                    TimePickerColumn(
                        value = minutes,
                        onValueChange = { minutes = it },
                        range = 0..59,
                        label = "min"
                    )
                    
                    Text(
                        text = ":",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = IMFITSpacing.sm)
                    )
                    
                    // Seconds
                    TimePickerColumn(
                        value = seconds,
                        onValueChange = { seconds = it },
                        range = 0..59,
                        label = "sec"
                    )
                }

                Spacer(modifier = Modifier.height(IMFITSpacing.xl))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(IMFITSpacing.md)
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        IMFITSecondaryButton(
                            text = stringResource(R.string.action_cancel),
                            onClick = onDismiss
                        )
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        IMFITButton(
                            text = stringResource(R.string.action_save),
                            onClick = {
                                val totalSeconds = hours * 3600 + minutes * 60 + seconds
                                onConfirm(if (totalSeconds > 0) totalSeconds else 60)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TimePickerColumn(
    value: Int,
    onValueChange: (Int) -> Unit,
    range: IntRange,
    label: String
) {
    val itemHeight = 40.dp
    val visibleItems = 3
    val listHeight = itemHeight * visibleItems
    val coroutineScope = rememberCoroutineScope()
    
    // Initialize at the correct position (item at center)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = value)
    val flingBehavior = rememberSnapFlingBehavior(lazyListState = listState)
    
    // Sync scroll position when value changes externally (e.g., clicking an item)
    LaunchedEffect(value) {
        if (listState.firstVisibleItemIndex != value) {
            listState.animateScrollToItem(value)
        }
    }
    
    // Update value when scroll stops
    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) {
            val centerIndex = listState.firstVisibleItemIndex
            if (centerIndex != value && centerIndex in range) {
                onValueChange(centerIndex)
            }
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .width(64.dp)
                .height(listHeight)
                .clip(IMFITShapes.Chip)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                flingBehavior = flingBehavior,
                contentPadding = PaddingValues(vertical = itemHeight) // One item padding top/bottom for center alignment
            ) {
                items(range.toList(), key = { it }) { num ->
                    val isSelected = num == value
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(itemHeight)
                            .clickable {
                                coroutineScope.launch {
                                    listState.animateScrollToItem(num)
                                }
                                onValueChange(num)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = String.format("%02d", num),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isSelected) Primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
            
            // Selection indicator overlay - centered
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(itemHeight)
                    .align(Alignment.Center)
                    .background(Primary.copy(alpha = 0.1f))
            )
        }
        
        Spacer(modifier = Modifier.height(IMFITSpacing.xs))
        
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatElapsedTime(seconds: Long): String {
    val hh = seconds / 3600
    val mm = (seconds % 3600) / 60
    val ss = seconds % 60
    return if (hh > 0) {
        String.format("%02d:%02d:%02d", hh, mm, ss)
    } else {
        String.format(
            "%02d:%02d:%02d",
            0,
            mm,
            ss
        ) // Requirement: 00:00:00 even if no hours? "00:00:00" format implies 3 parts.
    }
}

@Composable
private fun ProgressChip(
    label: String,
    value: String,
    isPrimary: Boolean = false
) {
    Row(
        modifier = Modifier
            .clip(IMFITShapes.Chip)
            .background(
                if (isPrimary) Primary.copy(alpha = 0.1f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
            )
            .padding(horizontal = IMFITSpacing.md, vertical = IMFITSpacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = if (isPrimary) Primary else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun ExerciseSection(
    exerciseLog: ExerciseLog,
    exerciseIndex: Int,
    onSetUpdate: (Int, Float, Int) -> Unit,
    onSetComplete: (Int) -> Unit,
    onAddSet: () -> Unit,
    onRemoveSet: (Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = IMFITShapes.Card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(IMFITSpacing.cardPadding)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(IMFITShapes.IconContainer)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.FitnessCenter,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(IMFITSizes.iconSm)
                    )
                }
                Spacer(modifier = Modifier.width(IMFITSpacing.md))
                Column {
                    Text(
                        text = exerciseLog.exercise.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(id = exerciseLog.exercise.muscleCategory.stringResourceId),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(IMFITSpacing.lg))

            // Headers
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = IMFITSpacing.xs),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    stringResource(R.string.label_set_header),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(36.dp),
                    textAlign = TextAlign.Center
                )
                Text(
                    stringResource(R.string.label_kg_header),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )
                Text(
                    stringResource(R.string.label_reps_header),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.width(32.dp))
                Text(
                    stringResource(R.string.exercise_placeholder),
                    modifier = Modifier.width(44.dp)
                )
            }

            Spacer(modifier = Modifier.height(IMFITSpacing.sm))

            // Sets
            exerciseLog.sets.forEachIndexed { setIndex, set ->
                SetInputRow(
                    set = set,
                    setIndex = setIndex,
                    canDelete = exerciseLog.sets.size > 1,
                    onUpdate = { weight, reps -> onSetUpdate(setIndex, weight, reps) },
                    onComplete = { onSetComplete(setIndex) },
                    onDelete = { onRemoveSet(setIndex) }
                )
                if (setIndex < exerciseLog.sets.size - 1) {
                    Spacer(modifier = Modifier.height(IMFITSpacing.sm))
                }
            }

            // Add Set
            Spacer(modifier = Modifier.height(IMFITSpacing.md))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(IMFITShapes.Chip)
                    .background(Primary.copy(alpha = 0.08f))
                    .clickable { onAddSet() }
                    .padding(vertical = IMFITSpacing.md),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(R.string.action_add_set),
                    tint = Primary,
                    modifier = Modifier.size(IMFITSizes.iconSm)
                )
                Spacer(modifier = Modifier.width(IMFITSpacing.sm))
                Text(
                    text = stringResource(R.string.action_add_set),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Primary
                )
            }
        }
    }
}

@Composable
private fun SetInputRow(
    set: WorkoutSet,
    setIndex: Int,
    canDelete: Boolean,
    onUpdate: (Float, Int) -> Unit,
    onComplete: () -> Unit,
    onDelete: () -> Unit
) {
    val rowBackground = if (set.isCompleted) SetComplete.copy(alpha = 0.08f)
    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    val inputBackground = MaterialTheme.colorScheme.background

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(IMFITShapes.Chip)
            .background(rowBackground)
            .padding(horizontal = IMFITSpacing.sm, vertical = IMFITSpacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Set Number
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(if (set.isCompleted) SetComplete else Primary),
            contentAlignment = Alignment.Center
        ) {
            if (set.isCompleted) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            } else {
                Text(
                    text = "${setIndex + 1}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }

        Spacer(modifier = Modifier.width(IMFITSpacing.sm))

        // Weight Input
        Box(
            modifier = Modifier
                .weight(1f)
                .height(48.dp)
                .clip(IMFITShapes.Chip)
                .background(inputBackground)
                .padding(horizontal = 2.dp),
            contentAlignment = Alignment.Center
        ) {
            // Use TextFieldValue to maintain cursor position at end
            var weightTextFieldValue by remember(set.weight) {
                val text = if (set.weight > 0) set.weight.toInt().toString() else ""
                mutableStateOf(TextFieldValue(text, TextRange(text.length)))
            }
            
            OutlinedTextField(
                value = weightTextFieldValue,
                onValueChange = { newValue ->
                    // Only allow numeric input
                    val filtered = newValue.text.filter { it.isDigit() }
                    val weight = filtered.toFloatOrNull() ?: 0f
                    
                    // Update the text field value with cursor at end
                    weightTextFieldValue = TextFieldValue(
                        text = if (weight > 0) weight.toInt().toString() else filtered,
                        selection = TextRange((if (weight > 0) weight.toInt().toString() else filtered).length)
                    )
                    
                    onUpdate(weight, set.reps)
                },
                modifier = Modifier.fillMaxSize(),
                enabled = !set.isCompleted,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                textStyle = MaterialTheme.typography.titleMedium.copy(
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.SemiBold
                ),
                shape = IMFITShapes.Chip,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    disabledBorderColor = Color.Transparent,
                    disabledTextColor = MaterialTheme.colorScheme.onSurface
                ),
                placeholder = {
                    Text(
                        text = "0",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            )
        }

        Spacer(modifier = Modifier.width(IMFITSpacing.sm))

        // Reps Input
        Box(
            modifier = Modifier
                .weight(1f)
                .height(48.dp)
                .clip(IMFITShapes.Chip)
                .background(inputBackground)
                .padding(horizontal = 2.dp),
            contentAlignment = Alignment.Center
        ) {
            // Use TextFieldValue to maintain cursor position at end
            var repsTextFieldValue by remember(set.reps) {
                val text = if (set.reps > 0) set.reps.toString() else ""
                mutableStateOf(TextFieldValue(text, TextRange(text.length)))
            }
            
            OutlinedTextField(
                value = repsTextFieldValue,
                onValueChange = { newValue ->
                    // Only allow numeric input
                    val filtered = newValue.text.filter { it.isDigit() }
                    val reps = filtered.toIntOrNull() ?: 0
                    
                    // Update the text field value with cursor at end
                    repsTextFieldValue = TextFieldValue(
                        text = if (reps > 0) reps.toString() else filtered,
                        selection = TextRange((if (reps > 0) reps.toString() else filtered).length)
                    )
                    
                    onUpdate(set.weight, reps)
                },
                modifier = Modifier.fillMaxSize(),
                enabled = !set.isCompleted,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                textStyle = MaterialTheme.typography.titleMedium.copy(
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.SemiBold
                ),
                shape = IMFITShapes.Chip,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    disabledBorderColor = Color.Transparent,
                    disabledTextColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }

        Spacer(modifier = Modifier.width(IMFITSpacing.xs))

        // Delete
        IconButton(
            onClick = onDelete,
            enabled = canDelete && !set.isCompleted,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = stringResource(R.string.action_delete),
                tint = if (canDelete && !set.isCompleted) DeletePink.copy(alpha = 0.8f)
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                modifier = Modifier.size(20.dp)
            )
        }

        // Checkbox
        Checkbox(
            checked = set.isCompleted,
            onCheckedChange = { if (!set.isCompleted && set.weight > 0 && set.reps > 0) onComplete() },
            enabled = !set.isCompleted && set.weight > 0 && set.reps > 0,
            colors = CheckboxDefaults.colors(
                checkedColor = SetComplete,
                checkmarkColor = Color.White
            )
        )
    }
}

@Composable
private fun RestTimerDialog(
    remainingSeconds: Int,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = {}) {
        Card(
            shape = IMFITShapes.Dialog,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(IMFITSpacing.xxl),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(listOf(Primary, PrimaryLight))
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Timer,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(IMFITSizes.iconLg)
                    )
                }

                Spacer(modifier = Modifier.height(IMFITSpacing.lg))

                Text(
                    text = stringResource(R.string.active_workout_rest_timer),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(IMFITSpacing.xxl))

                Text(
                    text = String.format("%d:%02d", remainingSeconds / 60, remainingSeconds % 60),
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                    color = Primary
                )

                Spacer(modifier = Modifier.height(IMFITSpacing.xxl))

                IMFITSecondaryButton(
                    text = stringResource(R.string.action_skip),
                    onClick = onDismiss
                )
            }
        }
    }
}

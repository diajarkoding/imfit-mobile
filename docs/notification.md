📋 PLANNING LENGKAP: FITUR NOTIFICATION WORKOUT DENGAN REAL-TIME REST TIMER

🎯 OVERVIEW FITUR

Menambahkan notification system yang menampilkan status workout aktif dengan real-time rest timer
saat user melakukan workout.

  ---
📱 FITUR YANG AKAN DIBUAT

1. Initial Workout Notification

- Muncul ketika user menekan tombol "Start Workout" di WorkoutDetailScreen
- Menampilkan nama workout dan status "Workout Started"
- Tidak dapat di-swipe away (ongoing notification)
- Menampilkan elapsed time workout

2. Real-Time Rest Timer Notification

- Update otomatis saat user menyelesaikan set dan memasuki periode rest
- Menampilkan countdown timer (misal: "Rest: 00:45")
- Update setiap detik secara real-time
- Berubah kembali ke workout status setelah rest selesai

3. Notification Actions

- Tap notification: Buka ActiveWorkoutScreen
- Stop button: Hentikan workout (dengan konfirmasi)
- Pause/Resume: Opsional untuk pause workout

  ---

📂 FILE BARU YANG AKAN DIBUAT

1. WorkoutNotificationManager.kt

Lokasi: app/src/main/java/com/diajarkoding/imfit/core/notification/

Fungsi:

- Manage semua notification terkait workout
- Create, update, dan cancel workout notification
- Handle notification clicks dan actions

Key Methods:
class WorkoutNotificationManager(private val context: Context) {
fun showWorkoutStartedNotification(workoutName: String, sessionId: String)
fun updateRestTimerNotification(remainingSeconds: Int, exerciseName: String?)
fun updateWorkoutElapsedNotification(elapsedSeconds: Long, completedSets: Int)
fun dismissWorkoutNotification()
}

2. WorkoutService.kt

Lokasi: app/src/main/java/com/diajarkoding/imfit/core/service/

Fungsi:

- Foreground service yang menjaga workout tetap berjalan meskipun app di-background
- Receive dan broadcast workout state updates
- Update notification secara real-time

Key Features:
class WorkoutService : Service() {
companion object {
const val ACTION_START_WORKOUT = "action_start_workout"
const val ACTION_UPDATE_REST = "action_update_rest"
const val ACTION_STOP_WORKOUT = "action_stop_workout"
const val ACTION_FINISH_WORKOUT = "action_finish_workout"
}

      private val restTimerChannel = Channel<RestTimerUpdate>()
      private val workoutTimerChannel = Channel<WorkoutTimerUpdate>()

}

3. NotificationChannels.kt

Lokasi: app/src/main/java/com/diajarkoding/imfit/core/notification/

Fungsi:

- Define notification channels untuk Android O+
- Setup channel importance dan behavior

Channels:
const val WORKOUT_ACTIVE_CHANNEL = "workout_active_channel"
const val WORKOUT_REST_TIMER_CHANNEL = "workout_rest_timer_channel"

4. RestTimerUpdate.kt (Data Class)

Lokasi: app/src/main/java/com/diajarkoding/imfit/core/model/

Fungsi:

- Data class untuk mengirim rest timer updates

data class RestTimerUpdate(
val remainingSeconds: Int,
val isRestActive: Boolean,
val currentExerciseName: String? = null
)

data class WorkoutTimerUpdate(
val elapsedSeconds: Long,
val totalCompletedSets: Int,
val totalVolume: Float
)

5. WorkoutReceiver.kt (Broadcast Receiver)

Lokasi: app/src/main/java/com/diajarkoding/imfit/core/receiver/

Fungsi:

- Handle notification action clicks
- Broadcast workout events ke aplikasi

  ---

🔧 FILE YANG AKAN DIMODIFIKASI

1. ActiveWorkoutViewModel.kt

Perubahan:

- Tambah channels/flows untuk mengirim updates ke Service
- Emit rest timer state changes untuk notification
- Emit workout elapsed time updates

// New additions
val restTimerUpdates: Flow<RestTimerUpdate> = ...
val workoutTimerUpdates: Flow<WorkoutTimerUpdate> = ...

private fun startRestTimer(exerciseIndex: Int) {
// ... existing logic ...

      // Emit update untuk notification
      viewModelScope.launch {
          while (remaining > 0) {
              delay(1000)
              remaining--
              _restTimerUpdates.emit(RestTimerUpdate(remaining, true, exerciseName))
          }
      }

}

2. ActiveWorkoutScreen.kt

Perubahan:

- Observe rest timer dan workout timer flows
- Send updates ke WorkoutService

LaunchedEffect(state.isRestTimerActive) {
viewModel.restTimerUpdates.collect { update ->
// Send ke service untuk update notification
workoutService?.sendRestTimerUpdate(update)
}
}

LaunchedEffect(state.elapsedSeconds) {
viewModel.workoutTimerUpdates.collect { update ->
// Update notification dengan elapsed time
workoutService?.sendWorkoutTimerUpdate(update)
}
}

3. WorkoutDetailViewModel.kt

Perubahan:

- Start WorkoutService ketika workout dimulai
- Stop WorkoutService ketika workout selesai/dibatalkan

fun startWorkout(workoutId: String) {
viewModelScope.launch {
// ... existing logic ...

          // Start foreground service
          workoutService.startWorkout(workout)
      }

}

fun endWorkout() {
viewModelScope.launch {
// ... existing logic ...

          // Stop service dan dismiss notification
          workoutService.stopWorkout()
      }

}

4. WorkoutDetailScreen.kt

Perubahan:

- Tidak ada perubahan signifikan di UI
- Start service melalui ViewModel

5. AndroidManifest.xml

Perubahan:

- Tambah permissions
- Register WorkoutService
- Register WorkoutReceiver

  <!-- Permissions -->
  <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
  <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
  <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

  <!-- Service -->

<service
android:name=".core.service.WorkoutService"
android:foregroundServiceType="dataSync"
android:exported="false" />

  <!-- Receiver -->
<receiver
android:name=".core.receiver.WorkoutReceiver"
android:exported="false">
<intent-filter>
<action android:name="com.diajarkoding.imfit.ACTION_STOP_WORKOUT" />
<action android.name="com.diajarkoding.imfit.ACTION_PAUSE_WORKOUT" />
</intent-filter>
</receiver>

6. ImFitApplication.kt

Perubahan:

- Initialize WorkoutNotificationManager
- Provide singleton instance

class ImFitApplication : Application() {
lateinit var workoutNotificationManager: WorkoutNotificationManager

      override fun onCreate() {
          super.onCreate()
          workoutNotificationManager = WorkoutNotificationManager(this)
      }

}

7. di/AppModule.kt

Perubahan:

- Provide WorkoutService helper class
- Provide WorkoutNotificationManager

@Provides
@Singleton
fun provideWorkoutNotificationManager(app: Application): WorkoutNotificationManager {
return (app as ImFitApplication).workoutNotificationManager
}

@Provides
@Singleton
fun provideWorkoutServiceHelper(
@ApplicationContext context: Context
): WorkoutServiceHelper {
return WorkoutServiceHelper(context)
}

  ---
🏗️ ARSITEKTUR SOLUTION

┌─────────────────────────────────────────────────────────────┐
│ UI LAYER │
├─────────────────────────────────────────────────────────────┤
│ WorkoutDetailScreen ActiveWorkoutScreen │
│ ↓ ↓ │
│ WorkoutDetailViewModel ActiveWorkoutViewModel │
│ ↓ ↓ │
└───────┴──────────────────────────────┴───────────────────────┘
↓
┌───────────────────────────────────────────────────────────────┐
│ SERVICE LAYER │
├───────────────────────────────────────────────────────────────┤
│ WorkoutService (Foreground Service)                          │
│ - Maintains workout lifecycle │
│ - Updates notification real-time │
│ - Handles service lifecycle events │
└───────┬───────────────────────────────────────────────────────┘
↓
┌───────────────────────────────────────────────────────────────┐
│ NOTIFICATION LAYER │
├───────────────────────────────────────────────────────────────┤
│ WorkoutNotificationManager │
│ - Create notifications │
│ - Update rest timer real-time │
│ - Handle notification actions │
└───────┬───────────────────────────────────────────────────────┘
↓
┌───────────────────────────────────────────────────────────────┐
│ SYSTEM NOTIFICATION │
│  [🏋️ Push Day - 00:15:32]                    [Stop] [Pause] │
│ Elapsed: 15:32 • Sets: 6/12 • Vol: 1,250 kg │
│ │
│ ──────────────────────────────────────────────────────────── │
│ │
│  [⏸️ Rest Timer - 00:45]                     [Skip] [Stop]    │
│ Resting after: Barbell Bench Press │
└───────────────────────────────────────────────────────────────┘

  ---
🔄 FLOW IMPLEMENTASI

Phase 1: Basic Notification Setup

1. Create NotificationChannels.kt
2. Create WorkoutNotificationManager.kt dengan basic notification
3. Add permissions ke AndroidManifest.xml
4. Update ImFitApplication.kt

Phase 2: Service Integration

1. Create WorkoutService.kt sebagai foreground service
2. Create WorkoutReceiver.kt untuk handle actions
3. Update WorkoutDetailViewModel.kt untuk start/stop service
4. Register service dan receiver di manifest

Phase 3: Real-Time Timer Updates

1. Create data classes untuk timer updates
2. Update ActiveWorkoutViewModel.kt untuk emit timer updates
3. Connect ActiveWorkoutScreen.kt ke service updates
4. Implement real-time notification updates

Phase 4: Notification Actions

1. Add action buttons ke notification
2. Handle stop action dengan konfirmasi dialog
3. Add pause/resume functionality (opsional)
4. Test notification clicks dan deep linking

Phase 5: Testing & Polish

1. Test app background behavior
2. Test notification updates accuracy
3. Test service lifecycle (start, stop, restart)
4. Edge case handling (app killed, device reboot, etc.)

  ---
📦 DEPENDENCIES YANG DIPERLUKAN

Tidak ada dependencies tambahan!

Semua bisa diimplementasi dengan Android SDK standar:

- androidx.core.app.NotificationCompat
- androidx.core.app.NotificationManagerCompat
- android.app.Service (Foreground Service)
- kotlinx.coroutines.channels (untuk communication)

  ---

🎨 NOTIFICATION DESIGN

State 1: Workout Berjalan (Tidak Sedang Rest)

┌────────────────────────────────────────────────┐
│ 🏋️ Push Day                    [Stop] [Pause] │
│ ────────────────────────────────────────────── │
│ ⏱️ Elapsed: 00:15:32 │
│ ✅ Sets: 6/12 • 📊 Vol: 1,250 kg │
└────────────────────────────────────────────────┘

State 2: Sedang Rest Timer

┌────────────────────────────────────────────────┐
│ ⏸️ Rest Timer                    [Skip] [Stop]│
│ ────────────────────────────────────────────── │
│ ⏱️ 00:45 remaining │
│ 💪 Resting after: Barbell Bench Press │
└────────────────────────────────────────────────┘

State 3: Rest Selesai (Kembali ke Workout)

Kembali ke State 1 dengan elapsed time terupdate

  ---
🔔 NOTIFICATION PRIORITY & IMPORTANCE

- Importance: IMPORTANCE_HIGH (muncul di atas)
- Priority: PRIORITY_HIGH
- Category: Notification.CATEGORY_SERVICE
- Ongoing: true (tidak bisa di-swipe saat workout aktif)

  ---

⚠️ EDGE CASES YANG PERLU DITANGANI

1. App killed by system: Service harus auto-restart
2. Device reboot: Clear notification dan state
3. Multiple workouts started: Handle duplicate start requests
4. Notification permission denied: Fallback behavior untuk Android 13+
5. Service timeout: Handle foreground service timeout (FOREGROUND_SERVICE_DEFAULT_SHORT_TIMEOUT)
6. App update/uninstall: Clean up service dan notifications

  ---
📝 ADDITIONAL RESOURCES YANG DIPERLUKAN

1. String Resources (strings.xml)

  <!-- Notification -->
<string name="notification_workout_active">Workout Active</string>
<string name="notification_rest_timer">Rest Timer</string>
<string name="notification_stop">Stop</string>
<string name="notification_pause">Pause</string>
<string name="notification_resume">Resume</string>
<string name="notification_skip_rest">Skip</string>
<string name="notification_elapsed">Elapsed: %s</string>
<string name="notification_sets_completed">Sets: %d/%d</string>
<string name="notification_volume">Vol: %,.0f kg</string>
<string name="notification_rest_remaining">%d remaining</string>
<string name="notification_resting_after">Resting after: %s</string>

  <!-- Notification Channels -->
<string name="channel_workout_active">Active Workout</string>
<string name="channel_workout_rest">Rest Timer</string>
<string name="channel_workout_desc">Shows your active workout progress</string>

2. Drawable Resources

- Icon kecil untuk notification (monochrome, 24x24dp)
- Icon untuk action buttons

  ---

🧪 TESTING CHECKLIST

- Notification muncul saat start workout
- Elapsed time update real-time
- Rest timer muncul setelah completed set
- Rest timer countdown real-time
- Notification hilang setelah workout finish
- Notification hilang setelah workout cancel
- Tap notification buka ActiveWorkoutScreen
- Stop action berfungni dengan konfirmasi
- Notification tetap ada saat app di-background
- Service restart jika app killed
- Permission handling untuk Android 13+
- Multi-language support untuk notification text

  ---

📊 ESTIMASI IMPLEMENTASI

| Phase   | Tasks                    | Estimasi  |
  |---------|--------------------------|-----------|
| Phase 1 | Basic Notification Setup | 2-3 jam   |
| Phase 2 | Service Integration      | 3-4 jam   |
| Phase 3 | Real-Time Updates        | 4-5 jam   |
| Phase 4 | Actions & Polish         | 2-3 jam   |
| Phase 5 | Testing & Fixes          | 2-3 jam   |
| TOTAL   |                          | 13-18 jam |

  ---
🚀 RECOMMENDATIONS

1. Start dengan Phase 1 & 2: Dapatkan basic notification working dulu
2. Gunakan WorkManager untuk alternative jika Foreground Service terlalu kompleks
3. Consider Lifecycle-Aware Components: Gunakan androidx.lifecycle untuk better lifecycle management
4. Battery Optimization: Pastikan service tidak drain battery
5. Analytics: Track notification interactions untuk UX insights

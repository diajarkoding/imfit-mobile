# Dokumentasi Arsitektur IMFIT Mobile

## Informasi Arsitektur yang Dimiliki Proyek Saat Ini

### 1. Struktur Entitas Room (DAO + Entity)

#### 1.1 Entity yang Sudah Didefinisikan:

**UserEntity**
```kotlin
@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey val id: String,
    val name: String,
    val email: String,
    @ColumnInfo(name = "birth_date") val birthDate: String? = null,
    @ColumnInfo(name = "profile_photo_uri") val profilePhotoUri: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)
```

**ExerciseEntity**
```kotlin
@Entity(tableName = "exercises")
data class ExerciseEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "muscle_category_id") val muscleCategoryId: Int,
    val description: String,
    @ColumnInfo(name = "image_url") val imageUrl: String? = null,
    @ColumnInfo(name = "is_active") val isActive: Boolean = true,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)
```

**WorkoutTemplateEntity**
```kotlin
@Entity(tableName = "workout_templates")
data class WorkoutTemplateEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "user_id") val userId: String,
    val name: String,
    @ColumnInfo(name = "is_deleted") val isDeleted: Boolean = false,
    @ColumnInfo(name = "sync_status") val syncStatus: String = SyncStatus.PENDING_SYNC.name,
    @ColumnInfo(name = "pending_operation") val pendingOperation: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)
```

**WorkoutLogEntity**
```kotlin
@Entity(tableName = "workout_logs")
data class WorkoutLogEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "user_id") val userId: String,
    @ColumnInfo(name = "template_id") val templateId: String?,
    @ColumnInfo(name = "template_name") val templateName: String,
    val date: Long,
    @ColumnInfo(name = "start_time") val startTime: Long,
    @ColumnInfo(name = "end_time") val endTime: Long,
    @ColumnInfo(name = "total_volume") val totalVolume: Float,
    @ColumnInfo(name = "total_sets") val totalSets: Int = 0,
    @ColumnInfo(name = "total_reps") val totalReps: Int = 0,
    @ColumnInfo(name = "sync_status") val syncStatus: String = SyncStatus.PENDING_SYNC.name,
    @ColumnInfo(name = "pending_operation") val pendingOperation: String? = null,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)
```

**TemplateExerciseEntity**
```kotlin
@Entity(
    tableName = "template_exercises",
    primaryKeys = ["template_id", "exercise_id"]
)
data class TemplateExerciseEntity(
    @ColumnInfo(name = "template_id") val templateId: String,
    @ColumnInfo(name = "exercise_id") val exerciseId: String,
    @ColumnInfo(name = "order_index") val orderIndex: Int,
    val sets: Int,
    val reps: Int,
    @ColumnInfo(name = "rest_seconds") val restSeconds: Int,
    @ColumnInfo(name = "sync_status") val syncStatus: String = SyncStatus.PENDING_SYNC.name,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)
```

**ExerciseLogEntity**
```kotlin
@Entity(
    tableName = "exercise_logs",
    primaryKeys = ["workout_log_id", "exercise_id"]
)
data class ExerciseLogEntity(
    @ColumnInfo(name = "workout_log_id") val workoutLogId: String,
    @ColumnInfo(name = "exercise_id") val exerciseId: String,
    @ColumnInfo(name = "exercise_name") val exerciseName: String,
    @ColumnInfo(name = "muscle_category") val muscleCategory: String,
    @ColumnInfo(name = "order_index") val orderIndex: Int,
    @ColumnInfo(name = "total_volume") val totalVolume: Float,
    @ColumnInfo(name = "total_sets") val totalSets: Int,
    @ColumnInfo(name = "total_reps") val totalReps: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)
```

**WorkoutSetEntity**
```kotlin
@Entity(tableName = "workout_sets")
data class WorkoutSetEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "exercise_log_id") val exerciseLogId: String,
    @ColumnInfo(name = "workout_log_id") val workoutLogId: String,
    @ColumnInfo(name = "exercise_id") val exerciseId: String,
    @ColumnInfo(name = "set_number") val setNumber: Int,
    val weight: Float,
    val reps: Int,
    @ColumnInfo(name = "is_completed") val isCompleted: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)
```

**ActiveSessionEntity**
```kotlin
@Entity(tableName = "active_sessions")
data class ActiveSessionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "user_id") val userId: String,
    @ColumnInfo(name = "template_id") val templateId: String,
    @ColumnInfo(name = "template_name") val templateName: String,
    @ColumnInfo(name = "start_time") val startTime: Long,
    @ColumnInfo(name = "current_exercise_index") val currentExerciseIndex: Int = 0,
    @ColumnInfo(name = "session_data_json") val sessionDataJson: String,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)
```

#### 1.2 Relasi Antar Tabel:
- **users** 1:N → **workout_templates** (user memiliki banyak template)
- **users** 1:N → **workout_logs** (user memiliki banyak workout log)
- **workout_templates** 1:N → **template_exercises** (template memiliki banyak exercise)
- **exercises** 1:N → **template_exercises** (exercise bisa ada di banyak template)
- **workout_logs** 1:N → **exercise_logs** (workout log memiliki banyak exercise log)
- **exercise_logs** 1:N → **workout_sets** (exercise log memiliki banyak set)
- **muscle_categories** 1:N → **exercises** (kategori memiliki banyak exercise)
- **users** 1:1 → **active_sessions** (user memiliki satu active session)

#### 1.3 Contoh DAO (Insert/Update/Delete/Query):

**WorkoutTemplateDao**
```kotlin
@Dao
interface WorkoutTemplateDao {
    // Query
    @Query("SELECT * FROM workout_templates WHERE user_id = :userId AND is_deleted = 0")
    fun getTemplatesByUser(userId: String): Flow<List<WorkoutTemplateEntity>>

    // Insert
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTemplate(template: WorkoutTemplateEntity)

    // Update
    @Update
    suspend fun updateTemplate(template: WorkoutTemplateEntity)

    // Soft Delete
    @Query("UPDATE workout_templates SET is_deleted = 1, sync_status = :syncStatus, pending_operation = 'DELETE' WHERE id = :id")
    suspend fun softDeleteTemplate(id: String, syncStatus: String)

    // Sync-aware queries
    @Query("SELECT * FROM workout_templates WHERE pending_operation IS NOT NULL")
    suspend fun getPendingSyncTemplates(): List<WorkoutTemplateEntity>

    @Query("UPDATE workout_templates SET sync_status = :syncStatus, pending_operation = NULL WHERE id = :id")
    suspend fun markAsSynced(id: String, syncStatus: String)
}
```

**WorkoutLogDao**
```kotlin
@Dao
interface WorkoutLogDao {
    // Query
    @Query("SELECT * FROM workout_logs WHERE user_id = :userId AND deleted_at IS NULL ORDER BY date DESC")
    fun getActiveWorkoutLogsByUser(userId: String): Flow<List<WorkoutLogEntity>>

    // Insert
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWorkoutLog(workoutLog: WorkoutLogEntity)

    // Soft Delete
    @Query("""
        UPDATE workout_logs
        SET deleted_at = :timestamp,
            sync_status = 'PENDING_SYNC',
            pending_operation = 'DELETE',
            updated_at = :timestamp
        WHERE id = :logId
    """)
    suspend fun softDeleteLog(logId: String, timestamp: Long = System.currentTimeMillis())

    // Sync operations
    @Query("SELECT * FROM workout_logs WHERE sync_status = 'PENDING_SYNC'")
    suspend fun getPendingLogs(): List<WorkoutLogEntity>

    @Query("UPDATE workout_logs SET sync_status = 'SYNCED', pending_operation = NULL WHERE id = :id")
    suspend fun markAsSynced(id: String)
}
```

### 2. Alur SyncManager Saat Ini

#### 2.1 SyncManager Implementation:
```kotlin
@Singleton
class SyncManager @Inject constructor(
    private val networkMonitor: NetworkMonitor,
    private val supabaseClient: SupabaseClient,
    private val workoutTemplateDao: WorkoutTemplateDao,
    private val templateExerciseDao: TemplateExerciseDao,
    private val workoutLogDao: WorkoutLogDao
) {
    init {
        // Observer network changes
        scope.launch {
            networkMonitor.networkStatus.collectLatest { isOnline ->
                if (isOnline) {
                    syncAll()
                }
            }
        }
    }

    suspend fun syncAll() {
        if (!networkMonitor.isOnline) return

        try {
            syncPendingTemplates()
            syncPendingWorkoutLogs()
        } catch (e: Exception) {
            // Handle error
        }
    }
}
```

#### 2.2 Pending Operations Tracking:
- **sync_status**: SYNCED, PENDING_SYNC, SYNC_FAILED
- **pending_operation**: CREATE, UPDATE, DELETE
- Setiap entity memiliki field ini untuk tracking

#### 2.3 Error Handling:
- Try-catch di setiap operasi sync
- Update sync_status ke SYNC_FAILED jika error
- Logging error dengan detail

#### 2.4 Network Monitor Trigger:
- Menggunakan `NetworkMonitor` class untuk observe connectivity
- Otomatis trigger sync saat online
- Flow-based reactive network monitoring

### 3. Flow Authentication + Storage Token

#### 3.1 Token Storage:
```kotlin
@Singleton
class SessionManager @Inject constructor(@ApplicationContext private val context: Context) {
    private val authTokenKey = stringPreferencesKey("auth_token")

    suspend fun saveAuthToken(token: String) {
        context.dataStore.edit { preferences ->
            preferences[authTokenKey] = token
        }
    }

    fun getAuthToken(): Flow<String?> {
        return context.dataStore.data.map { preferences ->
            preferences[authTokenKey]
        }
    }
}
```

#### 3.2 Supabase Auth Configuration:
```kotlin
@InstallIn(SingletonComponent::class)
object SupabaseModule {
    @Provides
    @Singleton
    fun provideSupabaseClient(): SupabaseClient {
        return createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_ANON_KEY
        ) {
            install(Auth) {
                flowType = FlowType.PKCE
                scheme = "imfit"
                host = "login-callback"
            }
            install(Postgrest)
            install(Storage)
        }
    }
}
```

#### 3.3 Token Management:
- Menggunakan Android DataStore untuk persist token
- Flow-based untuk reactive token observation
- PKCE flow untuk security
- Supabase auto-handle refresh token

### 4. Arsitektur UI Terkait Sync

#### 4.1 UI Architecture:
- **Jetpack Compose**: 100% Compose-based UI
- **MVVM**: Model-View-ViewModel pattern
- **State Management**: StateFlow dan Compose state

#### 4.2 Sync Indicator Placement:
- Saat ini belum ada sync indicator implementasi
- Potensi placement:
  - Top bar (MainScreen)
  - Profile screen
  - Floating indicator di corner

#### 4.3 State Management:
```kotlin
// ViewModel example
class HomeViewModel @Inject constructor(
    private val syncManager: SyncManager
) : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    // Sync status observation
    val syncStatus: Flow<SyncStatus> = syncManager.syncStatus
}
```

#### 4.4 Reactive UI Update:
- Menggunakan `collectAsState()` untuk observe Flow dari ViewModel
- UI otomatis update saat sync status berubah

### 5. Supabase Schema Saat Ini

#### 5.1 Struktur Tabel Supabase:
```sql
-- Primary Tables
profiles                    -- Extended user data
muscle_categories           -- Exercise categories
exercises                   -- Exercise library
workout_templates           -- User workout plans
template_exercises          -- Exercises in templates
workout_logs                -- Completed workouts
exercise_logs               -- Exercises in workout
workout_sets                -- Individual sets
active_sessions             -- Active workout sessions
```

#### 5.2 Key Schema Features:
- **UUID Primary Keys**: Konsisten dengan auth.users
- **Row Level Security (RLS)**: Aktif di semua tabel
- **Soft Delete**: Menggunakan `is_deleted` flag
- **Timestamp Tracking**: `created_at` dan `updated_at`
- **JSON Fields**: Untuk data kompleks (session_data)

#### 5.3 Sync Mapping:
- Room entities → Supabase tables dengan 1:1 mapping
- DTO classes untuk API communication
- Converter untuk timezone dan data type mapping

## 2. Informasi Tambahan yang Bermanfaat

### 2.1 Target Perilaku Aplikasi:

#### Edit Workout History Offline:
- **SUDAH DIDUKUNG**: WorkoutLog memiliki soft delete dengan `deleted_at`
- Bisa edit log dan akan sync saat online
- Conflict resolution perlu ditentukan (last-write-wins?)

#### Multi-Device Template Editing:
- **PERLU CONFLICT RESOLUTION**: Template bisa di edit di multiple device
- Perlu implementasi:
  - Versioning dengan `updated_at` timestamps
  - Conflict detection
  - Merge strategy atau user selection

#### Exercise Library Mutability:
- **IMMUTABLE**: Exercise library bersifat read-only dari client
- Updates hanya dari admin/backend
- Bisa di-sync dengan app updates

### 2.2 Tingkat Sensitivitas Data:

#### GDPR Compliance Needs:
- **BERLAKU**: User data (workout history, profile)
- Perlu implementasi:
  - Data export functionality
  - Right to be forgotten (hard delete)
  - Data minimization
  - Explicit consent

#### Local Encryption:
- **REKOMENDASI**: Encrypt sensitive data di device
- Workout logs, user profile
- Android Keystore untuk key management

### 2.3 Scale Target:

#### Large Data Handling:
- **1 user dengan 100K workout logs**:
  - Perlu pagination
  - Index optimization
  - Archive old data

#### Database Size Management:
- Ukuran database bisa besar dengan workout logs
- Perlu implementasi:
  - Data archival (logs > 1 tahun)
  - Compression untuk JSON data
  - Pruning unnecessary data

#### Sync Performance:
- Asynchronous sync sudah tepat
- Perlu tambahan:
  - Delta sync (sync hanya perubahan)
  - Batching untuk large operations
  - Background sync dengan WorkManager

---

## Rekomendasi Implementasi Selanjutnya

### 1. Conflict Resolution Strategy:
```kotlin
enum class ConflictResolution {
    LAST_WRITE_WINS,
    USER_SELECTS,
    MERGE,
    SERVER_WINS
}
```

### 2. Atomic Sync Unit:
```kotlin
data class SyncUnit(
    val entityType: String,
    val entityId: String,
    val operations: List<SyncOperation>,
    val dependencies: List<String>
)
```

### 3. Backup Implementation:
```kotlin
interface BackupManager {
    suspend fun createBackup(): BackupFile
    suspend fun restoreBackup(backupFile: BackupFile)
    suspend fun scheduleAutoBackup()
}
```

### 4. Sync Indicator Component:
```kotlin
@Composable
fun SyncIndicator(
    syncStatus: SyncStatus,
    pendingCount: Int,
    modifier: Modifier = Modifier
) {
    // UI implementation
}
```

Dokumentasi ini mencakup semua arsitektur yang sudah ada dan gap yang perlu diisi untuk implementasi offline-first enhancement.
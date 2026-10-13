# IMFIT - Migrasi dari Offline-First ke Online-First

## Daftar Isi
1. [Executive Summary](#1-executive-summary)
2. [Analisis Arsitektur Saat Ini (Offline-First)](#2-analisis-arsitektur-saat-ini-offline-first)
3. [Perbedaan Offline-First vs Online-First](#3-perbedaan-offline-first-vs-online-first)
4. [Dampak Migrasi](#4-dampak-migrasi)
5. [Planning Migrasi ke Online-First](#5-planning-migrasi-ke-online-first)
6. [Fase Implementasi](#6-fase-implementasi)
7. [File dan Komponen yang Terdampak](#7-file-dan-komponen-yang-terdampak)
8. [Risiko dan Mitigasi](#8-risiko-dan-mitigasi)
9. [Rollback Strategy](#9-rollback-strategy)
10. [Timeline Estimasi](#10-timeline-estimasi)

---

## 1. Executive Summary

### Status Saat Ini
Aplikasi IMFIT saat ini menggunakan arsitektur **Offline-First** dengan karakteristik:
- Data disimpan terlebih dahulu ke Room Database lokal
- Sinkronisasi dilakukan secara asinkron ke Supabase
- Aplikasi dapat berfungsi tanpa koneksi internet
- Mekanisme sync dengan `syncStatus` dan `pendingOperation` pada setiap entity

### Tujuan Migrasi
Migrasi ke arsitektur **Online-First** dengan karakteristik:
- Data langsung dikirim ke Supabase sebagai source of truth
- Room Database hanya sebagai cache untuk performa
- Aplikasi membutuhkan koneksi internet untuk operasi write
- Simplifikasi arsitektur dengan menghapus sync mechanism

### Alasan Migrasi
1. **Simplifikasi Kode**: Menghapus kompleksitas sync mechanism
2. **Konsistensi Data**: Menghindari konflik data antar device
3. **Real-time Updates**: Data selalu up-to-date dari server
4. **Maintenance Lebih Mudah**: Tidak perlu handle edge case offline sync

---

## 2. Analisis Arsitektur Saat Ini (Offline-First)

### 2.1 Struktur Data Layer

```
┌─────────────────────────────────────────────────────────────┐
│                    OFFLINE-FIRST ARCHITECTURE               │
│                                                             │
│  ┌───────────┐    ┌──────────────┐    ┌─────────────┐      │
│  │    UI     │───▶│  Repository  │───▶│  Room DAO   │      │
│  │ (Compose) │    │    Impl      │    │  (Local)    │      │
│  └───────────┘    └──────┬───────┘    └──────┬──────┘      │
│                          │                   │              │
│                          ▼                   │              │
│                   ┌──────────────┐           │              │
│                   │ SyncManager  │◀──────────┘              │
│                   └──────┬───────┘                          │
│                          │                                  │
│                          ▼                                  │
│                   ┌──────────────┐                          │
│                   │  Supabase    │                          │
│                   │  (Remote)    │                          │
│                   └──────────────┘                          │
└─────────────────────────────────────────────────────────────┘
```

### 2.2 Entity dengan Sync Tracking

**Entity yang memiliki `syncStatus` dan `pendingOperation`:**

| Entity | File | Sync Fields |
|--------|------|-------------|
| WorkoutTemplateEntity | `data/local/entity/WorkoutTemplateEntity.kt` | syncStatus, pendingOperation |
| WorkoutLogEntity | `data/local/entity/WorkoutLogEntity.kt` | syncStatus, pendingOperation |
| TemplateExerciseEntity | `data/local/entity/TemplateExerciseEntity.kt` | syncStatus, pendingOperation |
| ExerciseLogEntity | `data/local/entity/ExerciseLogEntity.kt` | syncStatus, pendingOperation |
| WorkoutSetEntity | `data/local/entity/WorkoutSetEntity.kt` | syncStatus, pendingOperation |
| UserEntity | `data/local/entity/UserEntity.kt` | syncStatus |

### 2.3 Sync Mechanism Components

```kotlin
// SyncStatus enum
enum class SyncStatus {
    SYNCED,         // Data sudah sinkron
    PENDING_SYNC,   // Menunggu sinkronisasi
    SYNC_FAILED     // Gagal sinkronisasi
}

// PendingOperation enum
enum class PendingOperation {
    CREATE,  // Data baru
    UPDATE,  // Data diupdate
    DELETE   // Data dihapus
}
```

### 2.4 Flow Data Saat Ini

**Create Template (Offline-First):**
```
1. User creates template
2. Repository saves to Room with syncStatus=PENDING_SYNC, pendingOperation=CREATE
3. Repository tries to sync to Supabase (non-blocking)
4. If success: update syncStatus=SYNCED
5. If fail: keep PENDING_SYNC for later retry
6. SyncManager periodically syncs pending data
```

---

## 3. Perbedaan Offline-First vs Online-First

### 3.1 Perbandingan Arsitektur

| Aspek | Offline-First (Saat Ini) | Online-First (Target) |
|-------|--------------------------|----------------------|
| **Source of Truth** | Room Database (Local) | Supabase (Remote) |
| **Write Operation** | Local first, sync later | Remote first, cache local |
| **Read Operation** | From local cache | From remote, fallback cache |
| **Network Required** | Tidak untuk write | Ya untuk write |
| **Sync Complexity** | Tinggi | Rendah/Tidak ada |
| **Conflict Resolution** | Perlu dihandle | Tidak perlu |
| **Data Consistency** | Eventually consistent | Immediately consistent |

### 3.2 Target Arsitektur Online-First

```
┌─────────────────────────────────────────────────────────────┐
│                    ONLINE-FIRST ARCHITECTURE                │
│                                                             │
│  ┌───────────┐    ┌──────────────┐    ┌─────────────┐      │
│  │    UI     │───▶│  Repository  │───▶│  Supabase   │      │
│  │ (Compose) │    │    Impl      │    │  (Remote)   │      │
│  └───────────┘    └──────┬───────┘    └─────────────┘      │
│                          │                                  │
│                          ▼                                  │
│                   ┌──────────────┐                          │
│                   │  Room DAO    │  (Cache Only)            │
│                   │  (Local)     │                          │
│                   └──────────────┘                          │
└─────────────────────────────────────────────────────────────┘
```

### 3.3 Flow Data Target (Online-First)

**Create Template (Online-First):**
```
1. User creates template
2. Check network connectivity
3. If online:
   a. Repository sends to Supabase directly
   b. On success: cache to Room for offline read
   c. On fail: show error to user
4. If offline:
   a. Show error "Requires internet connection"
   b. Optional: queue for later (simplified)
```

---

## 4. Dampak Migrasi

### 4.1 File yang Perlu Dimodifikasi

#### A. Data Layer - Entity (Hapus sync fields)

| File | Perubahan |
|------|-----------|
| `data/local/entity/WorkoutTemplateEntity.kt` | Hapus `syncStatus`, `pendingOperation` |
| `data/local/entity/WorkoutLogEntity.kt` | Hapus `syncStatus`, `pendingOperation` |
| `data/local/entity/TemplateExerciseEntity.kt` | Hapus `syncStatus`, `pendingOperation` |
| `data/local/entity/ExerciseLogEntity.kt` | Hapus `syncStatus`, `pendingOperation` |
| `data/local/entity/WorkoutSetEntity.kt` | Hapus `syncStatus`, `pendingOperation` |
| `data/local/entity/UserEntity.kt` | Hapus `syncStatus` |

#### B. Data Layer - DAO (Hapus sync queries)

| File | Perubahan |
|------|-----------|
| `data/local/dao/WorkoutTemplateDao.kt` | Hapus `getPendingSyncTemplates()`, `markAsSynced()`, `updateSyncStatus()` |
| `data/local/dao/WorkoutLogDao.kt` | Hapus `getPendingLogs()`, `markAsSynced()`, `updateSyncStatus()` |
| `data/local/dao/ExerciseLogDao.kt` | Hapus `getPendingExerciseLogs()`, `markAsSynced()` |
| `data/local/dao/WorkoutSetDao.kt` | Hapus `getPendingWorkoutSets()`, `markAsSynced()` |
| `data/local/dao/UserDao.kt` | Hapus sync-related queries |

#### C. Data Layer - Repository (Ubah flow)

| File | Perubahan |
|------|-----------|
| `data/repository/WorkoutRepositoryImpl.kt` | Ubah dari local-first ke remote-first |
| `data/repository/ExerciseRepositoryImpl.kt` | Ubah fetch strategy |
| `data/repository/AuthRepositoryImpl.kt` | Simplifikasi user data handling |

#### D. Sync Layer (Hapus atau Simplifikasi)

| File | Perubahan |
|------|-----------|
| `data/sync/SyncManager.kt` | **HAPUS** atau simplifikasi menjadi CacheManager |
| `data/sync/SyncState.kt` | **HAPUS** |
| `data/sync/SyncPreferences.kt` | **HAPUS** atau ubah jadi CachePreferences |
| `data/local/sync/SyncStatus.kt` | **HAPUS** |

#### E. Presentation Layer (Hapus sync UI)

| File | Perubahan |
|------|-----------|
| `presentation/components/common/SyncIndicator.kt` | **HAPUS** atau ubah jadi ConnectionIndicator |
| `presentation/components/common/SyncProgressDialog.kt` | **HAPUS** |
| `presentation/ui/home/HomeViewModel.kt` | Hapus syncState observation |
| Semua ViewModel | Hapus sync-related logic |

#### F. DI Module

| File | Perubahan |
|------|-----------|
| `di/DatabaseModule.kt` | Update database version, migration |
| `di/RepositoryModule.kt` | Hapus SyncManager injection |

### 4.2 Fitur yang Terdampak

| Fitur | Dampak | Solusi |
|-------|--------|--------|
| **Create Template** | Butuh internet | Show error jika offline |
| **Edit Template** | Butuh internet | Show error jika offline |
| **Delete Template** | Butuh internet | Show error jika offline |
| **Start Workout** | Tetap bisa offline | Active session di local |
| **Finish Workout** | Butuh internet | Queue untuk sync nanti |
| **View History** | Bisa dari cache | Stale data indicator |
| **View Templates** | Bisa dari cache | Stale data indicator |
| **Exercise Browser** | Bisa dari cache | Refresh saat online |

### 4.3 Database Migration

```kotlin
// Migration dari version X ke X+1
val MIGRATION_X_Y = object : Migration(X, Y) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // Hapus kolom sync_status dan pending_operation
        // Karena SQLite tidak support DROP COLUMN langsung,
        // perlu recreate table
        
        // 1. Rename table lama
        database.execSQL("ALTER TABLE workout_templates RENAME TO workout_templates_old")
        
        // 2. Create table baru tanpa sync fields
        database.execSQL("""
            CREATE TABLE workout_templates (
                id TEXT PRIMARY KEY NOT NULL,
                user_id TEXT NOT NULL,
                name TEXT NOT NULL,
                is_deleted INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
        """)
        
        // 3. Copy data
        database.execSQL("""
            INSERT INTO workout_templates (id, user_id, name, is_deleted, created_at, updated_at)
            SELECT id, user_id, name, is_deleted, created_at, updated_at FROM workout_templates_old
        """)
        
        // 4. Drop table lama
        database.execSQL("DROP TABLE workout_templates_old")
        
        // Repeat untuk table lain...
    }
}
```

---

## 5. Planning Migrasi ke Online-First

### 5.1 Strategi Migrasi

**Pendekatan: Incremental Migration**
- Migrasi per-fitur, bukan big bang
- Maintain backward compatibility selama transisi
- Feature flag untuk switch antara offline-first dan online-first

### 5.2 Prioritas Migrasi

```
Phase 1: Foundation
├── Buat abstraksi NetworkAwareRepository
├── Implement connection state handling
└── Add offline error handling UI

Phase 2: Read Operations
├── Migrate Exercise fetching (read-only)
├── Migrate Template fetching
└── Migrate WorkoutLog fetching

Phase 3: Write Operations
├── Migrate Template create/update/delete
├── Migrate WorkoutLog create
└── Handle Active Workout Session

Phase 4: Cleanup
├── Remove SyncManager
├── Remove sync fields dari entities
├── Database migration
└── Remove sync UI components
```

---

## 6. Fase Implementasi

### Phase 1: Foundation (Week 1-2)

#### 1.1 Buat NetworkAwareRepository Base Class

```kotlin
// domain/repository/NetworkAwareRepository.kt
abstract class NetworkAwareRepository(
    private val networkMonitor: NetworkMonitor
) {
    protected suspend fun <T> withNetwork(
        onOnline: suspend () -> T,
        onOffline: () -> T? = { null },
        cacheKey: String? = null
    ): Result<T> {
        return if (networkMonitor.isOnline) {
            try {
                Result.success(onOnline())
            } catch (e: Exception) {
                Result.failure(e)
            }
        } else {
            onOffline()?.let { Result.success(it) }
                ?: Result.failure(NoNetworkException())
        }
    }
}

class NoNetworkException : Exception("No internet connection. Please check your network.")
```

#### 1.2 Update UI untuk Handle Offline State

```kotlin
// presentation/components/common/OfflineAwareContent.kt
@Composable
fun OfflineAwareContent(
    isOnline: Boolean,
    onRetry: () -> Unit,
    content: @Composable () -> Unit
) {
    if (!isOnline) {
        OfflineBanner(onRetry = onRetry)
    }
    content()
}

@Composable
fun OfflineBanner(onRetry: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("You're offline", color = MaterialTheme.colorScheme.onErrorContainer)
            TextButton(onClick = onRetry) {
                Text("Retry")
            }
        }
    }
}
```

### Phase 2: Migrate Read Operations (Week 3-4)

#### 2.1 Update WorkoutRepository untuk Read

```kotlin
// data/repository/WorkoutRepositoryImpl.kt

override suspend fun getTemplates(userId: String): List<WorkoutTemplate> {
    return try {
        // Try to fetch from Supabase first (online-first)
        val remoteTemplates = fetchTemplatesFromSupabase(userId)
        
        // Cache to Room for offline read
        cacheTemplatesToRoom(remoteTemplates)
        
        remoteTemplates
    } catch (e: Exception) {
        Log.w(TAG, "Failed to fetch from remote, using cache: ${e.message}")
        // Fallback to cache
        getTemplatesFromCache(userId)
    }
}

private suspend fun fetchTemplatesFromSupabase(userId: String): List<WorkoutTemplate> {
    val response = supabaseClient.postgrest
        .from("workout_templates")
        .select() {
            filter { 
                eq("user_id", userId)
                eq("is_deleted", false)
            }
        }
        .decodeList<WorkoutTemplateDto>()
    
    return response.map { it.toDomain() }
}

private suspend fun getTemplatesFromCache(userId: String): List<WorkoutTemplate> {
    return workoutTemplateDao.getTemplatesByUserList(userId)
        .map { it.toDomain() }
}
```

### Phase 3: Migrate Write Operations (Week 5-6)

#### 3.1 Update Create Template

```kotlin
override suspend fun createTemplate(
    userId: String, 
    name: String, 
    exercises: List<TemplateExercise>
): Result<WorkoutTemplate> {
    // Check network first
    if (!networkMonitor.isOnline) {
        return Result.failure(NoNetworkException())
    }
    
    return try {
        val templateId = UUID.randomUUID().toString()
        
        // Create on Supabase first
        val templateDto = CreateTemplateDto(
            id = templateId,
            userId = userId,
            name = name
        )
        supabaseClient.postgrest.from("workout_templates").insert(templateDto)
        
        // Insert exercises
        exercises.forEachIndexed { index, exercise ->
            val exerciseDto = TemplateExerciseDto(
                templateId = templateId,
                exerciseId = exercise.exercise.id,
                orderIndex = index,
                sets = exercise.sets,
                reps = exercise.reps,
                restSeconds = exercise.restSeconds
            )
            supabaseClient.postgrest.from("template_exercises").insert(exerciseDto)
        }
        
        // Cache locally (tanpa sync fields)
        val entity = WorkoutTemplateEntity(
            id = templateId,
            userId = userId,
            name = name,
            isDeleted = false,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        workoutTemplateDao.insertTemplate(entity)
        
        Result.success(WorkoutTemplate(
            id = templateId,
            userId = userId,
            name = name,
            exercises = exercises
        ))
    } catch (e: Exception) {
        Log.e(TAG, "Failed to create template: ${e.message}", e)
        Result.failure(e)
    }
}
```

#### 3.2 Handle Active Workout (Special Case)

Active workout session tetap disimpan di local karena:
- Durasi workout bisa panjang
- Butuh real-time update (timer, sets)
- Sync ke server saat finish

```kotlin
override suspend fun finishWorkout(): Result<WorkoutLog> {
    val session = activeSession ?: return Result.failure(Exception("No active session"))
    
    // Check network for finishing
    if (!networkMonitor.isOnline) {
        // Save to local with "pending upload" flag
        saveWorkoutLogLocally(session, pendingUpload = true)
        return Result.failure(NoNetworkException("Workout saved locally. Will sync when online."))
    }
    
    return try {
        // Upload to Supabase
        val workoutLog = uploadWorkoutToSupabase(session)
        
        // Cache locally
        cacheWorkoutLogLocally(workoutLog)
        
        // Clear active session
        activeSessionDao.deleteSession(session.userId)
        activeSession = null
        
        Result.success(workoutLog)
    } catch (e: Exception) {
        // Save locally for retry
        saveWorkoutLogLocally(session, pendingUpload = true)
        Result.failure(e)
    }
}
```

### Phase 4: Cleanup (Week 7-8)

#### 4.1 Remove SyncManager dan Related Files

```bash
# Files to delete
rm app/src/main/java/com/diajarkoding/imfit/data/sync/SyncManager.kt
rm app/src/main/java/com/diajarkoding/imfit/data/sync/SyncState.kt
rm app/src/main/java/com/diajarkoding/imfit/data/sync/SyncPreferences.kt
rm app/src/main/java/com/diajarkoding/imfit/data/local/sync/SyncStatus.kt
rm app/src/main/java/com/diajarkoding/imfit/presentation/components/common/SyncIndicator.kt
rm app/src/main/java/com/diajarkoding/imfit/presentation/components/common/SyncProgressDialog.kt
```

#### 4.2 Database Migration Script

Increment database version dan implement migration untuk:
1. Hapus kolom `sync_status` dari semua table
2. Hapus kolom `pending_operation` dari semua table
3. Cleanup orphaned data

---

## 7. File dan Komponen yang Terdampak

### 7.1 Ringkasan File Changes

| Kategori | File Count | Action |
|----------|------------|--------|
| Entities | 6 | Modify (hapus sync fields) |
| DAOs | 5 | Modify (hapus sync queries) |
| Repositories | 3 | Modify (ubah flow) |
| Sync Components | 4 | Delete |
| UI Components | 2 | Delete/Modify |
| ViewModels | ~12 | Modify |
| DI Modules | 2 | Modify |

### 7.2 Detail Perubahan per File

[Lihat Section 4.1 untuk detail lengkap]

---

## 8. Risiko dan Mitigasi

| Risiko | Dampak | Mitigasi |
|--------|--------|----------|
| Data loss saat migrasi | Tinggi | Backup database sebelum migrasi |
| User tidak bisa workout offline | Medium | Tetap simpan active session lokal |
| Breaking change untuk existing users | Tinggi | Database migration yang proper |
| Performance degradation | Medium | Implement caching strategy |
| Network dependency | Medium | Good error handling & offline indicator |

---

## 9. Rollback Strategy

### 9.1 Feature Flag Approach

```kotlin
object FeatureFlags {
    val USE_ONLINE_FIRST = BuildConfig.ONLINE_FIRST_ENABLED
}

// Di Repository
if (FeatureFlags.USE_ONLINE_FIRST) {
    // Online-first logic
} else {
    // Offline-first logic (legacy)
}
```

### 9.2 Rollback Steps
1. Disable feature flag
2. Release hotfix
3. Data sudah aman karena masih ada di Room

---

## 10. Timeline Estimasi

| Phase | Durasi | Deliverables |
|-------|--------|--------------|
| Phase 1: Foundation | 2 minggu | NetworkAwareRepository, Offline UI |
| Phase 2: Read Migration | 2 minggu | All read operations online-first |
| Phase 3: Write Migration | 2 minggu | All write operations online-first |
| Phase 4: Cleanup | 2 minggu | Remove sync, DB migration |
| Testing & QA | 1 minggu | Integration testing |
| **Total** | **9 minggu** | Full online-first migration |

---

## Kesimpulan

Migrasi dari offline-first ke online-first akan menyederhanakan arsitektur aplikasi secara signifikan, mengurangi kompleksitas sync mechanism, dan memastikan data consistency. Namun, perlu diperhatikan bahwa migrasi ini membutuhkan:

1. **Perencanaan matang** untuk database migration
2. **Testing menyeluruh** untuk memastikan tidak ada data loss
3. **UX yang baik** untuk handle kondisi offline
4. **Rollback strategy** jika terjadi masalah

Dengan mengikuti fase-fase implementasi yang telah direncanakan, migrasi dapat dilakukan secara incremental dan aman.

---

*Dokumen ini dibuat pada: 7 Januari 2026*
*Branch: online-first*

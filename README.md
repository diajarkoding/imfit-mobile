# IMFIT - Aplikasi Manajemen Workout & Fitness

<div align="center">

![IMFIT Logo](https://via.placeholder.com/200x200?text=IMFIT)

**Aplikasi Android Modern untuk Tracking Workout dan Fitness**

[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-purple.svg)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-2025.09.00-blue.svg)](https://developer.android.com/jetpack/compose)
[![Android](https://img.shields.io/badge/Android-7.0+-green.svg)](https://developer.android.com)
[![Architecture](https://img.shields.io/badge/Architecture-Clean%20MVVM-orange.svg)](https://developer.android.com/topic/architecture)

</div>

---

## Dokumentasi Terkait

| Dokumen | Deskripsi |
|---------|-----------|
| **[README.md](./README.md)** | Dokumentasi utama aplikasi mobile (dokumen ini) |
| **[planning_db.md](./planning_db.md)** | Perencanaan Backend API & Development Roadmap |
| **[schema_db.md](./schema_db.md)** | Database Schema & SQL Migration Scripts |

---

## Daftar Isi

1. [Gambaran Umum](#1-gambaran-umum)
2. [Fitur Aplikasi](#2-fitur-aplikasi)
3. [Teknologi yang Digunakan](#3-teknologi-yang-digunakan)
4. [Arsitektur Aplikasi](#4-arsitektur-aplikasi)
5. [Struktur Proyek](#5-struktur-proyek)
6. [Modul dan Komponen](#6-modul-dan-komponen)
7. [Sistem Navigasi](#7-sistem-navigasi)
8. [Design System dan Theming](#8-design-system-dan-theming)
9. [State Management](#9-state-management)
10. [Data Layer](#10-data-layer)
11. [Dependency Injection](#11-dependency-injection)
12. [Panduan Instalasi](#12-panduan-instalasi)
13. [Panduan Pengembangan](#13-panduan-pengembangan)
14. [Konfigurasi Build](#14-konfigurasi-build)

---

## 1. Gambaran Umum

**IMFIT** adalah aplikasi Android komprehensif untuk manajemen workout dan fitness yang dikembangkan menggunakan teknologi modern Android development. Aplikasi ini membantu pengguna untuk:

- Merencanakan dan mengatur program latihan
- Melacak progress workout harian
- Menyimpan riwayat latihan dengan statistik lengkap
- Mengelola koleksi exercise berdasarkan kategori otot

### Informasi Proyek

| Atribut | Nilai |
|---------|-------|
| **Package Name** | `com.diajarkoding.imfit` |
| **Min SDK** | 24 (Android 7.0 Nougat) |
| **Target SDK** | 36 |
| **Compile SDK** | 36 |
| **Kotlin Version** | 2.0.21 |
| **Compose BOM** | 2025.09.00 |
| **AGP Version** | 8.13.0 |

### Prerequisites

- Android Studio Ladybug atau lebih baru
- JDK 11 atau lebih baru
- Android SDK 36
- Gradle 8.x

---

## 2. Fitur Aplikasi

### 2.1 Autentikasi (Auth)

| Fitur | Deskripsi |
|-------|-----------|
| **Login** | Autentikasi dengan email/username dan password |
| **Register** | Pendaftaran akun baru dengan validasi lengkap |
| **Session Management** | Token-based authentication dengan DataStore |
| **Auto Login** | Remember me functionality |

### 2.2 Home Dashboard

| Fitur | Deskripsi |
|-------|-----------|
| **Overview** | Ringkasan aktivitas workout terbaru |
| **Quick Actions** | Akses cepat ke fitur utama |
| **Progress Summary** | Statistik workout mingguan/bulanan |

### 2.3 Workout Management

| Fitur | Deskripsi |
|-------|-----------|
| **Create Template** | Membuat template workout kustom |
| **Edit Template** | Modifikasi template yang ada |
| **Workout Detail** | Melihat detail lengkap workout |
| **Active Workout** | Menjalankan sesi workout dengan timer |
| **Edit Workout** | Edit workout yang sedang berjalan |
| **Workout Summary** | Ringkasan hasil setelah workout selesai |

### 2.4 Exercise Library

| Fitur | Deskripsi |
|-------|-----------|
| **Exercise Browser** | Jelajahi seluruh koleksi exercise |
| **Category Filter** | Filter berdasarkan kategori otot |
| **Exercise Selection** | Pilih multiple exercise untuk template |
| **Exercise List** | Daftar exercise per kategori |

### 2.5 Progress Tracking

| Fitur | Deskripsi |
|-------|-----------|
| **Progress Dashboard** | Visualisasi progress keseluruhan |
| **Workout History** | Riwayat workout dengan detail |
| **Yearly Calendar** | Kalender tahunan aktivitas workout |
| **Statistics** | Statistik latihan komprehensif |

### 2.6 Profile Management

| Fitur | Deskripsi |
|-------|-----------|
| **User Profile** | Informasi profil pengguna |
| **Theme Switch** | Dark/Light mode toggle |
| **Language Switch** | Multi-language support |
| **Logout** | Logout dengan clear session |

---

## 3. Teknologi yang Digunakan

### Core Android & Jetpack

| Library | Versi | Kegunaan |
|---------|-------|----------|
| Jetpack Compose | BOM 2025.09.00 | UI Framework deklaratif modern |
| Material 3 | Latest | Design system Material You |
| Navigation Compose | 2.7.5 | Type-safe navigation |
| Lifecycle ViewModel | 2.6.1 | Lifecycle-aware state management |
| Core Splashscreen | 1.0.1 | Native splash screen API |
| DataStore | 1.0.0 | Modern preferences storage |
| Core Library Desugaring | 2.0.4 | Java 8+ API support |

### Dependency Injection

| Library | Versi | Kegunaan |
|---------|-------|----------|
| Hilt | 2.48 | Compile-time dependency injection |
| Hilt Navigation Compose | 1.1.0 | ViewModel injection di Compose |

### Backend (Supabase)

| Library | Versi | Kegunaan |
|---------|-------|----------|
| Supabase Kotlin | Latest | Supabase client untuk Android |
| Ktor Client | Latest | HTTP client untuk Supabase |
| Kotlinx Serialization | Latest | JSON serialization/deserialization |

### Database & Storage

| Library | Versi | Kegunaan |
|---------|-------|----------|
| Supabase Database | - | PostgreSQL via Supabase |
| Supabase Auth | - | Authentication & Authorization |
| Supabase Storage | - | File storage (profile photos, images) |
| DataStore Preferences | 1.0.0 | Local key-value persistent storage |

### Media

| Library | Versi | Kegunaan |
|---------|-------|----------|
| Coil Compose | 2.5.0 | Image loading untuk Compose |

### Testing

| Library | Versi | Kegunaan |
|---------|-------|----------|
| JUnit | 4.13.2 | Unit testing framework |
| AndroidX JUnit | 1.1.5 | Android testing extensions |
| Espresso | 3.5.1 | UI testing framework |
| Compose UI Test | Latest | Compose testing utilities |

---

## 4. Arsitektur Aplikasi

### 4.1 Clean Architecture Overview

Aplikasi menggunakan **Clean Architecture** dengan pemisahan yang jelas antar layer:

```
┌─────────────────────────────────────────────────────────────┐
│                    PRESENTATION LAYER                        │
│  ┌────────────────────────────────────────────────────────┐ │
│  │                                                        │ │
│  │   UI (Screens)  ←→  ViewModel  ←→  State/Event        │ │
│  │   - Composables     - Business      - UI State        │ │
│  │   - Components       Logic          - User Events     │ │
│  │                                                        │ │
│  └────────────────────────────────────────────────────────┘ │
├─────────────────────────────────────────────────────────────┤
│                      DOMAIN LAYER                            │
│  ┌────────────────────────────────────────────────────────┐ │
│  │                                                        │ │
│  │   Repository        Models           Use Cases        │ │
│  │   Interfaces   ←→   (Entities)   ←→  (Optional)       │ │
│  │                                                        │ │
│  └────────────────────────────────────────────────────────┘ │
├─────────────────────────────────────────────────────────────┤
│                       DATA LAYER                             │
│  ┌────────────────────────────────────────────────────────┐ │
│  │                                                        │ │
│  │   Repository     Remote Data       Local Data         │ │
│  │   Implementation  Source            Source            │ │
│  │                   (API/DTO)         (Room/DataStore)  │ │
│  │                                                        │ │
│  └────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
```

### 4.2 MVVM Pattern

```
┌──────────────┐    Events     ┌──────────────┐
│              │ ─────────────→│              │
│     VIEW     │               │  VIEW MODEL  │
│  (Composable)│←───────────── │              │
│              │   StateFlow   │              │
└──────────────┘               └──────────────┘
                                      │
                                      │ Repository
                                      ↓
                               ┌──────────────┐
                               │              │
                               │    MODEL     │
                               │  (Domain)    │
                               │              │
                               └──────────────┘
```

### 4.3 Unidirectional Data Flow (UDF)

```
User Interaction → Event → ViewModel → State Update → UI Recomposition
        ↑                                                      │
        └──────────────────────────────────────────────────────┘
```

---

## 5. Struktur Proyek

```
app/src/main/java/com/diajarkoding/imfit/
│
├── ImFitApplication.kt              # Application class (@HiltAndroidApp)
├── MainActivity.kt                  # Single Activity entry point
│
├── core/                            # Core utilities
│   ├── data/
│   │   └── SessionManager.kt        # Token & session management
│   └── utils/
│       └── [Utility classes]
│
├── data/                            # Data Layer
│   ├── local/
│   │   ├── dao/                     # Room DAOs
│   │   ├── database/                # Room Database
│   │   ├── FakeExerciseDataSource.kt
│   │   ├── FakeUserDataSource.kt
│   │   └── FakeWorkoutDataSource.kt
│   └── repository/
│       ├── AuthRepositoryImpl.kt
│       ├── ExerciseRepositoryImpl.kt
│       └── WorkoutRepositoryImpl.kt
│
├── di/                              # Dependency Injection
│   └── AppModule.kt                 # Hilt modules
│
├── domain/                          # Domain Layer
│   ├── model/
│   │   ├── Exercise.kt
│   │   ├── ExerciseLog.kt
│   │   ├── MuscleCategory.kt
│   │   ├── TemplateExercise.kt
│   │   ├── User.kt
│   │   ├── WorkoutLog.kt
│   │   ├── WorkoutSession.kt
│   │   ├── WorkoutSet.kt
│   │   └── WorkoutTemplate.kt
│   └── repository/
│       ├── AuthRepository.kt
│       ├── ExerciseRepository.kt
│       └── WorkoutRepository.kt
│
├── presentation/                    # Presentation Layer
│   ├── components/
│   │   └── common/
│   │       ├── IMFITButton.kt
│   │       ├── IMFITDialog.kt
│   │       ├── IMFITLanguageSwitch.kt
│   │       ├── IMFITTextField.kt
│   │       └── IMFITThemeSwitch.kt
│   │
│   ├── navigation/
│   │   ├── NavGraph.kt              # Main navigation graph
│   │   └── Routes.kt                # Route definitions
│   │
│   └── ui/
│       ├── auth/
│       │   ├── LoginScreen.kt
│       │   ├── LoginViewModel.kt
│       │   ├── RegisterScreen.kt
│       │   └── RegisterViewModel.kt
│       │
│       ├── exercise/
│       │   ├── ExerciseBrowserScreen.kt
│       │   ├── ExerciseBrowserViewModel.kt
│       │   ├── ExerciseListScreen.kt
│       │   ├── ExerciseSelectionScreen.kt
│       │   └── ExerciseSelectionViewModel.kt
│       │
│       ├── home/
│       │   ├── HomeScreen.kt
│       │   └── HomeViewModel.kt
│       │
│       ├── main/
│       │   └── [Main container screens]
│       │
│       ├── profile/
│       │   ├── ProfileScreen.kt
│       │   └── ProfileViewModel.kt
│       │
│       ├── progress/
│       │   ├── ProgressScreen.kt
│       │   ├── ProgressViewModel.kt
│       │   ├── WorkoutHistoryDetailScreen.kt
│       │   └── YearlyCalendarScreen.kt
│       │
│       ├── splash/
│       │   └── SplashScreen.kt
│       │
│       ├── template/
│       │   ├── CreateTemplateScreen.kt
│       │   └── CreateTemplateViewModel.kt
│       │
│       └── workout/
│           ├── ActiveWorkoutScreen.kt
│           ├── ActiveWorkoutViewModel.kt
│           ├── EditWorkoutScreen.kt
│           ├── EditWorkoutViewModel.kt
│           ├── WorkoutDetailScreen.kt
│           ├── WorkoutDetailViewModel.kt
│           ├── WorkoutSummaryScreen.kt
│           └── WorkoutSummaryViewModel.kt
│
└── theme/                           # Design System
    ├── Color.kt                     # Color definitions
    ├── DesignSystem.kt              # Design tokens
    ├── LocaleManager.kt             # Internationalization
    ├── Shape.kt                     # Shape definitions
    ├── Theme.kt                     # Theme configuration
    ├── ThemeManager.kt              # Theme state management
    └── Type.kt                      # Typography
```

---

## 6. Modul dan Komponen

### 6.1 Domain Models

#### Exercise
```kotlin
data class Exercise(
    val id: String,
    val name: String,
    val muscleCategory: MuscleCategory
)
```

#### Muscle Category
```kotlin
enum class MuscleCategory {
    CHEST, BACK, SHOULDERS, BICEPS, TRICEPS,
    LEGS, CORE, CARDIO, FULL_BODY
}
```

#### Workout Template
```kotlin
data class WorkoutTemplate(
    val id: String,
    val name: String,
    val exercises: List<TemplateExercise>
)
```

#### Workout Session
```kotlin
data class WorkoutSession(
    val id: String,
    val templateId: String,
    val startTime: Long,
    val endTime: Long?,
    val exercises: List<ExerciseLog>,
    val status: SessionStatus
)
```

#### Workout Log
```kotlin
data class WorkoutLog(
    val id: String,
    val date: String,
    val duration: Long,
    val exercisesCompleted: Int,
    val totalSets: Int,
    val totalReps: Int
)
```

### 6.2 Repositories

| Repository | Tanggung Jawab |
|------------|----------------|
| `AuthRepository` | Login, register, logout, session |
| `WorkoutRepository` | Template CRUD, workout sessions, logs |
| `ExerciseRepository` | Exercise catalog, categories |

### 6.3 Reusable Components

| Component | Deskripsi |
|-----------|-----------|
| `IMFITButton` | Custom button dengan variants (primary, secondary, outline) |
| `IMFITTextField` | Styled text input dengan validasi |
| `IMFITDialog` | Custom dialog component |
| `IMFITThemeSwitch` | Toggle untuk dark/light mode |
| `IMFITLanguageSwitch` | Language selector |

---

## 7. Sistem Navigasi

### 7.1 Route Definitions

```kotlin
object Routes {
    // Auth Flow
    const val SPLASH = "splash"
    const val LOGIN = "login"
    const val REGISTER = "register"
    
    // Main Flow
    const val MAIN = "main"
    const val HOME = "home"
    const val PROFILE = "profile"
    
    // Template Management
    const val TEMPLATE_LIST = "templates"
    const val CREATE_TEMPLATE = "create_template"
    const val EDIT_TEMPLATE = "edit_template/{templateId}"
    
    // Workout Flow
    const val WORKOUT_DETAIL = "workout_detail/{workoutId}"
    const val ACTIVE_WORKOUT = "active_workout/{templateId}"
    const val EDIT_WORKOUT = "edit_workout/{workoutId}"
    const val WORKOUT_SUMMARY = "workout_summary/{workoutLogId}"
    
    // Exercise Flow
    const val EXERCISE_BROWSER = "exercise_browser"
    const val EXERCISE_SELECTION = "exercise_selection/{templateId}"
    const val EXERCISE_LIST = "exercise_list/{categoryName}"
    
    // Progress Flow
    const val WORKOUT_HISTORY = "workout_history/{date}"
    const val YEARLY_CALENDAR = "yearly_calendar"
}
```

### 7.2 Navigation Flow Diagram

```
                         ┌──────────────┐
                         │    SPLASH    │
                         └──────┬───────┘
                                │
                    ┌───────────┴───────────┐
                    ▼                       ▼
             ┌──────────┐           ┌──────────────┐
             │  LOGIN   │◄─────────►│   REGISTER   │
             └────┬─────┘           └──────────────┘
                  │
                  ▼
           ┌──────────────┐
           │     MAIN     │
           └──────┬───────┘
                  │
    ┌─────────────┼─────────────────┬─────────────┐
    ▼             ▼                 ▼             ▼
┌────────┐  ┌──────────┐    ┌───────────┐  ┌──────────┐
│  HOME  │  │ TEMPLATE │    │  EXERCISE │  │ PROGRESS │
└────────┘  │   LIST   │    │  BROWSER  │  └────┬─────┘
            └────┬─────┘    └─────┬─────┘       │
                 │                │             ├──► Yearly Calendar
    ┌────────────┼────────┐       │             └──► Workout History
    ▼            ▼        ▼       ▼
┌────────┐  ┌────────┐ ┌────────┐ ┌────────────┐
│ CREATE │  │  EDIT  │ │WORKOUT │ │  EXERCISE  │
│TEMPLATE│  │TEMPLATE│ │ DETAIL │ │    LIST    │
└────────┘  └────────┘ └───┬────┘ └────────────┘
                           │
                ┌──────────┼──────────┐
                ▼          ▼          ▼
           ┌────────┐ ┌────────┐ ┌────────┐
           │ ACTIVE │ │  EDIT  │ │EXERCISE│
           │WORKOUT │ │WORKOUT │ │SELECTION
           └───┬────┘ └────────┘ └────────┘
               │
               ▼
         ┌──────────┐
         │ WORKOUT  │
         │ SUMMARY  │
         └──────────┘
```

---

## 8. Design System dan Theming

### 8.1 Color System

```kotlin
// Primary Colors
val PrimaryBlue = Color(0xFF2196F3)
val PrimaryDark = Color(0xFF1976D2)

// Background Colors (Light)
val BackgroundPrimaryLight = Color(0xFFF5F5F5)
val SurfaceLight = Color(0xFFFFFFFF)

// Background Colors (Dark)
val BackgroundPrimaryDark = Color(0xFF121212)
val SurfaceDark = Color(0xFF1E1E1E)
```

### 8.2 Theme Configuration

```kotlin
@Composable
fun IMFITTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = Shapes,
        content = content
    )
}
```

### 8.3 Theme Manager

```kotlin
class ThemeManager(private val dataStore: DataStore<Preferences>) {
    val isDarkMode: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[DARK_MODE_KEY] ?: false
    }
    
    suspend fun toggleTheme() { ... }
}
```

### 8.4 Locale Manager

```kotlin
class LocaleManager(private val dataStore: DataStore<Preferences>) {
    val currentLocale: Flow<String> = dataStore.data.map { preferences ->
        preferences[LOCALE_KEY] ?: "en"
    }
    
    suspend fun setLocale(locale: String) { ... }
}
```

---

## 9. State Management

### 9.1 Pattern: MVI-like (State + Event)

Setiap screen menggunakan pola yang konsisten:

```
┌──────────────────────────────────────────────────────────┐
│                        Screen                             │
│  ┌────────────────────────────────────────────────────┐  │
│  │                 State (data class)                 │  │
│  │  - UI data (text, lists, etc.)                     │  │
│  │  - Loading states                                  │  │
│  │  - Error messages                                  │  │
│  └────────────────────────────────────────────────────┘  │
│                          ↑                                │
│                     StateFlow                             │
│                          │                                │
│  ┌────────────────────────────────────────────────────┐  │
│  │                   ViewModel                        │  │
│  │  - Business logic                                  │  │
│  │  - State updates                                   │  │
│  │  - Event handling                                  │  │
│  └────────────────────────────────────────────────────┘  │
│                          ↑                                │
│                       Events                              │
│                          │                                │
│  ┌────────────────────────────────────────────────────┐  │
│  │             Event (sealed class)                   │  │
│  │  - User actions (clicks, inputs)                   │  │
│  │  - System events                                   │  │
│  └────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────┘
```

### 9.2 Contoh Implementasi

#### State Definition
```kotlin
data class LoginState(
    val email: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val isSuccess: Boolean = false
)
```

#### Event Definition
```kotlin
sealed class LoginEvent {
    data class EmailChanged(val email: String) : LoginEvent()
    data class PasswordChanged(val password: String) : LoginEvent()
    object LoginClicked : LoginEvent()
    object ErrorDismissed : LoginEvent()
}
```

#### ViewModel
```kotlin
@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {
    
    private val _state = MutableStateFlow(LoginState())
    val state: StateFlow<LoginState> = _state.asStateFlow()
    
    fun onEvent(event: LoginEvent) {
        when (event) {
            is LoginEvent.EmailChanged -> {
                _state.update { it.copy(email = event.email) }
            }
            is LoginEvent.PasswordChanged -> {
                _state.update { it.copy(password = event.password) }
            }
            LoginEvent.LoginClicked -> login()
            LoginEvent.ErrorDismissed -> {
                _state.update { it.copy(error = null) }
            }
        }
    }
    
    private fun login() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            // ... login logic
        }
    }
}
```

#### Screen Composable
```kotlin
@Composable
fun LoginScreen(
    viewModel: LoginViewModel = hiltViewModel(),
    onNavigateToRegister: () -> Unit,
    onLoginSuccess: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    
    LaunchedEffect(state.isSuccess) {
        if (state.isSuccess) onLoginSuccess()
    }
    
    // UI implementation
    Column {
        IMFITTextField(
            value = state.email,
            onValueChange = { viewModel.onEvent(LoginEvent.EmailChanged(it)) }
        )
        // ... more UI
    }
}
```

---

## 10. Data Layer

### 10.1 Repository Pattern

```
┌─────────────────────────────────────────────────────┐
│                    ViewModel                         │
└───────────────────────┬─────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────────┐
│              Repository Interface                    │
│                   (Domain Layer)                     │
└───────────────────────┬─────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────────┐
│            Repository Implementation                 │
│                   (Data Layer)                       │
├─────────────────────────────────────────────────────┤
│                        │                             │
│           ┌────────────┴────────────┐               │
│           ▼                         ▼               │
│   ┌───────────────┐        ┌───────────────┐       │
│   │ Remote Source │        │ Local Source  │       │
│   │   (Retrofit)  │        │  (Room/Fake)  │       │
│   └───────────────┘        └───────────────┘       │
└─────────────────────────────────────────────────────┘
```

### 10.2 Local Data Sources

Saat ini menggunakan **Fake Data Sources** untuk development:

| Data Source | Kegunaan |
|-------------|----------|
| `FakeExerciseDataSource` | Mock data untuk exercise library |
| `FakeWorkoutDataSource` | Mock data untuk workout templates dan logs |
| `FakeUserDataSource` | Mock data untuk user profile |

### 10.3 Repository Interfaces

```kotlin
interface WorkoutRepository {
    suspend fun getTemplates(): List<WorkoutTemplate>
    suspend fun getTemplate(id: String): WorkoutTemplate?
    suspend fun createTemplate(template: WorkoutTemplate): Result<Unit>
    suspend fun updateTemplate(template: WorkoutTemplate): Result<Unit>
    suspend fun deleteTemplate(id: String): Result<Unit>
    suspend fun startWorkout(templateId: String): WorkoutSession
    suspend fun completeWorkout(session: WorkoutSession): WorkoutLog
    suspend fun getWorkoutHistory(): List<WorkoutLog>
}

interface ExerciseRepository {
    suspend fun getAllExercises(): List<Exercise>
    suspend fun getExercisesByCategory(category: MuscleCategory): List<Exercise>
    suspend fun searchExercises(query: String): List<Exercise>
}

interface AuthRepository {
    suspend fun login(email: String, password: String): Result<User>
    suspend fun register(user: User): Result<Unit>
    suspend fun logout(): Result<Unit>
    fun isLoggedIn(): Flow<Boolean>
}
```

---

## 11. Dependency Injection

### 11.1 Hilt Setup

#### Application Class
```kotlin
@HiltAndroidApp
class ImFitApplication : Application()
```

#### MainActivity
```kotlin
@AndroidEntryPoint
class MainActivity : ComponentActivity()
```

### 11.2 Module Configuration

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    
    @Provides
    @Singleton
    fun provideDataStore(
        @ApplicationContext context: Context
    ): DataStore<Preferences> {
        return PreferenceDataStoreFactory.create {
            context.preferencesDataStoreFile("settings")
        }
    }
    
    @Provides
    @Singleton
    fun provideSessionManager(
        dataStore: DataStore<Preferences>
    ): SessionManager {
        return SessionManager(dataStore)
    }
    
    @Provides
    @Singleton
    fun provideWorkoutRepository(): WorkoutRepository {
        return WorkoutRepositoryImpl(FakeWorkoutDataSource())
    }
    
    @Provides
    @Singleton
    fun provideExerciseRepository(): ExerciseRepository {
        return ExerciseRepositoryImpl(FakeExerciseDataSource())
    }
}
```

### 11.3 ViewModel Injection

```kotlin
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val workoutRepository: WorkoutRepository,
    private val sessionManager: SessionManager
) : ViewModel() {
    // ...
}
```

---

## 12. Panduan Instalasi

### 12.1 Clone Repository

```bash
git clone https://github.com/your-org/imfit-mobile.git
cd imfit-mobile
```

### 12.2 Konfigurasi local.properties

Buat file `local.properties` di root project:

```properties
sdk.dir=/path/to/android/sdk
BASE_URL=http://10.0.2.2:8000/api/
```

> **Note**: `10.0.2.2` adalah localhost alias untuk Android Emulator

### 12.3 Build dan Run

```bash
# Sync dependencies
./gradlew build

# Run debug build
./gradlew installDebug

# Generate release APK
./gradlew assembleRelease
```

### 12.4 Menggunakan Android Studio

1. Buka Android Studio
2. File → Open → Pilih folder project
3. Tunggu Gradle sync selesai
4. Run → Run 'app' atau tekan `Shift + F10`

---

## 13. Panduan Pengembangan

### 13.1 Menambahkan Screen Baru

#### Step 1: Buat State dan Event
```kotlin
// presentation/ui/newfeature/NewFeatureState.kt
data class NewFeatureState(
    val data: List<Item> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

// presentation/ui/newfeature/NewFeatureEvent.kt
sealed class NewFeatureEvent {
    object LoadData : NewFeatureEvent()
    data class ItemClicked(val id: String) : NewFeatureEvent()
}
```

#### Step 2: Buat ViewModel
```kotlin
// presentation/ui/newfeature/NewFeatureViewModel.kt
@HiltViewModel
class NewFeatureViewModel @Inject constructor(
    private val repository: YourRepository
) : ViewModel() {
    
    private val _state = MutableStateFlow(NewFeatureState())
    val state: StateFlow<NewFeatureState> = _state.asStateFlow()
    
    init {
        loadData()
    }
    
    fun onEvent(event: NewFeatureEvent) {
        when (event) {
            NewFeatureEvent.LoadData -> loadData()
            is NewFeatureEvent.ItemClicked -> handleItemClick(event.id)
        }
    }
    
    private fun loadData() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            // ... load data
        }
    }
}
```

#### Step 3: Buat Screen Composable
```kotlin
// presentation/ui/newfeature/NewFeatureScreen.kt
@Composable
fun NewFeatureScreen(
    viewModel: NewFeatureViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    
    Scaffold(
        topBar = { /* AppBar */ }
    ) { padding ->
        // Screen content
    }
}
```

#### Step 4: Tambahkan Route
```kotlin
// presentation/navigation/Routes.kt
object Routes {
    // ... existing routes
    const val NEW_FEATURE = "new_feature"
}
```

#### Step 5: Daftarkan di NavGraph
```kotlin
// presentation/navigation/NavGraph.kt
composable(Routes.NEW_FEATURE) {
    NewFeatureScreen(
        onNavigateBack = { navController.popBackStack() }
    )
}
```

### 13.2 Menambahkan Repository Baru

#### Step 1: Buat Interface di Domain
```kotlin
// domain/repository/NewRepository.kt
interface NewRepository {
    suspend fun getData(): List<DataModel>
    suspend fun saveData(data: DataModel): Result<Unit>
}
```

#### Step 2: Buat Implementation di Data
```kotlin
// data/repository/NewRepositoryImpl.kt
class NewRepositoryImpl @Inject constructor(
    private val dataSource: DataSource
) : NewRepository {
    
    override suspend fun getData(): List<DataModel> {
        return dataSource.fetchAll()
    }
    
    override suspend fun saveData(data: DataModel): Result<Unit> {
        return try {
            dataSource.save(data)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
```

#### Step 3: Register di Hilt Module
```kotlin
// di/AppModule.kt
@Provides
@Singleton
fun provideNewRepository(
    dataSource: DataSource
): NewRepository {
    return NewRepositoryImpl(dataSource)
}
```

### 13.3 Best Practices

| Kategori | Guidelines |
|----------|------------|
| **Naming** | Gunakan suffix yang jelas: `*Screen`, `*ViewModel`, `*State`, `*Event` |
| **State** | State harus immutable, gunakan `copy()` untuk update |
| **ViewModel** | Jangan simpan Context atau Activity reference |
| **Composables** | Gunakan `remember` untuk expensive calculations |
| **Navigation** | Gunakan type-safe navigation dengan arguments |
| **Error Handling** | Selalu handle error states di UI |

---

## 14. Konfigurasi Build

### 14.1 Build Types

| Type | Minify | ProGuard | BASE_URL |
|------|--------|----------|----------|
| **debug** | No | No | From local.properties |
| **release** | No | No | Hardcoded production URL |

### 14.2 Build Features

```kotlin
android {
    buildFeatures {
        compose = true
        buildConfig = true
    }
}
```

### 14.3 ProGuard Configuration

ProGuard saat ini dinonaktifkan. Untuk mengaktifkan:

```kotlin
release {
    isMinifyEnabled = true
    isShrinkResources = true
    proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro"
    )
}
```

### 14.4 Gradle Commands

| Command | Deskripsi |
|---------|-----------|
| `./gradlew build` | Build semua variants |
| `./gradlew assembleDebug` | Build debug APK |
| `./gradlew assembleRelease` | Build release APK |
| `./gradlew installDebug` | Install ke device/emulator |
| `./gradlew test` | Run unit tests |
| `./gradlew connectedAndroidTest` | Run instrumented tests |
| `./gradlew clean` | Clean build files |
| `./gradlew dependencies` | List semua dependencies |

---

## Changelog

### Versi 1.1 (Desember 2024)
- **NEW**: Modul Progress dengan Yearly Calendar dan Workout History
- **NEW**: Exercise Browser dengan kategori filter
- **NEW**: Active Workout dengan real-time tracking
- **NEW**: Workout Summary setelah selesai latihan
- **NEW**: Theme switching (Dark/Light mode)
- **NEW**: Language switching support
- **IMPROVED**: Reusable components (IMFITButton, IMFITTextField, IMFITDialog)
- **IMPROVED**: Design system dengan tokens
- **IMPROVED**: Navigation structure dengan nested graphs

### Versi 1.0 (September 2024)
- Initial release
- Auth module (Login/Register)
- Basic workout template management
- Exercise library
- Profile management

---

## Tim Pengembang

| Role | Nama |
|------|------|
| Lead Developer | Tim IMFIT |
| UI/UX | Tim IMFIT |

---

## Lihat Juga

Untuk pengembangan backend dan database, lihat dokumentasi berikut:

- **[Backend API Planning](./planning_db.md)** - Arsitektur backend, API endpoints, authentication, dan roadmap implementasi
- **[Database Schema](./schema_db.md)** - ERD, definisi tabel, migrations, dan stored procedures

---

## Lisensi

```
Copyright 2024 IMFIT Team

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0
```

---

<div align="center">

**IMFIT** - Your Personal Fitness Companion

Made with Kotlin and Jetpack Compose

</div>

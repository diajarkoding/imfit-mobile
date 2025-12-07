# Dokumentasi Pengembangan Aplikasi IMFIT

## Daftar Isi
1. [Gambaran Umum Proyek](#1-gambaran-umum-proyek)
2. [Arsitektur Aplikasi](#2-arsitektur-aplikasi)
3. [Struktur Folder Proyek](#3-struktur-folder-proyek)
4. [Teknologi dan Library yang Digunakan](#4-teknologi-dan-library-yang-digunakan)
5. [Modul dan Fitur Aplikasi](#5-modul-dan-fitur-aplikasi)
6. [Alur Navigasi](#6-alur-navigasi)
7. [Dependency Injection dengan Hilt](#7-dependency-injection-dengan-hilt)
8. [Networking dan API](#8-networking-dan-api)
9. [Manajemen State](#9-manajemen-state)
10. [Theming dan Design System](#10-theming-dan-design-system)
11. [Panduan Pengembangan](#11-panduan-pengembangan)
12. [Konfigurasi Build](#12-konfigurasi-build)

---

## 1. Gambaran Umum Proyek

**IMFIT** adalah aplikasi Android untuk manajemen workout/fitness yang dikembangkan menggunakan **Jetpack Compose** dengan arsitektur **Clean Architecture + MVVM**. 

### Informasi Proyek
| Atribut | Nilai |
|---------|-------|
| Package Name | `com.diajarkoding.imfit` |
| Min SDK | 24 (Android 7.0) |
| Target SDK | 36 |
| Compile SDK | 36 |
| Kotlin Version | 2.0.21 |
| Compose BOM | 2025.09.00 |

### Fitur Utama
- **Autentikasi** - Login dan Register dengan token-based authentication
- **Home** - Dashboard utama aplikasi
- **Workout** - Manajemen rencana latihan dan hari latihan
- **Exercises** - Katalog dan pemilihan latihan
- **Progress** - Tracking kemajuan latihan (dalam pengembangan)
- **Profile** - Manajemen profil pengguna dan logout

---

## 2. Arsitektur Aplikasi

Aplikasi ini menggunakan **Clean Architecture** dengan 3 layer utama:

```text
+-------------------------------------------------------------+
|                    PRESENTATION LAYER                        |
|  +--------------------------------------------------------+ |
|  |   UI (Screens/Composables) <-> ViewModel <-> State     | |
|  +--------------------------------------------------------+ |
+-------------------------------------------------------------+
|                      DOMAIN LAYER                            |
|  +--------------------------------------------------------+ |
|  |     UseCase <-> Repository Interface <-> Model         | |
|  +--------------------------------------------------------+ |
+-------------------------------------------------------------+
|                       DATA LAYER                             |
|  +--------------------------------------------------------+ |
|  | Repository Impl <-> Remote (API) / Local (Room/Prefs)  | |
|  +--------------------------------------------------------+ |
+-------------------------------------------------------------+
```

### Pola Arsitektur

#### MVVM (Model-View-ViewModel)
- **View**: Composable functions yang menampilkan UI
- **ViewModel**: Mengelola state UI dan logika bisnis, menggunakan `StateFlow`
- **Model**: Data classes yang merepresentasikan domain entities

#### Unidirectional Data Flow (UDF)

```text
User Action -> Event -> ViewModel -> Update State -> UI Recompose
```

---

## 3. Struktur Folder Proyek

```text
app/src/main/java/com/diajarkoding/imfit/
|-- ImFitApplication.kt          # Application class dengan @HiltAndroidApp
|-- MainActivity.kt              # Single Activity sebagai entry point
|-- MainViewModel.kt             # ViewModel untuk state global (loading, auth)
|
|-- core/                        # Utilitas dan komponen inti
|   |-- data/
|   |   +-- SessionManager.kt    # Manajemen session/token dengan DataStore
|   +-- utils/
|       |-- Validator.kt         # Validasi input
|       +-- ErrorHandler.kt      # Penanganan error
|
|-- data/                        # Layer Data
|   |-- remote/
|   |   |-- api/
|   |   |   +-- AuthApiService.kt    # Retrofit API interface
|   |   |-- dto/                      # Data Transfer Objects
|   |   |   |-- BaseResponse.kt
|   |   |   |-- LoginRequest.kt
|   |   |   |-- LoginDto.kt
|   |   |   |-- RegisterRequest.kt
|   |   |   +-- ErrorResponse.kt
|   |   +-- AuthInterceptor.kt       # OkHttp interceptor untuk token
|   +-- repository/
|       +-- AuthRepositoryImpl.kt    # Implementasi repository
|
|-- di/                          # Dependency Injection Modules
|   |-- NetworkModule.kt         # Konfigurasi Retrofit, OkHttp, Moshi
|   +-- RepositoryModule.kt      # Binding repository interfaces
|
|-- domain/                      # Layer Domain (Business Logic)
|   |-- model/
|   |   |-- User.kt              # Domain model untuk User
|   |   +-- Result.kt            # Wrapper untuk success/error
|   |-- repository/
|   |   +-- AuthRepository.kt    # Repository interface
|   +-- usecase/
|       |-- LoginUserUseCase.kt
|       |-- RegisterUserUseCase.kt
|       +-- LogoutUserUseCase.kt
|
|-- presentation/                # Layer Presentation
|   |-- navigation/
|   |   |-- RootNavigation.kt    # Navigasi utama (auth flow)
|   |   |-- MainNavigation.kt    # Navigasi dalam app (bottom nav)
|   |   |-- AuthNavigation.kt    # Navigasi autentikasi
|   |   +-- BottomNavItem.kt     # Definisi item bottom navigation
|   |
|   |-- components/              # Reusable UI Components
|   |   |-- auth/
|   |   |   |-- AuthTextField.kt
|   |   |   |-- AuthScreenLayout.kt
|   |   |   +-- AuthRedirectText.kt
|   |   |-- workout/
|   |   |   |-- WorkoutDayCard.kt
|   |   |   |-- WorkoutHeroSection.kt
|   |   |   |-- AddExerciseCard.kt
|   |   |   +-- ... (komponen workout lainnya)
|   |   |-- exercises/
|   |   |   +-- ExerciseSelectionItem.kt
|   |   |-- PrimaryButton.kt
|   |   |-- PasswordTextField.kt
|   |   |-- DatePickerField.kt
|   |   |-- TwoToneTitle.kt
|   |   |-- IMFITBottomNavigation.kt
|   |   |-- IMFITAppBar.kt
|   |   +-- GlobalLoadingIndicator.kt
|   |
|   +-- ui/                      # Screen-level Composables
|       |-- SplashScreen.kt
|       |-- main/
|       |   |-- MainScreen.kt        # Container dengan bottom nav
|       |   +-- PlaceholderScreen.kt
|       |-- auth/
|       |   |-- LoginScreen.kt
|       |   |-- LoginViewModel.kt
|       |   |-- LoginState.kt
|       |   |-- LoginEvent.kt
|       |   |-- RegisterScreen.kt
|       |   |-- RegisterViewModel.kt
|       |   |-- RegisterState.kt
|       |   +-- RegisterEvent.kt
|       |-- home/
|       |   |-- HomeScreen.kt
|       |   +-- HomeViewModel.kt
|       |-- workout/
|       |   |-- WorkoutScreen.kt
|       |   |-- EditWorkoutDayScreen.kt
|       |   |-- viewmodel/
|       |   |   |-- WorkoutViewModel.kt
|       |   |   +-- EditWorkoutDayViewModel.kt
|       |   +-- views/
|       |       |-- EmptyStateView.kt
|       |       |-- AddWorkoutView.kt
|       |       |-- PlannedWorkoutView.kt
|       |       +-- WorkoutDayDetailView.kt
|       |-- exercises/
|       |   |-- AddExercisesScreen.kt
|       |   +-- AddExercisesViewModel.kt
|       +-- profile/
|           |-- ProfileScreen.kt
|           |-- ProfileViewModel.kt
|           |-- ProfileState.kt
|           +-- ProfileEvent.kt
|
+-- theme/                       # Design System
    |-- Theme.kt                 # Material3 Theme configuration
    |-- Color.kt                 # Definisi warna
    |-- Type.kt                  # Typography
    |-- DesignSystem.kt          # Custom design tokens
    +-- SafeAreaComponents.kt    # Komponen untuk safe area/insets
```

---

## 4. Teknologi dan Library yang Digunakan

### Core Android & Jetpack
| Library | Versi | Kegunaan |
|---------|-------|----------|
| Jetpack Compose | BOM 2025.09.00 | UI Framework modern |
| Material 3 | Latest | Design system |
| Navigation Compose | 2.7.5 | Navigasi antar screen |
| Lifecycle ViewModel | 2.6.1 | State management |
| Core Splashscreen | 1.0.1 | Native splash screen |
| DataStore | 1.0.0 | Penyimpanan preferensi |

### Dependency Injection
| Library | Versi | Kegunaan |
|---------|-------|----------|
| Hilt | 2.48 | Dependency injection |
| Hilt Navigation Compose | 1.1.0 | Integrasi Hilt dengan Navigation |

### Networking
| Library | Versi | Kegunaan |
|---------|-------|----------|
| Retrofit | 2.9.0 | HTTP client |
| OkHttp Logging | 4.9.0 | Logging network requests |
| Moshi | 1.15.0 | JSON parsing |

### Database & Storage
| Library | Versi | Kegunaan |
|---------|-------|----------|
| Room | 2.6.1 | Local database |
| DataStore Preferences | 1.0.0 | Key-value storage |

### Image Loading
| Library | Versi | Kegunaan |
|---------|-------|----------|
| Coil Compose | 2.5.0 | Image loading untuk Compose |

---

## 5. Modul dan Fitur Aplikasi

### 5.1 Modul Autentikasi (Auth)

#### Login
- Input: Email/Username dan Password
- Validasi lokal sebelum request ke server
- Penyimpanan token menggunakan DataStore
- State: `LoginState`, Events: `LoginEvent`

```text
Contoh alur login:
LoginEvent.LoginButtonPressed 
    -> validateAndLogin() 
    -> LoginUserUseCase 
    -> AuthRepository.login() 
    -> SessionManager.saveAuthToken()
```

#### Register
- Input: Fullname, Username, Email, Password, Password Confirmation, Date of Birth, Profile Picture (opsional)
- Upload gambar menggunakan Multipart/form-data
- Validasi field wajib diisi

### 5.2 Modul Workout

#### Screen Modes
1. **EMPTY** - Belum ada rencana workout
2. **ADD_PLAN** - Membuat rencana baru (belum ada hari)
3. **PLANNED** - Rencana sudah memiliki hari latihan

#### Fitur
- Membuat rencana workout baru
- Menambah hari latihan
- Mengedit hari latihan
- Menambah exercise ke hari tertentu
- Melihat detail hari latihan

#### Data Model
```kotlin
data class WorkoutPlan(
    val id: String,
    val title: String,
    val days: List<WorkoutDay>,
    val imageUrl: String,
    val maxDays: Int = 7
)

data class WorkoutDay(
    val id: String,
    val title: String,
    val estimatedTime: String,
    val exerciseCount: String,
    val exercises: List<Exercise>,
    val status: String
)
```

### 5.3 Modul Exercises
- Menampilkan daftar latihan yang tersedia
- Memilih multiple exercises untuk ditambahkan ke hari latihan
- Navigasi kembali dengan membawa data yang dipilih

### 5.4 Modul Profile
- Menampilkan informasi profil pengguna
- Fungsi logout dengan clear session

---

## 6. Alur Navigasi

### Root Navigation (Unauthenticated)

```text
Splash Screen
    |
    v
Login Screen <-> Register Screen
    |
    v
Main Graph (Authenticated)
```

### Main Navigation (Authenticated)

```text
+---------------------------------------------+
|              Bottom Navigation               |
+----------+----------+----------+------------+
|   Home   | Workout  |Exercises |  Progress  |
+----------+----+-----+----------+------------+
                |
                +-- Edit Workout Day
                +-- Add Exercises
                
Profile (accessible from AppBar)
```

### Route Definitions
```kotlin
object Routes {
    const val SPLASH = "splash"
    const val LOGIN = "login"
    const val REGISTER = "register"
    const val MAIN_GRAPH = "main_graph"
    const val PROFILE = "profile"
    
    // Dynamic routes
    const val EDIT_WORKOUT_DAY = "edit_workout_day/{dayId}?dayName={dayName}"
    const val ADD_EXERCISES = "add_exercises/{dayId}"
}
```

---

## 7. Dependency Injection dengan Hilt

### Application Setup
```kotlin
@HiltAndroidApp
class ImFitApplication : Application()
```

### Activity Setup
```kotlin
@AndroidEntryPoint
class MainActivity : ComponentActivity()
```

### Modules

#### NetworkModule
Menyediakan:
- `HttpLoggingInterceptor` - Logging untuk debug
- `AuthInterceptor` - Menambahkan token ke setiap request
- `OkHttpClient` - HTTP client dengan interceptors
- `Moshi` - JSON parser
- `Retrofit` - HTTP client untuk API calls
- `AuthApiService` - API interface

#### RepositoryModule
Binding interface ke implementasi:
```kotlin
@Binds
abstract fun bindAuthRepository(
    authRepositoryImpl: AuthRepositoryImpl
): AuthRepository
```

### Menggunakan Dependency di ViewModel
```kotlin
@HiltViewModel
class LoginViewModel @Inject constructor(
    private val loginUserUseCase: LoginUserUseCase,
    private val sessionManager: SessionManager,
) : ViewModel()
```

---

## 8. Networking dan API

### Base Configuration
- Base URL dikonfigurasi via `local.properties` (debug) atau hardcoded (release)
- Content type: JSON dan Multipart/form-data
- Token dikirim via `Authorization: Bearer {token}` header

### AuthInterceptor
```kotlin
class AuthInterceptor(private val sessionManager: SessionManager) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = runBlocking { 
            sessionManager.getAuthToken().first() 
        }
        val request = if (token != null) {
            chain.request().newBuilder()
                .addHeader("Authorization", "Bearer $token")
                .build()
        } else {
            chain.request()
        }
        return chain.proceed(request)
    }
}
```

### API Endpoints
| Method | Endpoint | Deskripsi |
|--------|----------|-----------|
| POST | `/register` | Registrasi user baru (multipart) |
| POST | `/login` | Login user |
| POST | `/logout` | Logout user |

### Response Format
```kotlin
data class BaseResponse<T>(
    val status: String,
    val message: String?,
    val data: T?
)
```

### Error Handling
```kotlin
sealed class Result<out T> {
    data class Success<T>(val data: T) : Result<T>()
    data class Error(val message: String) : Result<Nothing>()
}
```

---

## 9. Manajemen State

### Pattern: StateFlow + Event
Setiap screen menggunakan pola:
1. **State** - Data class yang menyimpan semua state UI
2. **Event** - Sealed class untuk user actions
3. **ViewModel** - Memproses event dan update state

### Contoh Implementasi (Login)

#### State
```kotlin
data class LoginState(
    val emailOrUsername: String = "",
    val password: String = "",
    val rememberMe: Boolean = false,
    val isLoading: Boolean = false,
    val emailOrUsernameError: String? = null,
    val passwordError: String? = null,
    val snackbarMessage: String? = null,
    val loginSuccess: Boolean = false
)
```

#### Event
```kotlin
sealed class LoginEvent {
    data class EmailOrUsernameChanged(val value: String) : LoginEvent()
    data class PasswordChanged(val value: String) : LoginEvent()
    data class RememberMeChanged(val value: Boolean) : LoginEvent()
    object LoginButtonPressed : LoginEvent()
    object SnackbarDismissed : LoginEvent()
}
```

#### ViewModel
```kotlin
@HiltViewModel
class LoginViewModel @Inject constructor(...) : ViewModel() {
    private val _state = MutableStateFlow(LoginState())
    val state = _state.asStateFlow()

    fun onEvent(event: LoginEvent) {
        when (event) {
            is LoginEvent.EmailOrUsernameChanged -> {
                _state.update { it.copy(emailOrUsername = event.value) }
            }
            // ... handle events lainnya
        }
    }
}
```

#### Screen (Composable)
```kotlin
@Composable
fun LoginScreen(viewModel: LoginViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    
    // Render UI based on state
    // Trigger events: viewModel.onEvent(LoginEvent.LoginButtonPressed)
}
```

---

## 10. Theming dan Design System

### Color Scheme
Aplikasi mendukung Light dan Dark theme dengan konfigurasi di `Theme.kt`:

```kotlin
private val LightColorScheme = lightColorScheme(
    primary = PrimaryBlue,
    onPrimary = TextOnPrimaryLight,
    background = BackgroundPrimaryLight,
    surface = SurfaceLight,
    // ...
)

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryBlue,
    onPrimary = TextOnPrimaryDark,
    background = BackgroundPrimaryDark,
    surface = SurfaceDark,
    // ...
)
```

### Typography
Didefinisikan di `Type.kt` menggunakan Material3 Typography.

### Menggunakan Theme
```kotlin
@Composable
fun IMFITTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
```

### Edge-to-Edge Display
Aplikasi mendukung edge-to-edge display untuk Android 15+ dengan penanganan insets yang proper.

---

## 11. Panduan Pengembangan

### Menambahkan Fitur Baru

#### 1. Buat Domain Layer
```kotlin
// 1. Model (jika diperlukan)
data class NewFeatureModel(...)

// 2. Repository Interface
interface NewFeatureRepository {
    suspend fun getData(): Result<NewFeatureModel>
}

// 3. UseCase
class GetNewFeatureUseCase @Inject constructor(
    private val repository: NewFeatureRepository
) {
    suspend operator fun invoke() = repository.getData()
}
```

#### 2. Buat Data Layer
```kotlin
// 1. DTO untuk API
data class NewFeatureDto(...)

// 2. API Service
interface NewFeatureApiService {
    @GET("endpoint")
    suspend fun getData(): BaseResponse<NewFeatureDto>
}

// 3. Repository Implementation
class NewFeatureRepositoryImpl @Inject constructor(
    private val api: NewFeatureApiService
) : NewFeatureRepository {
    override suspend fun getData() = ...
}
```

#### 3. Setup DI Module
```kotlin
@Module
@InstallIn(SingletonComponent::class)
abstract class NewFeatureModule {
    @Binds
    abstract fun bindRepository(impl: NewFeatureRepositoryImpl): NewFeatureRepository
}
```

#### 4. Buat Presentation Layer
```kotlin
// 1. State
data class NewFeatureState(...)

// 2. Event
sealed class NewFeatureEvent { ... }

// 3. ViewModel
@HiltViewModel
class NewFeatureViewModel @Inject constructor(...) : ViewModel()

// 4. Screen
@Composable
fun NewFeatureScreen(viewModel: NewFeatureViewModel = hiltViewModel())
```

#### 5. Tambahkan Navigation
```kotlin
composable("new_feature") {
    NewFeatureScreen()
}
```

### Best Practices

1. **Gunakan StateFlow** untuk reactive state management
2. **Validasi input** di layer ViewModel sebelum mengirim ke API
3. **Handle error** dengan baik menggunakan sealed Result class
4. **Pisahkan UI components** yang reusable ke folder `components/`
5. **Gunakan Preview** untuk development UI component
6. **Ikuti naming convention** yang konsisten

---

## 12. Konfigurasi Build

### Build Variants
- **Debug**: Menggunakan BASE_URL dari `local.properties`
- **Release**: Menggunakan BASE_URL production hardcoded

### local.properties (tidak di-commit ke git)

```text
BASE_URL=http://10.0.2.2:8000/api/
```

### Gradle Configuration
```kotlin
android {
    buildFeatures {
        compose = true
        buildConfig = true  // Untuk akses BuildConfig.BASE_URL
    }
}
```

### Menjalankan Aplikasi
```bash
# Debug build
./gradlew assembleDebug

# Release build
./gradlew assembleRelease

# Install ke device
./gradlew installDebug
```

### ProGuard
File konfigurasi: `app/proguard-rules.pro`
ProGuard dinonaktifkan untuk release build (`isMinifyEnabled = false`)

---

## Penutup

Dokumentasi ini mencakup seluruh aspek pengembangan aplikasi IMFIT. Untuk informasi lebih lanjut atau pertanyaan, silakan hubungi tim pengembang.

**Versi Dokumentasi**: 1.0  
**Tanggal Update**: Desember 2024  
**Dibuat oleh**: Tim Pengembang IMFIT

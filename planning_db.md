# IMFIT Backend Development Planning (Supabase)

## Dokumentasi Terkait

| Dokumen | Deskripsi |
|---------|-----------|
| **[README.md](./README.md)** | Dokumentasi utama aplikasi mobile |
| **[planning_db.md](./planning_db.md)** | Perencanaan Backend Supabase (dokumen ini) |
| **[schema_db.md](./schema_db.md)** | Database Schema & SQL Migration Scripts |

---

## Daftar Isi

1. [Executive Summary](#1-executive-summary)
2. [Analisis Kebutuhan](#2-analisis-kebutuhan)
3. [Arsitektur Supabase](#3-arsitektur-supabase)
4. [Spesifikasi Data](#4-spesifikasi-data)
5. [Supabase API & Client](#5-supabase-api--client)
6. [Authentication dengan Supabase Auth](#6-authentication-dengan-supabase-auth)
7. [Row Level Security (RLS)](#7-row-level-security-rls)
8. [Storage untuk Media](#8-storage-untuk-media)
9. [Edge Functions](#9-edge-functions)
10. [Implementation Roadmap](#10-implementation-roadmap)

---

## 1. Executive Summary

### 1.1 Tujuan Dokumen

Dokumen ini merupakan perencanaan lengkap untuk pengembangan Backend IMFIT menggunakan **Supabase** sebagai Backend-as-a-Service (BaaS). Supabase menyediakan:

- PostgreSQL Database dengan Row Level Security
- Authentication (Email, OAuth, Magic Link)
- Realtime subscriptions
- Storage untuk file uploads
- Edge Functions untuk custom logic
- Auto-generated REST & GraphQL APIs

### 1.2 Tech Stack

| Layer | Teknologi | Alasan |
|-------|-----------|--------|
| **BaaS** | Supabase | All-in-one backend solution |
| **Database** | PostgreSQL (Supabase) | Relational data, ACID compliance, RLS |
| **Auth** | Supabase Auth | Built-in JWT, OAuth providers |
| **Storage** | Supabase Storage | Profile photos, exercise images |
| **Functions** | Supabase Edge Functions | Custom server-side logic (Deno) |
| **Realtime** | Supabase Realtime | Live data synchronization |
| **Client** | supabase-kt | Official Kotlin client for Android |

### 1.3 Key Metrics Target

| Metric | Target |
|--------|--------|
| API Response Time | < 200ms (p95) |
| Concurrent Users | 10,000+ |
| Uptime | 99.9% (Supabase SLA) |
| Data Retention | 5 years |

### 1.4 Supabase Project Setup

```
Project URL: https://<project-id>.supabase.co
API URL: https://<project-id>.supabase.co/rest/v1
Auth URL: https://<project-id>.supabase.co/auth/v1
Storage URL: https://<project-id>.supabase.co/storage/v1
```

---

## 2. Analisis Kebutuhan

### 2.1 Functional Requirements

#### FR-01: User Management
| ID | Requirement | Priority | Supabase Feature |
|----|-------------|----------|------------------|
| FR-01.1 | User registration dengan email/password | HIGH | Supabase Auth |
| FR-01.2 | User login dengan JWT token | HIGH | Supabase Auth |
| FR-01.3 | Password reset via email | MEDIUM | Supabase Auth |
| FR-01.4 | Profile management (update, photo upload) | HIGH | Database + Storage |
| FR-01.5 | Account deactivation | LOW | Database |

#### FR-02: Exercise Management
| ID | Requirement | Priority | Supabase Feature |
|----|-------------|----------|------------------|
| FR-02.1 | Get all exercises | HIGH | PostgREST API |
| FR-02.2 | Filter exercises by muscle category | HIGH | PostgREST API |
| FR-02.3 | Search exercises by name | HIGH | Full-text search |
| FR-02.4 | Get exercise details | HIGH | PostgREST API |

#### FR-03: Workout Template Management
| ID | Requirement | Priority | Supabase Feature |
|----|-------------|----------|------------------|
| FR-03.1 | Create workout template | HIGH | PostgREST API + RLS |
| FR-03.2 | Update workout template | HIGH | PostgREST API + RLS |
| FR-03.3 | Delete workout template | HIGH | PostgREST API + RLS |
| FR-03.4 | List user's templates | HIGH | PostgREST API + RLS |
| FR-03.5 | Add/remove exercises from template | HIGH | PostgREST API |

#### FR-04: Active Workout Session
| ID | Requirement | Priority | Supabase Feature |
|----|-------------|----------|------------------|
| FR-04.1 | Start workout from template | HIGH | PostgREST API |
| FR-04.2 | Track exercise completion | HIGH | Realtime (optional) |
| FR-04.3 | Record set data (weight, reps) | HIGH | PostgREST API |
| FR-04.4 | Finish workout and save log | HIGH | Database Function |
| FR-04.5 | Cancel/discard workout | MEDIUM | PostgREST API |

#### FR-05: Workout History & Progress
| ID | Requirement | Priority | Supabase Feature |
|----|-------------|----------|------------------|
| FR-05.1 | Get workout history list | HIGH | PostgREST API |
| FR-05.2 | Get workout log details | HIGH | PostgREST API |
| FR-05.3 | Get workouts by date range | HIGH | PostgREST API |
| FR-05.4 | Calculate total volume | HIGH | Database Views |
| FR-05.5 | Progress statistics | MEDIUM | Database Functions |

### 2.2 Non-Functional Requirements

| Category | Requirement |
|----------|-------------|
| **Performance** | API response < 200ms for 95th percentile |
| **Scalability** | Supabase auto-scaling |
| **Security** | Row Level Security (RLS) on all tables |
| **Availability** | 99.9% uptime (Supabase Pro) |
| **Data Integrity** | ACID transactions |
| **Backup** | Supabase daily backups (Pro plan) |

---

## 3. Arsitektur Supabase

### 3.1 System Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                           CLIENTS                                    │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐              │
│  │ Android App  │  │   iOS App    │  │  Web Admin   │              │
│  │ (supabase-kt)│  │(supabase-swift)│ │(supabase-js) │              │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘              │
└─────────┼─────────────────┼─────────────────┼───────────────────────┘
          │                 │                 │
          ▼                 ▼                 ▼
┌─────────────────────────────────────────────────────────────────────┐
│                         SUPABASE                                     │
│  ┌────────────────────────────────────────────────────────────────┐ │
│  │                      API Gateway (Kong)                        │ │
│  │  • Rate Limiting    • JWT Validation    • CORS                 │ │
│  └────────────────────────────────────────────────────────────────┘ │
│                                │                                     │
│         ┌──────────────────────┼──────────────────────┐             │
│         ▼                      ▼                      ▼             │
│  ┌─────────────┐      ┌─────────────┐      ┌─────────────┐         │
│  │  GoTrue     │      │  PostgREST  │      │   Storage   │         │
│  │  (Auth)     │      │  (REST API) │      │   (S3)      │         │
│  └──────┬──────┘      └──────┬──────┘      └──────┬──────┘         │
│         │                    │                    │                 │
│         └────────────────────┼────────────────────┘                 │
│                              ▼                                      │
│  ┌────────────────────────────────────────────────────────────────┐ │
│  │                    PostgreSQL Database                         │ │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐         │ │
│  │  │   Tables     │  │     RLS      │  │   Functions  │         │ │
│  │  │   + Views    │  │   Policies   │  │   + Triggers │         │ │
│  │  └──────────────┘  └──────────────┘  └──────────────┘         │ │
│  └────────────────────────────────────────────────────────────────┘ │
│                              │                                      │
│  ┌────────────────────────────────────────────────────────────────┐ │
│  │                    Edge Functions (Deno)                       │ │
│  │  • Complex business logic    • Webhooks    • Scheduled jobs    │ │
│  └────────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────┘
```

### 3.2 Android Project Structure (Supabase Integration)

```
app/src/main/java/com/diajarkoding/imfit/
│
├── ImFitApplication.kt              # Application class (@HiltAndroidApp)
├── MainActivity.kt                  # Single Activity entry point
│
├── core/
│   └── supabase/
│       ├── SupabaseClient.kt        # Supabase client singleton
│       └── SupabaseConfig.kt        # Configuration
│
├── data/
│   ├── remote/
│   │   ├── dto/                     # Data Transfer Objects
│   │   │   ├── UserDto.kt
│   │   │   ├── ExerciseDto.kt
│   │   │   ├── WorkoutTemplateDto.kt
│   │   │   └── WorkoutLogDto.kt
│   │   └── mapper/                  # DTO to Domain mappers
│   │
│   └── repository/
│       ├── AuthRepositoryImpl.kt    # Supabase Auth
│       ├── ExerciseRepositoryImpl.kt # Supabase Database
│       ├── WorkoutRepositoryImpl.kt  # Supabase Database
│       └── StorageRepositoryImpl.kt  # Supabase Storage
│
├── di/
│   ├── AppModule.kt                 # Hilt modules
│   └── SupabaseModule.kt            # Supabase DI configuration
│
├── domain/
│   ├── model/                       # Domain models
│   └── repository/                  # Repository interfaces
│
└── presentation/                    # UI Layer (unchanged)
```

### 3.3 Supabase Client Setup (Kotlin)

```kotlin
// SupabaseModule.kt
@Module
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
            install(Realtime)
        }
    }
}
```

---

## 4. Spesifikasi Data

### 4.1 Entity Relationship Overview

```
┌─────────────────┐       ┌─────────────────────┐       ┌──────────────┐
│  auth.users     │       │  workout_templates  │       │   exercises  │
│  (Supabase)     │       │                     │       │              │
│ id          PK  │◄──────│ user_id (FK)        │       │ id (PK)      │
│ email           │       │ id (PK)             │       │ name         │
│ ...             │       │ name                │       │ category_id  │
└──────┬──────────┘       │ created_at          │       │ description  │
       │                  └─────────┬───────────┘       └──────┬───────┘
       │                            │                          │
       │  ┌─────────────────┐       │                          │
       └─►│    profiles     │       │                          │
          │                 │       │                          │
          │ id (PK = auth.id)│      ▼                          │
          │ name            │  ┌─────────────────────┐         │
          │ birth_date      │  │ template_exercises  │◄────────┘
          │ photo_url       │  │                     │
          │ created_at      │  │ id (PK)             │
          └─────────────────┘  │ template_id (FK)    │
                               │ exercise_id (FK)    │
                               │ sets, reps, rest    │
                               └─────────────────────┘
```

### 4.2 Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| Use `auth.users` for auth | Leverage Supabase built-in auth |
| Separate `profiles` table | Store additional user data linked to auth.users |
| UUID for all IDs | Consistency with Supabase auth.users.id |
| RLS on all user-owned tables | Security by default |
| Soft delete with `is_deleted` | Preserve data integrity |

---

## 5. Supabase API & Client

### 5.1 PostgREST Auto-generated API

Supabase auto-generates REST API dari database schema:

| Operation | HTTP Method | Endpoint |
|-----------|-------------|----------|
| Select | GET | `/rest/v1/table_name` |
| Insert | POST | `/rest/v1/table_name` |
| Update | PATCH | `/rest/v1/table_name?id=eq.value` |
| Upsert | POST | `/rest/v1/table_name` (with header) |
| Delete | DELETE | `/rest/v1/table_name?id=eq.value` |

### 5.2 Kotlin Client Examples

#### Select with Filters
```kotlin
// Get all exercises by category
suspend fun getExercisesByCategory(category: String): List<Exercise> {
    return supabase.from("exercises")
        .select()
        .filter {
            Exercise::muscleCategory eq category
            Exercise::isActive eq true
        }
        .decodeList<Exercise>()
}
```

#### Insert
```kotlin
// Create workout template
suspend fun createTemplate(template: WorkoutTemplate): WorkoutTemplate {
    return supabase.from("workout_templates")
        .insert(template)
        .decodeSingle<WorkoutTemplate>()
}
```

#### Update
```kotlin
// Update template
suspend fun updateTemplate(id: String, name: String) {
    supabase.from("workout_templates")
        .update({
            WorkoutTemplate::name setTo name
            WorkoutTemplate::updatedAt setTo Clock.System.now()
        }) {
            filter { WorkoutTemplate::id eq id }
        }
}
```

#### Delete
```kotlin
// Soft delete template
suspend fun deleteTemplate(id: String) {
    supabase.from("workout_templates")
        .update({
            WorkoutTemplate::isDeleted setTo true
        }) {
            filter { WorkoutTemplate::id eq id }
        }
}
```

### 5.3 Complex Queries

#### Join Tables
```kotlin
// Get template with exercises
suspend fun getTemplateWithExercises(templateId: String): TemplateWithExercises {
    return supabase.from("workout_templates")
        .select(Columns.raw("""
            *,
            template_exercises (
                *,
                exercises (*)
            )
        """))
        .filter { WorkoutTemplate::id eq templateId }
        .decodeSingle<TemplateWithExercises>()
}
```

#### Pagination
```kotlin
// Get workout history with pagination
suspend fun getWorkoutHistory(page: Int, limit: Int = 20): List<WorkoutLog> {
    val offset = (page - 1) * limit
    return supabase.from("workout_logs")
        .select()
        .order("date", Order.DESCENDING)
        .range(offset.toLong(), (offset + limit - 1).toLong())
        .decodeList<WorkoutLog>()
}
```

---

## 6. Authentication dengan Supabase Auth

### 6.1 Auth Flow

```
┌──────────────────────────────────────────────────────────────┐
│                     SUPABASE AUTH FLOW                        │
├──────────────────────────────────────────────────────────────┤
│                                                               │
│   ┌─────────┐                              ┌─────────────┐   │
│   │  User   │                              │  Supabase   │   │
│   │  App    │                              │    Auth     │   │
│   └────┬────┘                              └──────┬──────┘   │
│        │                                          │          │
│        │  1. signUp(email, password)              │          │
│        │─────────────────────────────────────────►│          │
│        │                                          │          │
│        │  2. Confirmation email sent              │          │
│        │◄─────────────────────────────────────────│          │
│        │                                          │          │
│        │  3. User clicks confirmation link        │          │
│        │─────────────────────────────────────────►│          │
│        │                                          │          │
│        │  4. Session (access_token, refresh_token)│          │
│        │◄─────────────────────────────────────────│          │
│        │                                          │          │
│        │  5. API calls with Bearer token          │          │
│        │─────────────────────────────────────────►│          │
│        │                                          │          │
│        │  6. Token refresh (automatic)            │          │
│        │◄────────────────────────────────────────►│          │
│                                                               │
└──────────────────────────────────────────────────────────────┘
```

### 6.2 Auth Implementation (Kotlin)

#### Sign Up
```kotlin
suspend fun signUp(email: String, password: String, name: String): AuthResult {
    try {
        supabase.auth.signUpWith(Email) {
            this.email = email
            this.password = password
            this.data = buildJsonObject {
                put("name", name)
            }
        }
        return AuthResult.Success
    } catch (e: Exception) {
        return AuthResult.Error(e.message ?: "Sign up failed")
    }
}
```

#### Sign In
```kotlin
suspend fun signIn(email: String, password: String): AuthResult {
    try {
        supabase.auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
        return AuthResult.Success
    } catch (e: Exception) {
        return AuthResult.Error(e.message ?: "Sign in failed")
    }
}
```

#### Sign Out
```kotlin
suspend fun signOut() {
    supabase.auth.signOut()
}
```

#### Session Management
```kotlin
// Listen to auth state changes
val authState: Flow<AuthState> = supabase.auth.sessionStatus

// Get current user
fun getCurrentUser(): User? = supabase.auth.currentUserOrNull()

// Get current session
fun getSession(): UserSession? = supabase.auth.currentSessionOrNull()
```

#### Password Reset
```kotlin
suspend fun resetPassword(email: String) {
    supabase.auth.resetPasswordForEmail(email)
}
```

### 6.3 Auth Trigger untuk Profile

```sql
-- Trigger: Buat profile otomatis saat user sign up
CREATE OR REPLACE FUNCTION public.handle_new_user()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO public.profiles (id, name, email)
    VALUES (
        NEW.id,
        COALESCE(NEW.raw_user_meta_data->>'name', split_part(NEW.email, '@', 1)),
        NEW.email
    );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;

CREATE TRIGGER on_auth_user_created
    AFTER INSERT ON auth.users
    FOR EACH ROW EXECUTE FUNCTION public.handle_new_user();
```

---

## 7. Row Level Security (RLS)

### 7.1 RLS Overview

Row Level Security memastikan user hanya bisa akses data mereka sendiri.

### 7.2 RLS Policies

#### Profiles
```sql
-- Enable RLS
ALTER TABLE profiles ENABLE ROW LEVEL SECURITY;

-- Users can view their own profile
CREATE POLICY "Users can view own profile"
    ON profiles FOR SELECT
    USING (auth.uid() = id);

-- Users can update their own profile
CREATE POLICY "Users can update own profile"
    ON profiles FOR UPDATE
    USING (auth.uid() = id);
```

#### Workout Templates
```sql
ALTER TABLE workout_templates ENABLE ROW LEVEL SECURITY;

-- Users can view their own templates
CREATE POLICY "Users can view own templates"
    ON workout_templates FOR SELECT
    USING (auth.uid() = user_id);

-- Users can create their own templates
CREATE POLICY "Users can create own templates"
    ON workout_templates FOR INSERT
    WITH CHECK (auth.uid() = user_id);

-- Users can update their own templates
CREATE POLICY "Users can update own templates"
    ON workout_templates FOR UPDATE
    USING (auth.uid() = user_id);

-- Users can delete their own templates
CREATE POLICY "Users can delete own templates"
    ON workout_templates FOR DELETE
    USING (auth.uid() = user_id);
```

#### Exercises (Public Read)
```sql
ALTER TABLE exercises ENABLE ROW LEVEL SECURITY;

-- Anyone authenticated can read exercises
CREATE POLICY "Authenticated users can view exercises"
    ON exercises FOR SELECT
    TO authenticated
    USING (true);
```

#### Workout Logs
```sql
ALTER TABLE workout_logs ENABLE ROW LEVEL SECURITY;

-- Users can only access their own workout logs
CREATE POLICY "Users can manage own workout logs"
    ON workout_logs FOR ALL
    USING (auth.uid() = user_id);
```

---

## 8. Storage untuk Media

### 8.1 Storage Buckets

| Bucket | Access | Purpose |
|--------|--------|---------|
| `avatars` | Private | User profile photos |
| `exercises` | Public | Exercise demonstration images |

### 8.2 Storage Policies

```sql
-- Bucket: avatars (private, user-specific)
CREATE POLICY "Users can upload their avatar"
    ON storage.objects FOR INSERT
    WITH CHECK (
        bucket_id = 'avatars' AND
        auth.uid()::text = (storage.foldername(name))[1]
    );

CREATE POLICY "Users can update their avatar"
    ON storage.objects FOR UPDATE
    USING (
        bucket_id = 'avatars' AND
        auth.uid()::text = (storage.foldername(name))[1]
    );

CREATE POLICY "Users can view their avatar"
    ON storage.objects FOR SELECT
    USING (
        bucket_id = 'avatars' AND
        auth.uid()::text = (storage.foldername(name))[1]
    );

-- Bucket: exercises (public read)
CREATE POLICY "Anyone can view exercise images"
    ON storage.objects FOR SELECT
    USING (bucket_id = 'exercises');
```

### 8.3 Storage Implementation (Kotlin)

#### Upload Profile Photo
```kotlin
suspend fun uploadProfilePhoto(userId: String, imageBytes: ByteArray): String {
    val fileName = "$userId/${UUID.randomUUID()}.jpg"
    
    supabase.storage.from("avatars")
        .upload(fileName, imageBytes) {
            upsert = true
            contentType = ContentType.Image.JPEG
        }
    
    return supabase.storage.from("avatars")
        .publicUrl(fileName)
}
```

#### Download Image
```kotlin
suspend fun getProfilePhotoUrl(userId: String, fileName: String): String {
    return supabase.storage.from("avatars")
        .publicUrl("$userId/$fileName")
}
```

---

## 9. Edge Functions

### 9.1 Use Cases untuk Edge Functions

| Function | Purpose |
|----------|---------|
| `complete-workout` | Complex transaction to save workout |
| `calculate-stats` | Calculate user statistics |
| `send-notification` | Push notification triggers |

### 9.2 Example: Complete Workout Function

```typescript
// supabase/functions/complete-workout/index.ts
import { serve } from "https://deno.land/std@0.168.0/http/server.ts"
import { createClient } from 'https://esm.sh/@supabase/supabase-js@2'

serve(async (req) => {
    const supabase = createClient(
        Deno.env.get('SUPABASE_URL') ?? '',
        Deno.env.get('SUPABASE_SERVICE_ROLE_KEY') ?? ''
    )
    
    const { sessionId } = await req.json()
    
    // Get active session
    const { data: session } = await supabase
        .from('active_sessions')
        .select('*')
        .eq('id', sessionId)
        .single()
    
    if (!session) {
        return new Response(
            JSON.stringify({ error: 'Session not found' }),
            { status: 404 }
        )
    }
    
    // Create workout log
    const { data: workoutLog } = await supabase
        .from('workout_logs')
        .insert({
            user_id: session.user_id,
            template_name: session.template_name,
            date: new Date().toISOString().split('T')[0],
            start_time: session.start_time,
            end_time: new Date().toISOString(),
            total_volume: session.session_data.totalVolume
        })
        .select()
        .single()
    
    // Delete active session
    await supabase
        .from('active_sessions')
        .delete()
        .eq('id', sessionId)
    
    return new Response(
        JSON.stringify({ workoutLogId: workoutLog.id }),
        { headers: { "Content-Type": "application/json" } }
    )
})
```

### 9.3 Invoke Edge Function (Kotlin)

```kotlin
suspend fun completeWorkout(sessionId: String): WorkoutLog {
    val response = supabase.functions.invoke("complete-workout") {
        body = buildJsonObject {
            put("sessionId", sessionId)
        }
    }
    return response.decodeAs<WorkoutLog>()
}
```

---

## 10. Implementation Roadmap

### Phase 1: Setup & Auth (Week 1)
- [x] Create Supabase project
- [ ] Setup database schema (run migrations)
- [ ] Configure RLS policies
- [ ] Implement Supabase Auth di Android
- [ ] Create profile trigger

### Phase 2: Core Data (Week 2)
- [ ] Seed exercise data
- [ ] Implement ExerciseRepository dengan Supabase
- [ ] Implement WorkoutTemplateRepository
- [ ] Setup Storage buckets

### Phase 3: Workout Features (Week 3)
- [ ] Active workout session management
- [ ] Workout logging
- [ ] Complete workout Edge Function
- [ ] Workout history queries

### Phase 4: Advanced Features (Week 4)
- [ ] Statistics calculations (database functions)
- [ ] Calendar view queries
- [ ] Profile photo upload
- [ ] Realtime subscriptions (optional)

### Phase 5: Testing & Polish (Week 5)
- [ ] Integration testing
- [ ] Performance optimization
- [ ] Error handling
- [ ] Offline support with caching

---

## Appendix A: Environment Variables

### Android (local.properties)
```properties
SUPABASE_URL=https://<project-id>.supabase.co
SUPABASE_ANON_KEY=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
```

### Supabase Dashboard
```
Project Settings > API > Project URL
Project Settings > API > anon/public key
```

---

## Appendix B: Supabase CLI Commands

```bash
# Login to Supabase
supabase login

# Link to project
supabase link --project-ref <project-id>

# Run migrations
supabase db push

# Generate types (for TypeScript)
supabase gen types typescript --project-id <project-id> > types.ts

# Deploy Edge Function
supabase functions deploy complete-workout

# View logs
supabase functions logs complete-workout
```

---

## Lihat Juga

- **[README.md](./README.md)** - Dokumentasi aplikasi mobile Android (Kotlin/Jetpack Compose)
- **[Database Schema](./schema_db.md)** - Detail ERD, definisi tabel, SQL migrations untuk Supabase

---

**Document Version**: 2.0 (Supabase)  
**Last Updated**: December 2024  
**Author**: IMFIT Development Team

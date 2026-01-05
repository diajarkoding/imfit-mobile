# IMFIT Database Schema (Supabase)

## Dokumentasi Terkait

| Dokumen | Deskripsi |
|---------|-----------|
| **[README.md](./README.md)** | Dokumentasi utama aplikasi mobile |
| **[planning_db.md](./planning_db.md)** | Perencanaan Backend Supabase |
| **[schema_db.md](./schema_db.md)** | Database Schema Supabase (dokumen ini) |

---

## Daftar Isi

1. [Overview](#1-overview)
2. [Entity Relationship Diagram](#2-entity-relationship-diagram)
3. [Table Definitions](#3-table-definitions)
4. [Row Level Security (RLS)](#4-row-level-security-rls)
5. [Indexes](#5-indexes)
6. [SQL Migration Scripts](#6-sql-migration-scripts)
7. [Seed Data](#7-seed-data)
8. [Database Functions](#8-database-functions)
9. [Triggers](#9-triggers)
10. [Views](#10-views)

---

## 1. Overview

### 1.1 Database Information

| Property | Value |
|----------|-------|
| **Platform** | Supabase |
| **DBMS** | PostgreSQL 15+ |
| **Character Set** | UTF-8 |
| **Timezone** | UTC |
| **Auth** | Supabase Auth (auth.users) |

### 1.2 Naming Conventions

| Type | Convention | Example |
|------|------------|---------|
| Tables | snake_case, plural | `profiles`, `workout_logs` |
| Columns | snake_case | `created_at`, `user_id` |
| Primary Keys | `id` | `id` |
| Foreign Keys | `{table}_id` | `user_id`, `exercise_id` |
| Indexes | `idx_{table}_{columns}` | `idx_profiles_user` |
| RLS Policies | descriptive | `"Users can view own profile"` |

### 1.3 Supabase Schema Structure

```
├── auth (Supabase managed)
│   └── users                    # Managed by Supabase Auth
│
├── public (Application data)
│   ├── profiles                 # Extended user info
│   ├── muscle_categories        # Exercise categories
│   ├── exercises                # Exercise library
│   ├── workout_templates        # User's workout plans
│   ├── template_exercises       # Exercises in templates
│   ├── workout_logs             # Completed workouts
│   ├── exercise_logs            # Exercises in workout
│   ├── workout_sets             # Individual sets
│   └── active_sessions          # Active workout sessions
│
└── storage (Supabase Storage)
    ├── avatars                  # Profile photos
    └── exercises                # Exercise images
```

### 1.4 Table Summary

| Table | Description | RLS |
|-------|-------------|-----|
| `profiles` | Extended user data (linked to auth.users) | Yes |
| `muscle_categories` | Exercise categories | Public Read |
| `exercises` | Exercise library | Public Read |
| `workout_templates` | User's workout plans | Yes |
| `template_exercises` | Exercises in templates | Yes |
| `workout_logs` | Completed workout history | Yes |
| `exercise_logs` | Exercises in workout log | Yes |
| `workout_sets` | Individual set records | Yes |
| `active_sessions` | Currently running workouts | Yes |

---

## 2. Entity Relationship Diagram

### 2.1 Full ERD

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         IMFIT SUPABASE DATABASE ERD                          │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────┐                 
│   auth.users    │ (Supabase Managed)
├─────────────────┤                 
│ id          PK  │◄─────────────────────────────────────────────┐
│ email           │                                              │
│ encrypted_pass  │                                              │
│ created_at      │                                              │
└────────┬────────┘                                              │
         │                                                       │
         │ 1:1                                                   │
         ▼                                                       │
┌─────────────────┐       ┌─────────────────────┐               │
│    profiles     │       │  workout_templates  │               │
├─────────────────┤       ├─────────────────────┤               │
│ id (PK=auth.id) │       │ id              PK  │               │
│ name            │       │ user_id         FK  │───────────────┤
│ email           │       │ name                │               │
│ birth_date      │       │ description         │               │
│ avatar_url      │       │ is_deleted          │               │
│ created_at      │       │ created_at          │               │
│ updated_at      │       │ updated_at          │               │
└─────────────────┘       └─────────┬───────────┘               │
                                    │                            │
                                    │ 1:N                        │
         ┌─────────────────────┐    │                            │
         │  muscle_categories  │    │                            │
         ├─────────────────────┤    │                            │
         │ id              PK  │    │                            │
         │ name                │    │                            │
         │ display_name        │    │                            │
         │ sort_order          │    │                            │
         └─────────┬───────────┘    │                            │
                   │                │                            │
                   │ 1:N            │                            │
                   ▼                ▼                            │
         ┌─────────────────┐  ┌─────────────────────┐           │
         │   exercises     │  │ template_exercises  │           │
         ├─────────────────┤  ├─────────────────────┤           │
         │ id          PK  │◄─│ exercise_id     FK  │           │
         │ category_id FK  │  │ template_id     FK  │───────────┤
         │ name            │  │ id              PK  │           │
         │ description     │  │ order_index         │           │
         │ image_url       │  │ sets                │           │
         │ difficulty      │  │ reps                │           │
         │ is_active       │  │ rest_seconds        │           │
         └────────┬────────┘  └─────────────────────┘           │
                  │                                              │
                  │                                              │
         ┌────────┴───────────────────────────────┐              │
         │                                        │              │
         ▼                                        │              │
┌─────────────────────┐                           │              │
│    workout_logs     │                           │              │
├─────────────────────┤                           │              │
│ id              PK  │                           │              │
│ user_id         FK  │───────────────────────────┼──────────────┘
│ template_name       │                           │
│ date                │                           │
│ start_time          │                           │
│ end_time            │                           │
│ total_volume        │                           │
│ total_sets          │                           │
│ total_reps          │                           │
│ notes               │                           │
└─────────┬───────────┘                           │
          │                                       │
          │ 1:N                                   │
          ▼                                       │
┌─────────────────────┐                           │
│   exercise_logs     │                           │
├─────────────────────┤                           │
│ id              PK  │                           │
│ workout_log_id  FK  │                           │
│ exercise_id     FK  │───────────────────────────┘
│ exercise_name       │
│ muscle_category     │
│ order_index         │
│ total_volume        │
└─────────┬───────────┘
          │
          │ 1:N
          ▼
┌─────────────────────┐
│    workout_sets     │
├─────────────────────┤
│ id              PK  │
│ exercise_log_id FK  │
│ set_number          │
│ weight              │
│ reps                │
│ is_completed        │
│ is_warmup           │
└─────────────────────┘

┌─────────────────────┐
│  active_sessions    │
├─────────────────────┤
│ id              PK  │
│ user_id      FK,UQ  │──► auth.users
│ template_id     FK  │──► workout_templates
│ template_name       │
│ start_time          │
│ session_data   JSON │
│ expires_at          │
└─────────────────────┘
```

---

## 3. Table Definitions

### 3.1 profiles

Extended user information linked to Supabase Auth.

| Column | Type | Nullable | Default | Description |
|--------|------|----------|---------|-------------|
| `id` | UUID | NO | - | PK, references auth.users.id |
| `name` | VARCHAR(100) | NO | - | User's full name |
| `email` | VARCHAR(255) | NO | - | Email (synced from auth) |
| `birth_date` | DATE | YES | NULL | Date of birth |
| `avatar_url` | TEXT | YES | NULL | Profile image URL |
| `created_at` | TIMESTAMPTZ | NO | now() | Record creation |
| `updated_at` | TIMESTAMPTZ | NO | now() | Record update |

---

### 3.2 muscle_categories

| Column | Type | Nullable | Default | Description |
|--------|------|----------|---------|-------------|
| `id` | SERIAL | NO | auto | Primary key |
| `name` | VARCHAR(50) | NO | - | Category code |
| `display_name` | VARCHAR(100) | NO | - | Display name |
| `icon_name` | VARCHAR(50) | YES | NULL | Icon identifier |
| `sort_order` | INTEGER | NO | 0 | Display order |
| `created_at` | TIMESTAMPTZ | NO | now() | Record creation |

---

### 3.3 exercises

| Column | Type | Nullable | Default | Description |
|--------|------|----------|---------|-------------|
| `id` | VARCHAR(50) | NO | - | Primary key |
| `muscle_category_id` | INTEGER | NO | - | FK to muscle_categories |
| `name` | VARCHAR(100) | NO | - | Exercise name |
| `description` | TEXT | NO | - | How to perform |
| `image_url` | TEXT | YES | NULL | Exercise image |
| `video_url` | TEXT | YES | NULL | Tutorial video |
| `difficulty` | VARCHAR(20) | YES | 'INTERMEDIATE' | Difficulty level |
| `equipment` | VARCHAR(100) | YES | NULL | Required equipment |
| `is_active` | BOOLEAN | NO | true | Available for selection |
| `created_at` | TIMESTAMPTZ | NO | now() | Record creation |
| `updated_at` | TIMESTAMPTZ | NO | now() | Record update |

---

### 3.4 workout_templates

| Column | Type | Nullable | Default | Description |
|--------|------|----------|---------|-------------|
| `id` | UUID | NO | gen_random_uuid() | Primary key |
| `user_id` | UUID | NO | - | FK to auth.users |
| `name` | VARCHAR(100) | NO | - | Template name |
| `description` | TEXT | YES | NULL | Template description |
| `estimated_duration` | INTEGER | YES | NULL | Minutes |
| `is_deleted` | BOOLEAN | NO | false | Soft delete flag |
| `created_at` | TIMESTAMPTZ | NO | now() | Record creation |
| `updated_at` | TIMESTAMPTZ | NO | now() | Record update |

---

### 3.5 template_exercises

| Column | Type | Nullable | Default | Description |
|--------|------|----------|---------|-------------|
| `id` | UUID | NO | gen_random_uuid() | Primary key |
| `template_id` | UUID | NO | - | FK to workout_templates |
| `exercise_id` | VARCHAR(50) | NO | - | FK to exercises |
| `order_index` | INTEGER | NO | 0 | Display order |
| `sets` | INTEGER | NO | 3 | Number of sets |
| `reps` | INTEGER | NO | 10 | Target reps |
| `rest_seconds` | INTEGER | NO | 60 | Rest between sets |
| `notes` | VARCHAR(255) | YES | NULL | Exercise notes |
| `created_at` | TIMESTAMPTZ | NO | now() | Record creation |
| `updated_at` | TIMESTAMPTZ | NO | now() | Record update |

---

### 3.6 workout_logs

| Column | Type | Nullable | Default | Description |
|--------|------|----------|---------|-------------|
| `id` | UUID | NO | gen_random_uuid() | Primary key |
| `user_id` | UUID | NO | - | FK to auth.users |
| `template_id` | UUID | YES | NULL | Original template |
| `template_name` | VARCHAR(100) | NO | - | Template name snapshot |
| `date` | DATE | NO | - | Workout date |
| `start_time` | TIMESTAMPTZ | NO | - | Workout start |
| `end_time` | TIMESTAMPTZ | NO | - | Workout end |
| `total_volume` | DECIMAL(12,2) | NO | 0 | Sum of weight × reps |
| `total_sets` | INTEGER | NO | 0 | Total sets completed |
| `total_reps` | INTEGER | NO | 0 | Total reps completed |
| `notes` | TEXT | YES | NULL | Workout notes |
| `rating` | INTEGER | YES | NULL | User rating 1-5 |
| `created_at` | TIMESTAMPTZ | NO | now() | Record creation |

---

### 3.7 exercise_logs

| Column | Type | Nullable | Default | Description |
|--------|------|----------|---------|-------------|
| `id` | UUID | NO | gen_random_uuid() | Primary key |
| `workout_log_id` | UUID | NO | - | FK to workout_logs |
| `exercise_id` | VARCHAR(50) | NO | - | FK to exercises |
| `exercise_name` | VARCHAR(100) | NO | - | Exercise name snapshot |
| `muscle_category` | VARCHAR(50) | NO | - | Category snapshot |
| `order_index` | INTEGER | NO | 0 | Order in workout |
| `total_volume` | DECIMAL(10,2) | NO | 0 | Sum for this exercise |
| `created_at` | TIMESTAMPTZ | NO | now() | Record creation |

---

### 3.8 workout_sets

| Column | Type | Nullable | Default | Description |
|--------|------|----------|---------|-------------|
| `id` | UUID | NO | gen_random_uuid() | Primary key |
| `exercise_log_id` | UUID | NO | - | FK to exercise_logs |
| `set_number` | INTEGER | NO | - | Set order (1, 2, 3...) |
| `weight` | DECIMAL(6,2) | NO | 0 | Weight in kg |
| `reps` | INTEGER | NO | 0 | Reps completed |
| `is_completed` | BOOLEAN | NO | false | Set completed flag |
| `is_warmup` | BOOLEAN | NO | false | Warmup set flag |
| `notes` | VARCHAR(255) | YES | NULL | Set notes |
| `created_at` | TIMESTAMPTZ | NO | now() | Record creation |
| `updated_at` | TIMESTAMPTZ | NO | now() | Record update |

---

### 3.9 active_sessions

| Column | Type | Nullable | Default | Description |
|--------|------|----------|---------|-------------|
| `id` | UUID | NO | gen_random_uuid() | Primary key |
| `user_id` | UUID | NO | - | FK to auth.users (unique) |
| `template_id` | UUID | NO | - | FK to workout_templates |
| `template_name` | VARCHAR(100) | NO | - | Template name snapshot |
| `start_time` | TIMESTAMPTZ | NO | now() | Session start |
| `current_exercise_index` | INTEGER | NO | 0 | Current position |
| `session_data` | JSONB | NO | '{}' | Full session state |
| `last_activity_at` | TIMESTAMPTZ | NO | now() | Last update |
| `expires_at` | TIMESTAMPTZ | NO | - | Session expiration |
| `created_at` | TIMESTAMPTZ | NO | now() | Record creation |
| `updated_at` | TIMESTAMPTZ | NO | now() | Record update |

---

## 4. Row Level Security (RLS)

### 4.1 Enable RLS on All Tables

```sql
ALTER TABLE profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE workout_templates ENABLE ROW LEVEL SECURITY;
ALTER TABLE template_exercises ENABLE ROW LEVEL SECURITY;
ALTER TABLE workout_logs ENABLE ROW LEVEL SECURITY;
ALTER TABLE exercise_logs ENABLE ROW LEVEL SECURITY;
ALTER TABLE workout_sets ENABLE ROW LEVEL SECURITY;
ALTER TABLE active_sessions ENABLE ROW LEVEL SECURITY;
ALTER TABLE muscle_categories ENABLE ROW LEVEL SECURITY;
ALTER TABLE exercises ENABLE ROW LEVEL SECURITY;
```

### 4.2 Profiles Policies

```sql
CREATE POLICY "Users can view own profile"
    ON profiles FOR SELECT
    USING (auth.uid() = id);

CREATE POLICY "Users can update own profile"
    ON profiles FOR UPDATE
    USING (auth.uid() = id);
```

### 4.3 Public Read Tables

```sql
-- Muscle Categories (public read)
CREATE POLICY "Anyone can view muscle categories"
    ON muscle_categories FOR SELECT
    TO authenticated
    USING (true);

-- Exercises (public read)
CREATE POLICY "Anyone can view exercises"
    ON exercises FOR SELECT
    TO authenticated
    USING (true);
```

### 4.4 User-Owned Tables

```sql
-- Workout Templates
CREATE POLICY "Users can view own templates"
    ON workout_templates FOR SELECT
    USING (auth.uid() = user_id);

CREATE POLICY "Users can create own templates"
    ON workout_templates FOR INSERT
    WITH CHECK (auth.uid() = user_id);

CREATE POLICY "Users can update own templates"
    ON workout_templates FOR UPDATE
    USING (auth.uid() = user_id);

CREATE POLICY "Users can delete own templates"
    ON workout_templates FOR DELETE
    USING (auth.uid() = user_id);

-- Template Exercises (via template ownership)
CREATE POLICY "Users can manage template exercises"
    ON template_exercises FOR ALL
    USING (
        EXISTS (
            SELECT 1 FROM workout_templates
            WHERE id = template_exercises.template_id
            AND user_id = auth.uid()
        )
    );

-- Workout Logs
CREATE POLICY "Users can manage own workout logs"
    ON workout_logs FOR ALL
    USING (auth.uid() = user_id);

-- Exercise Logs (via workout log ownership)
CREATE POLICY "Users can manage exercise logs"
    ON exercise_logs FOR ALL
    USING (
        EXISTS (
            SELECT 1 FROM workout_logs
            WHERE id = exercise_logs.workout_log_id
            AND user_id = auth.uid()
        )
    );

-- Workout Sets (via exercise log ownership)
CREATE POLICY "Users can manage workout sets"
    ON workout_sets FOR ALL
    USING (
        EXISTS (
            SELECT 1 FROM exercise_logs el
            JOIN workout_logs wl ON wl.id = el.workout_log_id
            WHERE el.id = workout_sets.exercise_log_id
            AND wl.user_id = auth.uid()
        )
    );

-- Active Sessions
CREATE POLICY "Users can manage own sessions"
    ON active_sessions FOR ALL
    USING (auth.uid() = user_id);
```

---

## 5. Indexes

### 5.1 Performance Indexes

```sql
-- Profiles
CREATE INDEX idx_profiles_email ON profiles(email);

-- Exercises
CREATE INDEX idx_exercises_category ON exercises(muscle_category_id);
CREATE INDEX idx_exercises_name_search ON exercises USING gin(to_tsvector('english', name));
CREATE INDEX idx_exercises_active ON exercises(is_active) WHERE is_active = true;

-- Workout Templates
CREATE INDEX idx_templates_user ON workout_templates(user_id);
CREATE INDEX idx_templates_user_active ON workout_templates(user_id) WHERE is_deleted = false;
CREATE UNIQUE INDEX idx_templates_user_name ON workout_templates(user_id, name) WHERE is_deleted = false;

-- Template Exercises
CREATE INDEX idx_template_exercises_template ON template_exercises(template_id);
CREATE UNIQUE INDEX idx_template_exercises_order ON template_exercises(template_id, order_index);

-- Workout Logs
CREATE INDEX idx_workout_logs_user ON workout_logs(user_id);
CREATE INDEX idx_workout_logs_user_date ON workout_logs(user_id, date DESC);
CREATE INDEX idx_workout_logs_date ON workout_logs(date);

-- Exercise Logs
CREATE INDEX idx_exercise_logs_workout ON exercise_logs(workout_log_id);

-- Workout Sets
CREATE INDEX idx_workout_sets_exercise_log ON workout_sets(exercise_log_id);
CREATE UNIQUE INDEX idx_workout_sets_number ON workout_sets(exercise_log_id, set_number);

-- Active Sessions
CREATE UNIQUE INDEX idx_active_sessions_user ON active_sessions(user_id);
CREATE INDEX idx_active_sessions_expires ON active_sessions(expires_at);
```

---

## 6. SQL Migration Scripts

### 6.1 Complete Migration Script

```sql
-- ============================================
-- IMFIT Supabase Migration Script
-- Run this in Supabase SQL Editor
-- ============================================

-- Enable required extensions
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ============================================
-- TABLE: profiles
-- ============================================
CREATE TABLE IF NOT EXISTS profiles (
    id UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    email VARCHAR(255) NOT NULL,
    birth_date DATE,
    avatar_url TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ============================================
-- TABLE: muscle_categories
-- ============================================
CREATE TABLE IF NOT EXISTS muscle_categories (
    id SERIAL PRIMARY KEY,
    name VARCHAR(50) NOT NULL UNIQUE,
    display_name VARCHAR(100) NOT NULL,
    icon_name VARCHAR(50),
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ============================================
-- TABLE: exercises
-- ============================================
CREATE TABLE IF NOT EXISTS exercises (
    id VARCHAR(50) PRIMARY KEY,
    muscle_category_id INTEGER NOT NULL REFERENCES muscle_categories(id) ON DELETE RESTRICT,
    name VARCHAR(100) NOT NULL UNIQUE,
    description TEXT NOT NULL,
    image_url TEXT,
    video_url TEXT,
    difficulty VARCHAR(20) DEFAULT 'INTERMEDIATE' CHECK (difficulty IN ('BEGINNER', 'INTERMEDIATE', 'ADVANCED')),
    equipment VARCHAR(100),
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ============================================
-- TABLE: workout_templates
-- ============================================
CREATE TABLE IF NOT EXISTS workout_templates (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL CHECK (char_length(name) >= 2),
    description TEXT,
    estimated_duration INTEGER,
    is_deleted BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ============================================
-- TABLE: template_exercises
-- ============================================
CREATE TABLE IF NOT EXISTS template_exercises (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    template_id UUID NOT NULL REFERENCES workout_templates(id) ON DELETE CASCADE,
    exercise_id VARCHAR(50) NOT NULL REFERENCES exercises(id) ON DELETE RESTRICT,
    order_index INTEGER NOT NULL DEFAULT 0 CHECK (order_index >= 0),
    sets INTEGER NOT NULL DEFAULT 3 CHECK (sets BETWEEN 1 AND 20),
    reps INTEGER NOT NULL DEFAULT 10 CHECK (reps BETWEEN 1 AND 100),
    rest_seconds INTEGER NOT NULL DEFAULT 60 CHECK (rest_seconds BETWEEN 0 AND 600),
    notes VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ============================================
-- TABLE: workout_logs
-- ============================================
CREATE TABLE IF NOT EXISTS workout_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    template_id UUID REFERENCES workout_templates(id) ON DELETE SET NULL,
    template_name VARCHAR(100) NOT NULL,
    date DATE NOT NULL,
    start_time TIMESTAMPTZ NOT NULL,
    end_time TIMESTAMPTZ NOT NULL CHECK (end_time > start_time),
    total_volume DECIMAL(12,2) NOT NULL DEFAULT 0 CHECK (total_volume >= 0),
    total_sets INTEGER NOT NULL DEFAULT 0,
    total_reps INTEGER NOT NULL DEFAULT 0,
    notes TEXT,
    rating INTEGER CHECK (rating IS NULL OR rating BETWEEN 1 AND 5),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ============================================
-- TABLE: exercise_logs
-- ============================================
CREATE TABLE IF NOT EXISTS exercise_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workout_log_id UUID NOT NULL REFERENCES workout_logs(id) ON DELETE CASCADE,
    exercise_id VARCHAR(50) NOT NULL REFERENCES exercises(id) ON DELETE RESTRICT,
    exercise_name VARCHAR(100) NOT NULL,
    muscle_category VARCHAR(50) NOT NULL,
    order_index INTEGER NOT NULL DEFAULT 0 CHECK (order_index >= 0),
    total_volume DECIMAL(10,2) NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ============================================
-- TABLE: workout_sets
-- ============================================
CREATE TABLE IF NOT EXISTS workout_sets (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    exercise_log_id UUID NOT NULL REFERENCES exercise_logs(id) ON DELETE CASCADE,
    set_number INTEGER NOT NULL CHECK (set_number >= 1),
    weight DECIMAL(6,2) NOT NULL DEFAULT 0 CHECK (weight >= 0 AND weight <= 1000),
    reps INTEGER NOT NULL DEFAULT 0 CHECK (reps >= 0 AND reps <= 1000),
    is_completed BOOLEAN NOT NULL DEFAULT false,
    is_warmup BOOLEAN NOT NULL DEFAULT false,
    notes VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ============================================
-- TABLE: active_sessions
-- ============================================
CREATE TABLE IF NOT EXISTS active_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL UNIQUE REFERENCES auth.users(id) ON DELETE CASCADE,
    template_id UUID NOT NULL REFERENCES workout_templates(id) ON DELETE CASCADE,
    template_name VARCHAR(100) NOT NULL,
    start_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    current_exercise_index INTEGER NOT NULL DEFAULT 0,
    session_data JSONB NOT NULL DEFAULT '{}',
    last_activity_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ============================================
-- INDEXES
-- ============================================
CREATE INDEX IF NOT EXISTS idx_profiles_email ON profiles(email);
CREATE INDEX IF NOT EXISTS idx_exercises_category ON exercises(muscle_category_id);
CREATE INDEX IF NOT EXISTS idx_exercises_active ON exercises(is_active) WHERE is_active = true;
CREATE INDEX IF NOT EXISTS idx_templates_user ON workout_templates(user_id);
CREATE INDEX IF NOT EXISTS idx_templates_user_active ON workout_templates(user_id) WHERE is_deleted = false;
CREATE UNIQUE INDEX IF NOT EXISTS idx_templates_user_name ON workout_templates(user_id, name) WHERE is_deleted = false;
CREATE INDEX IF NOT EXISTS idx_template_exercises_template ON template_exercises(template_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_template_exercises_order ON template_exercises(template_id, order_index);
CREATE INDEX IF NOT EXISTS idx_workout_logs_user ON workout_logs(user_id);
CREATE INDEX IF NOT EXISTS idx_workout_logs_user_date ON workout_logs(user_id, date DESC);
CREATE INDEX IF NOT EXISTS idx_exercise_logs_workout ON exercise_logs(workout_log_id);
CREATE INDEX IF NOT EXISTS idx_workout_sets_exercise_log ON workout_sets(exercise_log_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_workout_sets_number ON workout_sets(exercise_log_id, set_number);
CREATE UNIQUE INDEX IF NOT EXISTS idx_active_sessions_user ON active_sessions(user_id);

-- ============================================
-- ROW LEVEL SECURITY
-- ============================================

-- Enable RLS
ALTER TABLE profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE muscle_categories ENABLE ROW LEVEL SECURITY;
ALTER TABLE exercises ENABLE ROW LEVEL SECURITY;
ALTER TABLE workout_templates ENABLE ROW LEVEL SECURITY;
ALTER TABLE template_exercises ENABLE ROW LEVEL SECURITY;
ALTER TABLE workout_logs ENABLE ROW LEVEL SECURITY;
ALTER TABLE exercise_logs ENABLE ROW LEVEL SECURITY;
ALTER TABLE workout_sets ENABLE ROW LEVEL SECURITY;
ALTER TABLE active_sessions ENABLE ROW LEVEL SECURITY;

-- Profiles
CREATE POLICY "Users can view own profile" ON profiles FOR SELECT USING (auth.uid() = id);
CREATE POLICY "Users can update own profile" ON profiles FOR UPDATE USING (auth.uid() = id);

-- Public read tables
CREATE POLICY "Authenticated can view categories" ON muscle_categories FOR SELECT TO authenticated USING (true);
CREATE POLICY "Authenticated can view exercises" ON exercises FOR SELECT TO authenticated USING (true);

-- Workout Templates
CREATE POLICY "Users can view own templates" ON workout_templates FOR SELECT USING (auth.uid() = user_id);
CREATE POLICY "Users can create own templates" ON workout_templates FOR INSERT WITH CHECK (auth.uid() = user_id);
CREATE POLICY "Users can update own templates" ON workout_templates FOR UPDATE USING (auth.uid() = user_id);
CREATE POLICY "Users can delete own templates" ON workout_templates FOR DELETE USING (auth.uid() = user_id);

-- Template Exercises
CREATE POLICY "Users can manage template exercises" ON template_exercises FOR ALL
    USING (EXISTS (SELECT 1 FROM workout_templates WHERE id = template_exercises.template_id AND user_id = auth.uid()));

-- Workout Logs
CREATE POLICY "Users can manage own workout logs" ON workout_logs FOR ALL USING (auth.uid() = user_id);

-- Exercise Logs
CREATE POLICY "Users can manage exercise logs" ON exercise_logs FOR ALL
    USING (EXISTS (SELECT 1 FROM workout_logs WHERE id = exercise_logs.workout_log_id AND user_id = auth.uid()));

-- Workout Sets
CREATE POLICY "Users can manage workout sets" ON workout_sets FOR ALL
    USING (EXISTS (
        SELECT 1 FROM exercise_logs el
        JOIN workout_logs wl ON wl.id = el.workout_log_id
        WHERE el.id = workout_sets.exercise_log_id AND wl.user_id = auth.uid()
    ));

-- Active Sessions
CREATE POLICY "Users can manage own sessions" ON active_sessions FOR ALL USING (auth.uid() = user_id);
```

---

## 7. Seed Data

### 7.1 Muscle Categories

```sql
INSERT INTO muscle_categories (name, display_name, icon_name, sort_order) VALUES
('CHEST', 'Chest', 'chest', 1),
('BACK', 'Back', 'back', 2),
('SHOULDERS', 'Shoulders', 'shoulders', 3),
('BICEPS', 'Biceps', 'biceps', 4),
('TRICEPS', 'Triceps', 'triceps', 5),
('LEGS', 'Legs', 'legs', 6),
('CORE', 'Core', 'core', 7),
('CARDIO', 'Cardio', 'cardio', 8)
ON CONFLICT (name) DO NOTHING;
```

### 7.2 Exercises

```sql
INSERT INTO exercises (id, muscle_category_id, name, description, difficulty) VALUES
-- CHEST
('ex_chest_1', 1, 'Barbell Bench Press', 'Lie on flat bench, grip barbell wider than shoulders, lower to chest, press up.', 'INTERMEDIATE'),
('ex_chest_2', 1, 'Incline Dumbbell Press', 'Set bench to 30-45 degrees, press dumbbells up from shoulder level.', 'INTERMEDIATE'),
('ex_chest_3', 1, 'Cable Flyes', 'Stand between cables, bring handles together in front of chest.', 'BEGINNER'),
('ex_chest_4', 1, 'Push-Ups', 'Standard push-up, lower until chest nearly touches floor, push back up.', 'BEGINNER'),
('ex_chest_5', 1, 'Dumbbell Flyes', 'Lie on bench, extend arms above chest, lower in arc motion to sides.', 'INTERMEDIATE'),

-- BACK
('ex_back_1', 2, 'Deadlift', 'Stand feet hip-width, grip barbell, lift by extending hips and knees.', 'ADVANCED'),
('ex_back_2', 2, 'Pull-Ups', 'Hang from bar with overhand grip, pull up until chin clears bar.', 'INTERMEDIATE'),
('ex_back_3', 2, 'Barbell Row', 'Bend at hips, grip barbell, pull to lower chest.', 'INTERMEDIATE'),
('ex_back_4', 2, 'Lat Pulldown', 'Sit at machine, pull bar down to upper chest.', 'BEGINNER'),
('ex_back_5', 2, 'Seated Cable Row', 'Sit at cable machine, pull handle to lower chest.', 'BEGINNER'),

-- SHOULDERS
('ex_shoulders_1', 3, 'Overhead Press', 'Stand with barbell at shoulders, press overhead.', 'INTERMEDIATE'),
('ex_shoulders_2', 3, 'Lateral Raises', 'Hold dumbbells at sides, raise laterally to shoulder height.', 'BEGINNER'),
('ex_shoulders_3', 3, 'Front Raises', 'Hold dumbbells, raise one arm at a time to shoulder height.', 'BEGINNER'),
('ex_shoulders_4', 3, 'Face Pulls', 'Pull rope toward face, separating hands.', 'BEGINNER'),
('ex_shoulders_5', 3, 'Arnold Press', 'Start dumbbells in front, rotate and press overhead.', 'INTERMEDIATE'),

-- BICEPS
('ex_biceps_1', 4, 'Barbell Curl', 'Stand with barbell, curl up by flexing elbows.', 'BEGINNER'),
('ex_biceps_2', 4, 'Dumbbell Curl', 'Alternating curls with dumbbells.', 'BEGINNER'),
('ex_biceps_3', 4, 'Hammer Curl', 'Curl with neutral grip throughout.', 'BEGINNER'),
('ex_biceps_4', 4, 'Preacher Curl', 'Rest arms on preacher bench, curl up.', 'INTERMEDIATE'),
('ex_biceps_5', 4, 'Cable Curl', 'Stand facing cable, curl bar up.', 'BEGINNER'),

-- TRICEPS
('ex_triceps_1', 5, 'Tricep Pushdown', 'Push bar down at cable machine.', 'BEGINNER'),
('ex_triceps_2', 5, 'Skull Crushers', 'Lie on bench, lower barbell toward forehead.', 'INTERMEDIATE'),
('ex_triceps_3', 5, 'Overhead Tricep Extension', 'Hold dumbbell overhead, lower behind head.', 'BEGINNER'),
('ex_triceps_4', 5, 'Close-Grip Bench Press', 'Bench press with narrow grip.', 'INTERMEDIATE'),
('ex_triceps_5', 5, 'Tricep Dips', 'Support on parallel bars, lower and push up.', 'INTERMEDIATE'),

-- LEGS
('ex_legs_1', 6, 'Barbell Squat', 'Bar on back, squat until thighs parallel.', 'INTERMEDIATE'),
('ex_legs_2', 6, 'Leg Press', 'Push platform away at leg press machine.', 'BEGINNER'),
('ex_legs_3', 6, 'Romanian Deadlift', 'Hinge at hips, lower weight along legs.', 'INTERMEDIATE'),
('ex_legs_4', 6, 'Leg Curl', 'Lie face down, curl weight up.', 'BEGINNER'),
('ex_legs_5', 6, 'Leg Extension', 'Sit in machine, extend legs.', 'BEGINNER'),
('ex_legs_6', 6, 'Calf Raises', 'Raise heels as high as possible.', 'BEGINNER'),
('ex_legs_7', 6, 'Lunges', 'Step forward, lower back knee.', 'BEGINNER'),

-- CORE
('ex_core_1', 7, 'Plank', 'Hold push-up position on forearms.', 'BEGINNER'),
('ex_core_2', 7, 'Crunches', 'Lie on back, curl shoulders toward hips.', 'BEGINNER'),
('ex_core_3', 7, 'Russian Twist', 'Sit, lean back, rotate torso.', 'BEGINNER'),
('ex_core_4', 7, 'Leg Raises', 'Lie on back, raise legs vertical.', 'INTERMEDIATE'),
('ex_core_5', 7, 'Cable Woodchop', 'Pull cable diagonally across body.', 'INTERMEDIATE'),

-- CARDIO
('ex_cardio_1', 8, 'Treadmill Running', 'Run on treadmill.', 'BEGINNER'),
('ex_cardio_2', 8, 'Stationary Bike', 'Cycle on stationary bike.', 'BEGINNER'),
('ex_cardio_3', 8, 'Rowing Machine', 'Full body cardio with rowing.', 'INTERMEDIATE'),
('ex_cardio_4', 8, 'Jump Rope', 'Skip rope continuously.', 'BEGINNER'),
('ex_cardio_5', 8, 'Burpees', 'Full body exercise with jump.', 'INTERMEDIATE')
ON CONFLICT (id) DO NOTHING;
```

---

## 8. Database Functions

### 8.1 Auto-create Profile on Signup

```sql
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
```

### 8.2 Update Timestamp Function

```sql
CREATE OR REPLACE FUNCTION update_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
```

### 8.3 Calculate Workout Stats

```sql
CREATE OR REPLACE FUNCTION get_user_workout_stats(p_user_id UUID)
RETURNS TABLE (
    total_workouts BIGINT,
    total_volume DECIMAL,
    avg_duration_minutes DECIMAL,
    workouts_this_week BIGINT,
    workouts_this_month BIGINT
) AS $$
BEGIN
    RETURN QUERY
    SELECT 
        COUNT(*)::BIGINT,
        COALESCE(SUM(wl.total_volume), 0),
        COALESCE(AVG(EXTRACT(EPOCH FROM (wl.end_time - wl.start_time)) / 60), 0),
        COUNT(*) FILTER (WHERE wl.date >= CURRENT_DATE - INTERVAL '7 days')::BIGINT,
        COUNT(*) FILTER (WHERE wl.date >= DATE_TRUNC('month', CURRENT_DATE))::BIGINT
    FROM workout_logs wl
    WHERE wl.user_id = p_user_id;
END;
$$ LANGUAGE plpgsql SECURITY DEFINER;
```

---

## 9. Triggers

### 9.1 Create Triggers

```sql
-- Profile creation on signup
CREATE TRIGGER on_auth_user_created
    AFTER INSERT ON auth.users
    FOR EACH ROW
    EXECUTE FUNCTION public.handle_new_user();

-- Updated_at triggers
CREATE TRIGGER update_profiles_updated_at
    BEFORE UPDATE ON profiles
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

CREATE TRIGGER update_exercises_updated_at
    BEFORE UPDATE ON exercises
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

CREATE TRIGGER update_workout_templates_updated_at
    BEFORE UPDATE ON workout_templates
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

CREATE TRIGGER update_template_exercises_updated_at
    BEFORE UPDATE ON template_exercises
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

CREATE TRIGGER update_workout_sets_updated_at
    BEFORE UPDATE ON workout_sets
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();

CREATE TRIGGER update_active_sessions_updated_at
    BEFORE UPDATE ON active_sessions
    FOR EACH ROW EXECUTE FUNCTION update_updated_at();
```

---

## 10. Views

### 10.1 User Workout Stats View

```sql
CREATE OR REPLACE VIEW v_user_workout_stats AS
SELECT 
    u.id AS user_id,
    p.name AS user_name,
    COUNT(DISTINCT wl.id) AS total_workouts,
    COALESCE(SUM(wl.total_volume), 0) AS lifetime_volume,
    COALESCE(AVG(EXTRACT(EPOCH FROM (wl.end_time - wl.start_time)) / 60), 0) AS avg_duration_minutes,
    MAX(wl.date) AS last_workout_date,
    COUNT(*) FILTER (WHERE wl.date >= CURRENT_DATE - INTERVAL '7 days') AS workouts_this_week,
    COUNT(*) FILTER (WHERE wl.date >= DATE_TRUNC('month', CURRENT_DATE)) AS workouts_this_month
FROM auth.users u
LEFT JOIN profiles p ON p.id = u.id
LEFT JOIN workout_logs wl ON u.id = wl.user_id
GROUP BY u.id, p.name;
```

### 10.2 Workout Calendar View

```sql
CREATE OR REPLACE VIEW v_workout_calendar AS
SELECT 
    user_id,
    date,
    COUNT(*) AS workout_count,
    SUM(total_volume) AS daily_volume,
    ARRAY_AGG(template_name) AS workout_names
FROM workout_logs
GROUP BY user_id, date;
```

---

## Lihat Juga

- **[README.md](./README.md)** - Dokumentasi aplikasi mobile Android
- **[Backend Planning](./planning_db.md)** - Arsitektur backend Supabase, API client, dan roadmap

---

**Schema Version**: 2.0 (Supabase)  
**Last Updated**: December 2024  
**Author**: IMFIT Development Team

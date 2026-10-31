-- IMFIT Phase 1 / expand + catalog mapping, source hybrid@b4215300.
-- PRECONDITIONS: Phase 0 completed and restore PASS; migration drift reconciled.
-- Deliberately does NOT change existing RPCs, reps NOT NULL, or old workout metrics.
-- Execute as one migration using Supabase CLI. On scratch restore: psql --single-transaction.
-- Catalog mapping in this migration is an explicit, reviewed 161-ID fixture.
-- No historic workout logs are marked VERIFIED.

SET LOCAL lock_timeout = '8s';
SET LOCAL statement_timeout = '120s';

CREATE TYPE public.tracking_type_enum AS ENUM (
  'WEIGHT_REPS', 'BODYWEIGHT_REPS', 'ASSISTED_REPS',
  'REPS_ONLY', 'DURATION', 'DISTANCE_DURATION'
);
CREATE TYPE public.exercise_modality_enum AS ENUM (
  'STRENGTH', 'CALISTHENICS', 'ISOMETRIC', 'CARDIO', 'CONDITIONING'
);

-- NULL during expand; classify actual catalog first, enforce NOT NULL only after backfill.
ALTER TABLE public.exercises
  ADD COLUMN default_tracking_type public.tracking_type_enum,
  ADD COLUMN modality public.exercise_modality_enum;

CREATE TABLE public.exercise_tracking_modes (
  exercise_id VARCHAR(50) NOT NULL REFERENCES public.exercises(id) ON DELETE CASCADE,
  tracking_type public.tracking_type_enum NOT NULL,
  PRIMARY KEY (exercise_id, tracking_type)
);
ALTER TABLE public.exercise_tracking_modes ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.exercise_tracking_modes FROM PUBLIC, anon, authenticated;
CREATE POLICY exercise_tracking_modes_authenticated_read
  ON public.exercise_tracking_modes FOR SELECT TO authenticated USING (true);
GRANT SELECT ON public.exercise_tracking_modes TO authenticated;

-- v1 clients still require template_exercises.reps as NOT NULL, with DEFAULT 10.
-- Keep tracking_type NULL for legacy templates; Phase 2 must populate for typed v2.
ALTER TABLE public.template_exercises
  ADD COLUMN tracking_type public.tracking_type_enum,
  ADD COLUMN target_duration_seconds INTEGER CHECK
    (target_duration_seconds IS NULL OR target_duration_seconds BETWEEN 1 AND 86400),
  ADD COLUMN target_distance_meters INTEGER CHECK
    (target_distance_meters IS NULL OR target_distance_meters BETWEEN 1 AND 1000000);

ALTER TABLE public.workout_sets
  ADD COLUMN duration_seconds INTEGER CHECK
    (duration_seconds IS NULL OR duration_seconds >= 0),
  ADD COLUMN distance_meters INTEGER CHECK
    (distance_meters IS NULL OR distance_meters >= 0),
  ADD COLUMN additional_weight NUMERIC(7,2) CHECK
    (additional_weight IS NULL OR additional_weight >= 0),
  ADD COLUMN assistance_weight NUMERIC(7,2) CHECK
    (assistance_weight IS NULL OR assistance_weight >= 0);

-- Nullable tracking/modality preserve unknown semantics of old persisted logs.
ALTER TABLE public.exercise_logs
  ADD COLUMN tracking_type public.tracking_type_enum,
  ADD COLUMN modality public.exercise_modality_enum,
  ADD COLUMN total_duration_seconds INTEGER NOT NULL DEFAULT 0
    CHECK (total_duration_seconds >= 0),
  ADD COLUMN total_distance_meters INTEGER NOT NULL DEFAULT 0
    CHECK (total_distance_meters >= 0),
  ADD COLUMN additional_volume NUMERIC(12,2) NOT NULL DEFAULT 0
    CHECK (additional_volume >= 0),
  ADD COLUMN metric_quality TEXT NOT NULL DEFAULT 'LEGACY_UNVERIFIED'
    CHECK (metric_quality IN ('VERIFIED','LEGACY_UNVERIFIED'));

ALTER TABLE public.workout_logs
  ADD COLUMN total_duration_seconds INTEGER NOT NULL DEFAULT 0
    CHECK (total_duration_seconds >= 0),
  ADD COLUMN total_distance_meters INTEGER NOT NULL DEFAULT 0
    CHECK (total_distance_meters >= 0),
  ADD COLUMN additional_volume NUMERIC(12,2) NOT NULL DEFAULT 0
    CHECK (additional_volume >= 0),
  ADD COLUMN data_quality TEXT NOT NULL DEFAULT 'LEGACY_UNVERIFIED'
    CHECK (data_quality IN ('VERIFIED','LEGACY_UNVERIFIED'));

-- Accept 1/2 in storage only. New v2 RPC writes remain BLOCKED until Phase 2.
ALTER TABLE public.active_sessions
  DROP CONSTRAINT active_sessions_schema_version_check;
ALTER TABLE public.active_sessions
  ADD CONSTRAINT active_sessions_schema_version_check
  CHECK (schema_version IN (1,2));

CREATE TEMP TABLE _imfit_phase1_fixture (
  exercise_id VARCHAR(50) PRIMARY KEY,
  modality public.exercise_modality_enum NOT NULL,
  default_tracking_type public.tracking_type_enum NOT NULL,
  allowed_modes public.tracking_type_enum[] NOT NULL,
  CONSTRAINT nonempty_modes CHECK (COALESCE(array_length(allowed_modes,1),0)>0)
);

INSERT INTO _imfit_phase1_fixture
  (exercise_id, modality, default_tracking_type, allowed_modes)
VALUES
  ('ex_back_1', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_back_2', 'CALISTHENICS', 'BODYWEIGHT_REPS', ARRAY['BODYWEIGHT_REPS','REPS_ONLY']::public.tracking_type_enum[]),
  ('ex_back_3', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_back_4', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_back_5', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_back_6', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_back_7', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_back_8', 'CALISTHENICS', 'BODYWEIGHT_REPS', ARRAY['BODYWEIGHT_REPS','REPS_ONLY']::public.tracking_type_enum[]),
  ('ex_back_9', 'CALISTHENICS', 'BODYWEIGHT_REPS', ARRAY['BODYWEIGHT_REPS','REPS_ONLY']::public.tracking_type_enum[]),
  ('ex_back_10', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_back_11', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_back_12', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_back_13', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_back_14', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_back_15', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_back_16', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_back_17', 'STRENGTH', 'ASSISTED_REPS', ARRAY['ASSISTED_REPS']::public.tracking_type_enum[]),
  ('ex_back_18', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_back_19', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_back_20', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_biceps_1', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_biceps_2', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_biceps_3', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_biceps_4', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_biceps_5', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_biceps_6', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_biceps_7', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_biceps_8', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_biceps_9', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_biceps_10', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_biceps_11', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_biceps_12', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_biceps_13', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_biceps_14', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_biceps_15', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_biceps_16', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_biceps_17', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_biceps_18', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_biceps_19', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_biceps_20', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_cardio_1', 'CARDIO', 'DISTANCE_DURATION', ARRAY['DISTANCE_DURATION','DURATION']::public.tracking_type_enum[]),
  ('ex_cardio_2', 'CARDIO', 'DISTANCE_DURATION', ARRAY['DISTANCE_DURATION','DURATION']::public.tracking_type_enum[]),
  ('ex_cardio_3', 'CARDIO', 'DISTANCE_DURATION', ARRAY['DISTANCE_DURATION','DURATION']::public.tracking_type_enum[]),
  ('ex_cardio_4', 'CARDIO', 'REPS_ONLY', ARRAY['REPS_ONLY','DURATION']::public.tracking_type_enum[]),
  ('ex_cardio_5', 'CONDITIONING', 'REPS_ONLY', ARRAY['REPS_ONLY','DURATION']::public.tracking_type_enum[]),
  ('ex_cardio_6', 'CARDIO', 'DISTANCE_DURATION', ARRAY['DISTANCE_DURATION','DURATION']::public.tracking_type_enum[]),
  ('ex_cardio_7', 'CARDIO', 'DISTANCE_DURATION', ARRAY['DISTANCE_DURATION','DURATION']::public.tracking_type_enum[]),
  ('ex_cardio_8', 'CONDITIONING', 'REPS_ONLY', ARRAY['REPS_ONLY']::public.tracking_type_enum[]),
  ('ex_cardio_9', 'CARDIO', 'REPS_ONLY', ARRAY['REPS_ONLY','DURATION']::public.tracking_type_enum[]),
  ('ex_cardio_10', 'CARDIO', 'REPS_ONLY', ARRAY['REPS_ONLY','DURATION']::public.tracking_type_enum[]),
  ('ex_cardio_11', 'CARDIO', 'DISTANCE_DURATION', ARRAY['DISTANCE_DURATION','DURATION']::public.tracking_type_enum[]),
  ('ex_cardio_12', 'CARDIO', 'DISTANCE_DURATION', ARRAY['DISTANCE_DURATION','DURATION']::public.tracking_type_enum[]),
  ('ex_cardio_13', 'CARDIO', 'DISTANCE_DURATION', ARRAY['DISTANCE_DURATION','DURATION']::public.tracking_type_enum[]),
  ('ex_cardio_14', 'CARDIO', 'DISTANCE_DURATION', ARRAY['DISTANCE_DURATION','DURATION']::public.tracking_type_enum[]),
  ('ex_cardio_15', 'CONDITIONING', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_cardio_16', 'CONDITIONING', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_cardio_17', 'CONDITIONING', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_cardio_18', 'CONDITIONING', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_cardio_19', 'CONDITIONING', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_cardio_20', 'CONDITIONING', 'REPS_ONLY', ARRAY['REPS_ONLY','DURATION']::public.tracking_type_enum[]),
  ('ex_chest_1', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_chest_2', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_chest_3', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_chest_4', 'CALISTHENICS', 'BODYWEIGHT_REPS', ARRAY['BODYWEIGHT_REPS','REPS_ONLY']::public.tracking_type_enum[]),
  ('ex_chest_5', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_chest_6', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_chest_7', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_chest_8', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_chest_9', 'CALISTHENICS', 'BODYWEIGHT_REPS', ARRAY['BODYWEIGHT_REPS','REPS_ONLY']::public.tracking_type_enum[]),
  ('ex_chest_10', 'CALISTHENICS', 'BODYWEIGHT_REPS', ARRAY['BODYWEIGHT_REPS','REPS_ONLY']::public.tracking_type_enum[]),
  ('ex_chest_11', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_chest_12', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_chest_13', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_chest_14', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_chest_15', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_chest_16', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_chest_17', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_chest_18', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_chest_19', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_chest_20', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_core_1', 'ISOMETRIC', 'DURATION', ARRAY['DURATION']::public.tracking_type_enum[]),
  ('ex_core_2', 'CALISTHENICS', 'BODYWEIGHT_REPS', ARRAY['BODYWEIGHT_REPS','REPS_ONLY']::public.tracking_type_enum[]),
  ('ex_core_3', 'CALISTHENICS', 'BODYWEIGHT_REPS', ARRAY['BODYWEIGHT_REPS','REPS_ONLY']::public.tracking_type_enum[]),
  ('ex_core_4', 'CALISTHENICS', 'BODYWEIGHT_REPS', ARRAY['BODYWEIGHT_REPS','REPS_ONLY']::public.tracking_type_enum[]),
  ('ex_core_5', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_core_6', 'CALISTHENICS', 'BODYWEIGHT_REPS', ARRAY['BODYWEIGHT_REPS','REPS_ONLY']::public.tracking_type_enum[]),
  ('ex_core_7', 'CALISTHENICS', 'BODYWEIGHT_REPS', ARRAY['BODYWEIGHT_REPS','REPS_ONLY']::public.tracking_type_enum[]),
  ('ex_core_8', 'CONDITIONING', 'REPS_ONLY', ARRAY['REPS_ONLY','DURATION']::public.tracking_type_enum[]),
  ('ex_core_9', 'ISOMETRIC', 'DURATION', ARRAY['DURATION']::public.tracking_type_enum[]),
  ('ex_core_10', 'CALISTHENICS', 'BODYWEIGHT_REPS', ARRAY['BODYWEIGHT_REPS','REPS_ONLY']::public.tracking_type_enum[]),
  ('ex_core_11', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_core_12', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_core_13', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_core_14', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_core_15', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_core_16', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_core_17', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_core_18', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_core_19', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_core_20', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_legs_1', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_legs_2', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_legs_3', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_legs_4', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_legs_5', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_legs_6', 'CALISTHENICS', 'BODYWEIGHT_REPS', ARRAY['BODYWEIGHT_REPS','REPS_ONLY']::public.tracking_type_enum[]),
  ('ex_legs_7', 'CALISTHENICS', 'BODYWEIGHT_REPS', ARRAY['BODYWEIGHT_REPS','REPS_ONLY']::public.tracking_type_enum[]),
  ('ex_legs_8', 'CALISTHENICS', 'BODYWEIGHT_REPS', ARRAY['BODYWEIGHT_REPS','REPS_ONLY']::public.tracking_type_enum[]),
  ('ex_legs_9', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_legs_10', 'CALISTHENICS', 'BODYWEIGHT_REPS', ARRAY['BODYWEIGHT_REPS','REPS_ONLY']::public.tracking_type_enum[]),
  ('ex_legs_11', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_legs_12', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_legs_13', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_legs_14', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_legs_15', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_legs_16', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_legs_17', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_legs_18', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_legs_19', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_legs_20', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_legs_21', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_shoulders_1', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_shoulders_2', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_shoulders_3', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_shoulders_4', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_shoulders_5', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_shoulders_6', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_shoulders_7', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_shoulders_8', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_shoulders_9', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_shoulders_10', 'CALISTHENICS', 'BODYWEIGHT_REPS', ARRAY['BODYWEIGHT_REPS','REPS_ONLY']::public.tracking_type_enum[]),
  ('ex_shoulders_11', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_shoulders_12', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_shoulders_13', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_shoulders_14', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_shoulders_15', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_shoulders_16', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_shoulders_17', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_shoulders_18', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_shoulders_19', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_shoulders_20', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_triceps_1', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_triceps_2', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_triceps_3', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_triceps_4', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_triceps_5', 'CALISTHENICS', 'BODYWEIGHT_REPS', ARRAY['BODYWEIGHT_REPS','REPS_ONLY']::public.tracking_type_enum[]),
  ('ex_triceps_6', 'CALISTHENICS', 'BODYWEIGHT_REPS', ARRAY['BODYWEIGHT_REPS','REPS_ONLY']::public.tracking_type_enum[]),
  ('ex_triceps_7', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_triceps_8', 'CALISTHENICS', 'BODYWEIGHT_REPS', ARRAY['BODYWEIGHT_REPS','REPS_ONLY']::public.tracking_type_enum[]),
  ('ex_triceps_9', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_triceps_10', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_triceps_11', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_triceps_12', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_triceps_13', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_triceps_14', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_triceps_15', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_triceps_16', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_triceps_17', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_triceps_18', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_triceps_19', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]),
  ('ex_triceps_20', 'STRENGTH', 'WEIGHT_REPS', ARRAY['WEIGHT_REPS']::public.tracking_type_enum[]);

-- Fail-fast validation before mutating catalog data.
DO $phase1_preflight$
BEGIN
  IF (SELECT COUNT(*) FROM public.exercises) <> 161
     OR (SELECT COUNT(*) FROM _imfit_phase1_fixture) <> 161 THEN
    RAISE EXCEPTION 'Phase 1 aborted: live catalog or fixture count is not 161';
  END IF;
  IF EXISTS (
      SELECT 1 FROM public.exercises e
      FULL OUTER JOIN _imfit_phase1_fixture f ON e.id=f.exercise_id
      WHERE e.id IS NULL OR f.exercise_id IS NULL
  ) THEN
    RAISE EXCEPTION 'Phase 1 aborted: catalog/fixture ID sets differ';
  END IF;
  IF EXISTS (
      SELECT 1 FROM _imfit_phase1_fixture f
      WHERE NOT f.default_tracking_type = ANY(f.allowed_modes)
  ) THEN
    RAISE EXCEPTION 'Phase 1 aborted: a default is not in allowed modes';
  END IF;
  IF EXISTS (
      SELECT 1 FROM _imfit_phase1_fixture f,
           unnest(f.allowed_modes) AS a(mode)
      GROUP BY f.exercise_id, a.mode HAVING COUNT(*)>1
  ) THEN
    RAISE EXCEPTION 'Phase 1 aborted: duplicate allowed modes';
  END IF;
END;
$phase1_preflight$;

UPDATE public.exercises e
  SET modality = f.modality,
      default_tracking_type = f.default_tracking_type
  FROM _imfit_phase1_fixture f
  WHERE f.exercise_id = e.id;

INSERT INTO public.exercise_tracking_modes (exercise_id,tracking_type)
SELECT f.exercise_id, m.tracking_type
FROM _imfit_phase1_fixture f
CROSS JOIN LATERAL unnest(f.allowed_modes) AS m(tracking_type);

-- No global WEIGHT_REPS default: new exercise insertion must be explicitly classified.
ALTER TABLE public.exercises
  ALTER COLUMN default_tracking_type SET NOT NULL,
  ALTER COLUMN modality SET NOT NULL;

DO $phase1_postflight$
BEGIN
  IF EXISTS (
    SELECT 1 FROM public.exercises e
    LEFT JOIN public.exercise_tracking_modes m ON m.exercise_id=e.id
    GROUP BY e.id, e.default_tracking_type, e.modality
    HAVING e.default_tracking_type IS NULL OR e.modality IS NULL
      OR COUNT(m.tracking_type)=0
      OR COUNT(m.tracking_type) FILTER (WHERE m.tracking_type=e.default_tracking_type)=0
  ) THEN
    RAISE EXCEPTION 'Phase 1 aborted: exercise missing default, modality or allowed mode';
  END IF;
  IF EXISTS (
    SELECT 1 FROM public.exercise_logs
    WHERE metric_quality <> 'LEGACY_UNVERIFIED'
  ) OR EXISTS (
    SELECT 1 FROM public.workout_logs
    WHERE data_quality <> 'LEGACY_UNVERIFIED'
  ) THEN
    RAISE EXCEPTION 'Phase 1 aborted: legacy data must not be marked VERIFIED';
  END IF;
  IF (SELECT COUNT(*) FROM public.exercise_tracking_modes) <> (
    SELECT COALESCE(SUM(array_length(allowed_modes,1)),0)
    FROM _imfit_phase1_fixture
  ) THEN
    RAISE EXCEPTION 'Phase 1 aborted: mode row count does not match fixture';
  END IF;
END;
$phase1_postflight$;

DROP TABLE _imfit_phase1_fixture;

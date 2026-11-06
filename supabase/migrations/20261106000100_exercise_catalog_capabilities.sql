-- T02/T03: catalog capabilities, explicit backfill, and validation are atomic.
-- PRECONDITIONS: T01 live/repository reconciliation and isolated backup/restore PASS.
-- Apply through the migration runner (one transaction), or psql --single-transaction.
-- Historical RPC validators, tracking-mode relations, and snapshots stay unchanged.
-- LIVE CUTOVER GATE: resolve legacy read/upload metadata fallbacks first; see
-- docs/plans/PLAN_ARCHITECTURAL_EXERCISES_REFACTOR.md section 11.5.

SET LOCAL lock_timeout = '8s';
SET LOCAL statement_timeout = '120s';

ALTER TABLE public.exercises
  ADD COLUMN tracking_profile TEXT NULL,
  ADD COLUMN supported_metrics TEXT[] NOT NULL DEFAULT '{}'::TEXT[];

-- PostgreSQL TEXT[] accepts multiple dimensions, arbitrary lower bounds, NULL
-- members and duplicates. Catalog capability lists accept none of those shapes.
-- The ordinary zero-dimensional empty array is valid for every supported profile.
-- Count DISTINCT also rejects NULL members: cardinality includes them, COUNT does not.
CREATE FUNCTION imfit_private.exercise_supported_metrics_valid(p_metrics TEXT[])
RETURNS BOOLEAN
LANGUAGE sql
IMMUTABLE
SECURITY INVOKER
SET search_path = ''
AS $function$
  SELECT COALESCE(
    p_metrics IS NOT NULL
    AND (
      p_metrics = '{}'::TEXT[]
      OR (
        pg_catalog.array_ndims(p_metrics) = 1
        AND pg_catalog.array_lower(p_metrics, 1) = 1
      )
    )
    AND p_metrics <@ ARRAY['calories', 'distance', 'speed', 'laps', 'incline']::TEXT[]
    AND pg_catalog.cardinality(p_metrics) = (
      SELECT pg_catalog.count(DISTINCT metric)
      FROM pg_catalog.unnest(p_metrics) AS metrics(metric)
    ),
    FALSE
  );
$function$;

-- CHECK evaluation needs EXECUTE for the inserting/updating role. Catalog writes
-- use postgres (function owner) or service_role; authenticated/anon are not catalog
-- writers. Keep schema USAGE and the existing catalog table privileges unchanged:
-- the stored CHECK references the helper by OID, not by caller-side name lookup.
REVOKE ALL ON FUNCTION imfit_private.exercise_supported_metrics_valid(TEXT[])
  FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION imfit_private.exercise_supported_metrics_valid(TEXT[])
  TO service_role;

ALTER TABLE public.exercises
  ADD CONSTRAINT exercises_catalog_capabilities_check CHECK (
    imfit_private.exercise_supported_metrics_valid(supported_metrics)
    AND COALESCE(
      CASE modality
        WHEN 'STRENGTH' THEN
          default_tracking_type = 'WEIGHT_REPS'
          AND tracking_profile IS NULL
          AND supported_metrics = '{}'::TEXT[]
        WHEN 'ISOMETRIC' THEN
          default_tracking_type = 'DURATION'
          AND tracking_profile IS NULL
          AND supported_metrics = '{}'::TEXT[]
        WHEN 'CARDIO' THEN
          default_tracking_type = 'DURATION'
          AND CASE tracking_profile
            WHEN 'duration_only' THEN
              supported_metrics = '{}'::TEXT[]
            WHEN 'distance_activity' THEN
              supported_metrics <@ ARRAY['distance', 'speed', 'laps']::TEXT[]
            WHEN 'equipment_metrics' THEN
              supported_metrics <@ ARRAY['calories', 'distance', 'speed', 'laps', 'incline']::TEXT[]
            ELSE FALSE
          END
        WHEN 'CONDITIONING' THEN
          default_tracking_type IN ('WEIGHT_REPS', 'DURATION')
          AND tracking_profile IS NULL
          AND supported_metrics = '{}'::TEXT[]
        -- CALISTHENICS remains in the legacy enum/snapshots, not new catalog rows.
        ELSE FALSE
      END,
      FALSE
    )
  ) NOT VALID;

COMMENT ON COLUMN public.exercises.tracking_profile IS
  'Catalog-only Cardio profile: duration_only, distance_activity or equipment_metrics; NULL for non-Cardio. Historical snapshots retain their original semantics.';
COMMENT ON COLUMN public.exercises.supported_metrics IS
  'Unique optional Cardio metric keys supported by this exercise; empty is valid. Does not rewrite historical results or snapshots.';
COMMENT ON FUNCTION imfit_private.exercise_supported_metrics_valid(TEXT[]) IS
  'Immutable, null-safe catalog list validation: ordinary empty or one-dimensional 1-based TEXT[] with distinct non-NULL known metric keys.';
COMMENT ON CONSTRAINT exercises_catalog_capabilities_check ON public.exercises IS
  'Catalog category/format/profile/capability contract; legacy history and RPC validators are unchanged.';

-- Explicit fixture reviewed against the live catalog on 2026-11-06. Names are
-- drift guards during migration, never runtime classification rules.
CREATE TEMP TABLE _catalog_cardio_capabilities (
  exercise_id TEXT PRIMARY KEY,
  expected_name TEXT NOT NULL,
  tracking_profile TEXT NOT NULL,
  supported_metrics TEXT[] NOT NULL
) ON COMMIT DROP;

INSERT INTO _catalog_cardio_capabilities VALUES
  ('ex_cardio_1', 'Treadmill Running', 'equipment_metrics',
    ARRAY['calories','distance','speed','incline']),
  ('ex_cardio_2', 'Stationary Bike', 'equipment_metrics',
    ARRAY['calories','distance','speed']),
  ('ex_cardio_3', 'Rowing Machine', 'equipment_metrics',
    ARRAY['calories','distance']),
  ('ex_cardio_4', 'Jump Rope', 'duration_only', '{}'::TEXT[]),
  ('ex_cardio_6', 'Elliptical Trainer', 'equipment_metrics',
    ARRAY['calories','distance']),
  ('ex_cardio_7', 'Stair Climber', 'equipment_metrics',
    ARRAY['calories']),
  ('ex_cardio_9', 'High Knees', 'duration_only', '{}'::TEXT[]),
  ('ex_cardio_10', 'Jumping Jacks', 'duration_only', '{}'::TEXT[]),
  ('ex_cardio_11', 'Assault AirBike Sprint', 'equipment_metrics',
    ARRAY['calories','distance','speed']),
  ('ex_cardio_12', 'SkiErg Machine', 'equipment_metrics',
    ARRAY['calories','distance']),
  ('ex_cardio_13', 'Incline Treadmill Power Walk', 'equipment_metrics',
    ARRAY['calories','distance','speed','incline']),
  ('ex_cardio_14', 'Recumbent Bike Cycling', 'equipment_metrics',
    ARRAY['calories','distance','speed']);

DO $catalog_preflight$
BEGIN
  IF EXISTS (
    SELECT 1 FROM public.exercises e
    FULL JOIN _catalog_cardio_capabilities f ON f.exercise_id = e.id
    WHERE (e.modality = 'CARDIO' AND f.exercise_id IS NULL)
       OR (f.exercise_id IS NOT NULL AND (
         e.id IS NULL OR e.modality IS DISTINCT FROM 'CARDIO'
         OR e.name IS DISTINCT FROM f.expected_name
       ))
  ) THEN
    RAISE EXCEPTION 'Catalog Cardio fixture differs from database; reconcile IDs, names and capabilities before retrying';
  END IF;
END;
$catalog_preflight$;

UPDATE public.exercises e
SET default_tracking_type = 'DURATION',
    tracking_profile = f.tracking_profile,
    supported_metrics = f.supported_metrics
FROM _catalog_cardio_capabilities f
WHERE e.id = f.exercise_id;

-- New catalog semantics only. Do not update template_exercises, session JSON,
-- exercise_logs, workout_sets, receipts or any immutable pending payload.
UPDATE public.exercises
SET modality = 'STRENGTH', default_tracking_type = 'WEIGHT_REPS'
WHERE modality IN ('STRENGTH','CALISTHENICS')
  AND (modality <> 'STRENGTH' OR default_tracking_type <> 'WEIGHT_REPS');

-- CONDITIONING stays descriptive. Repetition-based conditioning becomes timed
-- for new configurations; existing typed REPS_ONLY snapshots retain that mode.
UPDATE public.exercises
SET default_tracking_type = 'DURATION'
WHERE modality = 'CONDITIONING' AND default_tracking_type = 'REPS_ONLY';

-- RPCs still use this existing relation to admit legacy pending typed uploads.
-- Add canonical defaults; NEVER remove legacy modes during catalog backfill.
INSERT INTO public.exercise_tracking_modes (exercise_id, tracking_type)
SELECT id, default_tracking_type FROM public.exercises
ON CONFLICT (exercise_id, tracking_type) DO NOTHING;

ALTER TABLE public.exercises
  VALIDATE CONSTRAINT exercises_catalog_capabilities_check;

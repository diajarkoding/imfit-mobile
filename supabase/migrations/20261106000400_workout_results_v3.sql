-- T06 expand: preserve every legacy value/default; new fields have no v3 default.
-- Snapshot association/format/capability validation belongs to versioned writer RPCs.
SET LOCAL lock_timeout = '8s';
SET LOCAL statement_timeout = '120s';

ALTER TABLE public.workout_sets
  ALTER COLUMN weight DROP NOT NULL,
  ALTER COLUMN reps DROP NOT NULL,
  ALTER COLUMN distance_meters TYPE NUMERIC(12,2) USING distance_meters::NUMERIC(12,2),
  ADD COLUMN measurement_version SMALLINT NULL,
  ADD COLUMN target_row_id TEXT NULL,
  ADD COLUMN calories_kcal NUMERIC(12,2) NULL,
  ADD COLUMN speed_kph NUMERIC(12,2) NULL,
  ADD COLUMN lap_reps INTEGER NULL,
  ADD COLUMN incline_percent NUMERIC(5,2) NULL,
  ADD CONSTRAINT workout_sets_v3_metric_ranges_check CHECK (
    (distance_meters IS NULL OR (distance_meters >= 0 AND distance_meters <> 'NaN'::NUMERIC))
    AND (calories_kcal IS NULL OR (calories_kcal >= 0 AND calories_kcal <> 'NaN'::NUMERIC))
    AND (speed_kph IS NULL OR (speed_kph >= 0 AND speed_kph <> 'NaN'::NUMERIC))
    AND (lap_reps IS NULL OR lap_reps >= 0)
    AND (incline_percent IS NULL OR incline_percent <> 'NaN'::NUMERIC)
  ),
  ADD CONSTRAINT workout_sets_result_version_check CHECK (
    (measurement_version IS NULL AND target_row_id IS NULL
      AND calories_kcal IS NULL AND speed_kph IS NULL
      AND lap_reps IS NULL AND incline_percent IS NULL)
    OR (measurement_version IS NOT NULL AND measurement_version = 3
      AND target_row_id IS NOT NULL AND nullif(btrim(target_row_id),'') IS NOT NULL)
  );

COMMENT ON COLUMN public.workout_sets.measurement_version IS
  'NULL preserves the original result contract; 3 identifies new snapshot-associated results. Never backfilled to 3.';
COMMENT ON COLUMN public.workout_sets.target_row_id IS
  'Stable row_id in this exercise snapshot set_targets or extra_set_targets; validated by v3 writer RPC, not positional or relational FK.';
COMMENT ON COLUMN public.workout_sets.distance_meters IS
  'Meters with two decimal places; legacy integer values preserved exactly. Aggregate/read RPC precision is upgraded separately.';

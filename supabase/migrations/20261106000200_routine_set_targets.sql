-- T04 expand only. No legacy routine/scalar/receipt is backfilled or relabelled.
-- Deploy only together with the compatible target validator and routine RPC/reader.
SET LOCAL lock_timeout = '8s';
SET LOCAL statement_timeout = '120s';

-- The parent marker survives an empty exercise list, so removing the last child
-- cannot accidentally reopen a converted routine to scalar legacy writers.
ALTER TABLE public.workout_templates
  ADD COLUMN measurement_version SMALLINT NULL,
  ADD CONSTRAINT workout_templates_measurement_version_check
    CHECK (measurement_version IS NULL OR measurement_version = 3);

ALTER TABLE public.template_exercises
  ADD COLUMN set_targets JSONB NULL,
  ADD COLUMN measurement_version SMALLINT NULL,
  ADD CONSTRAINT template_exercises_target_version_check CHECK (
    (measurement_version IS NULL AND set_targets IS NULL)
    OR (measurement_version IS NOT NULL AND measurement_version = 3
        AND set_targets IS NOT NULL AND jsonb_typeof(set_targets) = 'array')
  );

COMMENT ON COLUMN public.template_exercises.set_targets IS
  'Ordered canonical v3 target rows: row_id, set_number, weight, reps, duration_seconds, rest_seconds. NULL preserves the original scalar contract.';
COMMENT ON COLUMN public.template_exercises.measurement_version IS
  'NULL means original legacy contract; 3 means canonical set_targets. No default or historical promotion.';
COMMENT ON COLUMN public.workout_templates.measurement_version IS
  'Sticky routine contract marker: NULL for legacy, 3 after explicit conversion, including empty v3 routines.';

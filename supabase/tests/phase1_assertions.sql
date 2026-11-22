-- IMFIT Phase 1 post-deployment assertions; read-only, raises SQL error on failure.
-- psql -X -v ON_ERROR_STOP=1 -f tests/phase1_assertions.sql
DO $imfit_assertions$
DECLARE
  v_total INTEGER;
  v_modes INTEGER;
BEGIN
  SELECT COUNT(*) INTO v_total FROM public.exercises;
  IF v_total <> 161 THEN
    RAISE EXCEPTION 'FAIL: exercise count %, expected 161',v_total;
  END IF;
  IF EXISTS (
    SELECT 1 FROM public.exercises
    WHERE default_tracking_type IS NULL OR modality IS NULL
  ) THEN RAISE EXCEPTION 'FAIL: missing catalog type or modality'; END IF;
  IF EXISTS (
    SELECT 1 FROM public.exercises e
    WHERE NOT EXISTS (
      SELECT 1 FROM public.exercise_tracking_modes m
      WHERE m.exercise_id=e.id
    )
  ) THEN RAISE EXCEPTION 'FAIL: missing exercise_tracking_modes row'; END IF;
  IF EXISTS (
    SELECT 1 FROM public.exercises e
    WHERE NOT EXISTS (
      SELECT 1 FROM public.exercise_tracking_modes m
      WHERE m.exercise_id=e.id AND m.tracking_type=e.default_tracking_type
    )
  ) THEN RAISE EXCEPTION 'FAIL: default tracking type not allowed'; END IF;
  IF EXISTS (
    SELECT 1 FROM public.exercise_tracking_modes m
    LEFT JOIN public.exercises e ON e.id=m.exercise_id
    WHERE e.id IS NULL
  ) THEN RAISE EXCEPTION 'FAIL: mode has orphan exercise'; END IF;
  -- Phase 1 gate: run against pre-existing history. Only pre-v3 (measurement_version IS NULL) rows must stay unverified;
  -- v3 writes (and rows written by the current app after cutover) are legitimately VERIFIED.
  IF EXISTS (SELECT 1 FROM public.exercise_logs WHERE measurement_version IS NULL AND metric_quality <> 'LEGACY_UNVERIFIED')
     OR EXISTS (SELECT 1 FROM public.workout_logs WHERE measurement_version IS NULL AND data_quality <> 'LEGACY_UNVERIFIED')
  THEN RAISE EXCEPTION 'FAIL: historical workout incorrectly verified'; END IF;
  IF EXISTS (SELECT 1 FROM public.template_exercises WHERE reps IS NULL)
  THEN RAISE EXCEPTION 'FAIL: legacy template reps modified'; END IF;
  IF (SELECT COUNT(*) FROM public.exercises WHERE id IN
      ('ex_chest_4','ex_core_1','ex_back_17','ex_cardio_1')) <> 4
     -- T02/T03 canonical defaults: Strength -> WEIGHT_REPS, Isometric/Cardio -> DURATION.
     OR NOT EXISTS (SELECT 1 FROM public.exercises WHERE id='ex_chest_4' AND default_tracking_type='WEIGHT_REPS')
     OR NOT EXISTS (SELECT 1 FROM public.exercises WHERE id='ex_core_1' AND default_tracking_type='DURATION')
     OR NOT EXISTS (SELECT 1 FROM public.exercises WHERE id='ex_back_17' AND default_tracking_type='WEIGHT_REPS')
     OR NOT EXISTS (SELECT 1 FROM public.exercises WHERE id='ex_cardio_1' AND default_tracking_type='DURATION')
     -- Legacy modes stay readable (never deleted) even though they are no longer the default.
     OR NOT EXISTS (SELECT 1 FROM public.exercise_tracking_modes WHERE exercise_id='ex_chest_4' AND tracking_type='BODYWEIGHT_REPS')
     OR NOT EXISTS (SELECT 1 FROM public.exercise_tracking_modes WHERE exercise_id='ex_back_17' AND tracking_type='ASSISTED_REPS')
     OR NOT EXISTS (SELECT 1 FROM public.exercise_tracking_modes WHERE exercise_id='ex_cardio_1' AND tracking_type='DISTANCE_DURATION')
  THEN RAISE EXCEPTION 'FAIL: critical exercise incorrect mapping'; END IF;
  SELECT COUNT(*) INTO v_modes FROM public.exercise_tracking_modes;
  -- 196 legacy relations are never removed; T02/T03 only add canonical modes.
  IF v_modes<196 THEN RAISE EXCEPTION 'FAIL: expected at least 196 mode relations but got %',v_modes; END IF;
  RAISE NOTICE 'PASS: % exercises; % allowed mode relations; legacy unchanged',v_total,v_modes;
END;
$imfit_assertions$;

DO $imfit_permissions$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
    WHERE n.nspname='public' AND c.relname='exercise_tracking_modes'
      AND c.relrowsecurity
  ) THEN RAISE EXCEPTION 'FAIL: exercise_tracking_modes RLS disabled'; END IF;
  IF NOT EXISTS (
    SELECT 1 FROM pg_policies WHERE schemaname='public'
      AND tablename='exercise_tracking_modes'
      AND policyname='exercise_tracking_modes_authenticated_read'
      AND cmd='SELECT'
  ) THEN RAISE EXCEPTION 'FAIL: authenticated SELECT policy missing'; END IF;
  IF NOT has_table_privilege('authenticated','public.exercise_tracking_modes','SELECT')
  THEN RAISE EXCEPTION 'FAIL: authenticated SELECT not granted'; END IF;
  IF has_table_privilege('anon','public.exercise_tracking_modes','SELECT')
  THEN RAISE EXCEPTION 'FAIL: anon SELECT unexpectedly granted'; END IF;
  IF has_table_privilege('authenticated','public.exercise_tracking_modes','INSERT')
    OR has_table_privilege('authenticated','public.exercise_tracking_modes','UPDATE')
    OR has_table_privilege('authenticated','public.exercise_tracking_modes','DELETE')
  THEN RAISE EXCEPTION 'FAIL: client has catalog write privilege'; END IF;
END;
$imfit_permissions$;

SELECT e.default_tracking_type, e.modality, COUNT(*) AS exercise_count
FROM public.exercises e
GROUP BY e.default_tracking_type,e.modality
ORDER BY e.modality,e.default_tracking_type;

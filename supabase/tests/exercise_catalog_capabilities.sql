-- Run only against a disposable migrated database as postgres:
-- psql -X -v ON_ERROR_STOP=1 -f supabase/tests/exercise_catalog_capabilities.sql
-- Exercises actual catalog writes and CHECK enforcement; rolls all changes back.
BEGIN;
DO $catalog_contract$
DECLARE
  invalid_case JSONB;
BEGIN
  -- A duration result may offer no optional measurements, even on a machine.
  UPDATE public.exercises SET tracking_profile='equipment_metrics', supported_metrics='{}'
  WHERE id='ex_cardio_1';
  UPDATE public.exercises SET tracking_profile='distance_activity',
    supported_metrics=ARRAY['distance','speed','laps'] WHERE id='ex_cardio_1';
  UPDATE public.exercises SET tracking_profile='duration_only', supported_metrics='{}'
  WHERE id='ex_cardio_1';

  FOR invalid_case IN SELECT value FROM jsonb_array_elements('[
    {"profile":null,"metrics":[]},
    {"profile":"unknown","metrics":[]},
    {"profile":"duration_only","metrics":["calories"]},
    {"profile":"distance_activity","metrics":["calories"]},
    {"profile":"distance_activity","metrics":["incline"]},
    {"profile":"equipment_metrics","metrics":["watts"]},
    {"profile":"equipment_metrics","metrics":["speed","speed"]},
    {"profile":"equipment_metrics","metrics":[null]}
  ]'::jsonb)
  LOOP
    BEGIN
      UPDATE public.exercises SET tracking_profile=invalid_case->>'profile',
        supported_metrics=ARRAY(SELECT jsonb_array_elements_text(invalid_case->'metrics'))
      WHERE id='ex_cardio_1';
      RAISE EXCEPTION 'Invalid capability accepted: %',invalid_case;
    EXCEPTION WHEN check_violation THEN NULL;
    END;
  END LOOP;

  BEGIN
    UPDATE public.exercises SET supported_metrics=NULL WHERE id='ex_cardio_1';
    RAISE EXCEPTION 'NULL capability list accepted';
  EXCEPTION WHEN not_null_violation THEN NULL;
  END;
  BEGIN
    UPDATE public.exercises SET tracking_profile='equipment_metrics',
      supported_metrics=ARRAY[['distance','speed']] WHERE id='ex_cardio_1';
    RAISE EXCEPTION 'Multidimensional capability list accepted';
  EXCEPTION WHEN check_violation THEN NULL;
  END;
  BEGIN
    UPDATE public.exercises SET tracking_profile='equipment_metrics',
      supported_metrics='[0:0]={distance}'::TEXT[] WHERE id='ex_cardio_1';
    RAISE EXCEPTION 'Nonstandard capability array bounds accepted';
  EXCEPTION WHEN check_violation THEN NULL;
  END;
  BEGIN
    UPDATE public.exercises SET default_tracking_type='DISTANCE_DURATION' WHERE id='ex_cardio_1';
    RAISE EXCEPTION 'Legacy Cardio format accepted for catalog';
  EXCEPTION WHEN check_violation THEN NULL;
  END;
  BEGIN
    UPDATE public.exercises SET tracking_profile='duration_only' WHERE id='ex_chest_1';
    RAISE EXCEPTION 'Non-Cardio profile accepted';
  EXCEPTION WHEN check_violation THEN NULL;
  END;
  BEGIN
    UPDATE public.exercises SET supported_metrics=ARRAY['distance'] WHERE id='ex_core_1';
    RAISE EXCEPTION 'Isometric capability accepted';
  EXCEPTION WHEN check_violation THEN NULL;
  END;
  BEGIN
    UPDATE public.exercises SET modality='CALISTHENICS' WHERE id='ex_chest_4';
    RAISE EXCEPTION 'Legacy catalog category accepted';
  EXCEPTION WHEN check_violation THEN NULL;
  END;
  BEGIN
    UPDATE public.exercises SET default_tracking_type='BODYWEIGHT_REPS' WHERE id='ex_chest_4';
    RAISE EXCEPTION 'Legacy Strength format accepted for catalog';
  EXCEPTION WHEN check_violation THEN NULL;
  END;

  IF EXISTS (SELECT 1 FROM public.exercises e WHERE NOT EXISTS (
    SELECT 1 FROM public.exercise_tracking_modes m
    WHERE m.exercise_id=e.id AND m.tracking_type=e.default_tracking_type
  )) THEN RAISE EXCEPTION 'Canonical default cannot pass existing RPC mode validation'; END IF;
  IF NOT EXISTS (SELECT 1 FROM public.exercise_tracking_modes
    WHERE exercise_id='ex_chest_4' AND tracking_type='BODYWEIGHT_REPS')
    OR NOT EXISTS (SELECT 1 FROM public.exercise_tracking_modes
    WHERE exercise_id='ex_cardio_1' AND tracking_type='DISTANCE_DURATION') THEN
    RAISE EXCEPTION 'Legacy pending payloads lost allowed modes';
  END IF;
  RAISE NOTICE 'PASS: catalog profiles, metric boundaries, category formats and legacy modes';
END;
$catalog_contract$;

-- The private helper must remain executable during actual service-role writes.
SET LOCAL ROLE service_role;
UPDATE public.exercises SET tracking_profile='equipment_metrics',
  supported_metrics=ARRAY['calories','distance','speed'] WHERE id='ex_cardio_2';
RESET ROLE;
ROLLBACK;

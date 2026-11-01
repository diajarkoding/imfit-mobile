-- Behavioral Phase 2 regressions. Run only in a disposable database as postgres:
-- psql -X -v ON_ERROR_STOP=1 -f supabase/tests/phase2_verification_regressions.sql
-- These are rollback-isolated PL/pgSQL assertions, not a pgTAP test suite.
BEGIN;
SET LOCAL TIME ZONE 'UTC';
DO $fixtures$
DECLARE
  key text;
  owner_id uuid;
  template_id uuid;
  session_id uuid;
  exercise_id uuid;
  set_id uuid;
BEGIN
  FOREACH key IN ARRAY ARRAY['owner','other','v1_template','v1_session','v1_exercise','v1_set'] LOOP
    PERFORM set_config('imfit.test.' || key, gen_random_uuid()::text, true);
  END LOOP;
  owner_id := current_setting('imfit.test.owner')::uuid;
  template_id := current_setting('imfit.test.v1_template')::uuid;
  session_id := current_setting('imfit.test.v1_session')::uuid;
  exercise_id := current_setting('imfit.test.v1_exercise')::uuid;
  set_id := current_setting('imfit.test.v1_set')::uuid;
  INSERT INTO auth.users (id, email, raw_user_meta_data) VALUES
    (owner_id, 'phase5-owner@example.invalid', '{"name":"Phase5 Owner"}'),
    (current_setting('imfit.test.other')::uuid, 'phase5-other@example.invalid', '{"name":"Phase5 Other"}');
  INSERT INTO public.workout_templates (id, user_id, name)
  VALUES (template_id, owner_id, 'Pre-upgrade v1 routine');
  -- Frozen v1 weight/reps snapshot shape: no tracking_type, modality or typed fields.
  INSERT INTO public.active_sessions (
    id, user_id, template_id, template_name, start_time, session_data,
    expires_at, version, schema_version, status, review_required, template_revision
  ) VALUES (
    session_id, owner_id, template_id, 'Pre-upgrade v1 routine', now() - interval '10 minutes',
    jsonb_build_object('exercises', jsonb_build_array(jsonb_build_object(
      'id', exercise_id, 'exercise_id', 'ex_core_1', 'name', 'Plank',
      'muscle_category', 'Core', 'order_index', 0, 'rest_seconds', 60,
      'input_defaults', jsonb_build_object('sets', 1, 'reps', 12),
      'sets', jsonb_build_array(jsonb_build_object(
        'id', set_id, 'set_number', 1, 'weight', '20.50', 'reps', 12,
        'is_completed', true, 'is_warmup', false
      ))
    )), 'current_exercise_id', exercise_id,
    'used_exercise_ids', jsonb_build_array(exercise_id), 'used_set_ids', jsonb_build_array(set_id)),
    now() + interval '1 day', 1, 1, 'ACTIVE', false, 1
  );
END;
$fixtures$;
CREATE TEMP TABLE phase2_regression_results (
  case_id text PRIMARY KEY, passed boolean NOT NULL, error_state text, reason text
) ON COMMIT DROP;
GRANT SELECT, INSERT ON phase2_regression_results TO authenticated;
SET LOCAL ROLE authenticated;
DO $regressions$
DECLARE
  owner_id uuid := current_setting('imfit.test.owner')::uuid;
  other_id uuid := current_setting('imfit.test.other')::uuid;
  template_id uuid := current_setting('imfit.test.v1_template')::uuid;
  session_id uuid;
  request_id uuid;
  workout_id uuid;
  exercise_log_id uuid;
  set_id uuid;
  result jsonb;
  replay jsonb;
  detail jsonb;
  exercises jsonb;
  sets jsonb;
  workout jsonb;
  defaults jsonb;
  measurement jsonb;
  tracking text;
  catalog_id text;
  i integer;
  version bigint;
  start_at timestamptz := date_trunc('day', now()) + interval '10 hours';
BEGIN
  IF current_user <> 'authenticated' THEN
    RAISE EXCEPTION 'Regressions require the effective authenticated role';
  END IF;
  PERFORM set_config('request.jwt.claim.sub', owner_id::text, true);
  PERFORM set_config('request.jwt.claims', jsonb_build_object('sub', owner_id, 'role', 'authenticated')::text, true);

  BEGIN
    request_id := gen_random_uuid();
    result := public.finish_active_session(request_id, current_setting('imfit.test.v1_session')::uuid, 1);
    replay := public.finish_active_session(request_id, current_setting('imfit.test.v1_session')::uuid, 1);
    detail := public.get_workout_detail((result->>'workout_id')::uuid);
    IF replay IS DISTINCT FROM result
      OR detail->>'data_quality' IS DISTINCT FROM 'LEGACY_UNVERIFIED'
      OR (detail->>'total_volume')::numeric IS DISTINCT FROM 246::numeric
      OR (detail->>'total_reps')::integer IS DISTINCT FROM 12
      OR detail#>>'{exercise_logs,0,tracking_type}' IS DISTINCT FROM 'WEIGHT_REPS' THEN
      RAISE EXCEPTION 'v1 completion changed legacy metrics, quality, or receipt';
    END IF;
    INSERT INTO phase2_regression_results VALUES ('R01_v1_session_completion_receipt', true, NULL, NULL);
  EXCEPTION WHEN OTHERS THEN
    INSERT INTO phase2_regression_results VALUES ('R01_v1_session_completion_receipt', false, SQLSTATE, SQLERRM);
  END;

  BEGIN
    exercises := '[]';
    FOR i IN 0..5 LOOP
      tracking := (ARRAY['WEIGHT_REPS','BODYWEIGHT_REPS','ASSISTED_REPS','REPS_ONLY','DURATION','DISTANCE_DURATION'])[i+1];
      catalog_id := (ARRAY['ex_chest_1','ex_chest_4','ex_back_17','ex_back_2','ex_core_1','ex_cardio_1'])[i+1];
      defaults := jsonb_build_object('exercise_id',catalog_id,'order_index',i,'sets',1,'rest_seconds',60,'tracking_type',tracking);
      IF i < 4 THEN defaults := defaults || '{"reps":10}'::jsonb; END IF;
      IF i >= 4 THEN defaults := defaults || '{"target_duration_seconds":45}'::jsonb; END IF;
      IF i = 5 THEN defaults := defaults || '{"target_distance_meters":500}'::jsonb; END IF;
      exercises := exercises || jsonb_build_array(defaults);
    END LOOP;
    session_id := gen_random_uuid();
    result := public.save_template_revision(gen_random_uuid(), gen_random_uuid(), NULL, 'Six tracking types', exercises, '[]', false, owner_id);
    request_id := gen_random_uuid();
    result := public.create_active_session(request_id, session_id, (result->>'template_id')::uuid, (result->>'revision')::bigint);
    replay := public.create_active_session(request_id, session_id, (result#>>'{snapshot,template_id}')::uuid, (result#>>'{snapshot,template_revision}')::bigint);
    IF replay IS DISTINCT FROM result THEN RAISE EXCEPTION 'Create replay changed receipt'; END IF;
    version := 1;
    FOR i IN 0..5 LOOP
      exercise_log_id := (result#>>ARRAY['snapshot','exercises',i::text,'id'])::uuid;
      set_id := (result#>>ARRAY['snapshot','exercises',i::text,'sets','0','id'])::uuid;
      measurement := CASE i
        WHEN 0 THEN '{"weight":40,"reps":10}'::jsonb
        WHEN 1 THEN '{"additional_weight":5,"reps":10}'::jsonb
        WHEN 2 THEN '{"assistance_weight":20,"reps":10}'::jsonb
        WHEN 3 THEN '{"reps":10}'::jsonb
        WHEN 4 THEN '{"duration_seconds":45}'::jsonb
        ELSE '{"duration_seconds":45,"distance_meters":500}'::jsonb END;
      result := public.update_active_session(gen_random_uuid(), session_id, version,
        measurement || jsonb_build_object('type','edit_set','exercise_log_id',exercise_log_id,'set_id',set_id));
      version := version + 1;
      result := public.update_active_session(gen_random_uuid(), session_id, version,
        jsonb_build_object('type','complete_set','exercise_log_id',exercise_log_id,'set_id',set_id));
      version := version + 1;
      IF (result#>>ARRAY['snapshot','exercises',i::text,'sets','0','is_completed'])::boolean IS DISTINCT FROM true THEN
        RAISE EXCEPTION 'Completed typed set missing: %', i;
      END IF;
    END LOOP;
    request_id := gen_random_uuid();
    result := public.finish_active_session(request_id, session_id, version);
    replay := public.finish_active_session(request_id, session_id, version);
    detail := public.get_workout_detail((result->>'workout_id')::uuid);
    IF result IS DISTINCT FROM replay
      OR (detail->>'total_volume')::numeric IS DISTINCT FROM 400::numeric
      OR (detail->>'additional_volume')::numeric IS DISTINCT FROM 50::numeric
      OR (detail->>'total_reps')::integer IS DISTINCT FROM 40
      OR (detail->>'total_duration_seconds')::integer IS DISTINCT FROM 90
      OR (detail->>'total_distance_meters')::integer IS DISTINCT FROM 500
      OR detail->>'data_quality' IS DISTINCT FROM 'VERIFIED'
      OR jsonb_array_length(detail->'exercise_logs') IS DISTINCT FROM 6 THEN
      RAISE EXCEPTION 'Six-type terminal metrics or replay mismatch';
    END IF;
    INSERT INTO phase2_regression_results VALUES ('R02_six_types_online_lifecycle', true, NULL, NULL);
  EXCEPTION WHEN OTHERS THEN
    INSERT INTO phase2_regression_results VALUES ('R02_six_types_online_lifecycle', false, SQLSTATE, SQLERRM);
  END;

  -- Each import is independent: failures do not prevent exercising the other types.
  FOR i IN 0..7 LOOP
    BEGIN
      tracking := (ARRAY['WEIGHT_REPS','BODYWEIGHT_REPS','ASSISTED_REPS','REPS_ONLY','DURATION','DISTANCE_DURATION','V1_WEIGHT_REPS','V1_DURATION_CATALOG'])[i+1];
      catalog_id := (ARRAY['ex_chest_1','ex_chest_4','ex_back_17','ex_back_2','ex_core_1','ex_cardio_1','ex_chest_1','ex_core_1'])[i+1];
      workout_id := gen_random_uuid(); exercise_log_id := gen_random_uuid(); set_id := gen_random_uuid();
      measurement := CASE i
        WHEN 0 THEN '{"weight":40,"reps":10}'::jsonb
        WHEN 1 THEN '{"weight":0,"additional_weight":5,"reps":10}'::jsonb
        WHEN 2 THEN '{"weight":0,"assistance_weight":20,"reps":10}'::jsonb
        WHEN 3 THEN '{"weight":0,"reps":10}'::jsonb
        WHEN 4 THEN '{"weight":0,"reps":0,"duration_seconds":45}'::jsonb
        WHEN 5 THEN '{"weight":0,"reps":0,"duration_seconds":45,"distance_meters":500}'::jsonb
        ELSE '{"weight":40,"reps":10}'::jsonb END;
      workout := jsonb_build_object('id',workout_id,'user_id',owner_id,'template_id',template_id,
        'template_name','Pending aggregate','date',start_at::date,'start_time',start_at,'end_time',start_at+interval '1 minute',
        'total_sets',1,'total_reps',CASE WHEN i IN (4,5) THEN 0 ELSE 10 END,'total_volume',CASE WHEN i IN (0,6,7) THEN 400 ELSE 0 END);
      exercises := jsonb_build_array(jsonb_build_object('id',exercise_log_id,'workout_log_id',workout_id,
        'exercise_id',catalog_id,'exercise_name','Regression fixture','muscle_category','Fixture','order_index',0,
        'total_sets',1,'total_reps',workout->'total_reps','total_volume',workout->'total_volume')
        || CASE WHEN i < 6 THEN jsonb_build_object('tracking_type',tracking) ELSE '{}'::jsonb END);
      sets := jsonb_build_array(measurement || jsonb_build_object('id',set_id,'workout_log_id',workout_id,
        'exercise_log_id',exercise_log_id,'exercise_id',catalog_id,'set_number',1,'is_completed',true));
      result := public.persist_local_workout_aggregate(workout, exercises, sets);
      replay := public.persist_local_workout_aggregate(workout, exercises, sets);
      detail := public.get_workout_detail(workout_id);
      IF replay IS DISTINCT FROM result
        OR (detail->>'total_volume')::numeric IS DISTINCT FROM (workout->>'total_volume')::numeric
        OR (detail->>'total_reps')::integer IS DISTINCT FROM (workout->>'total_reps')::integer
        OR detail->>'data_quality' IS DISTINCT FROM (CASE WHEN i < 6 THEN 'VERIFIED' ELSE 'LEGACY_UNVERIFIED' END)
        OR (i IN (4,5) AND (detail->>'total_duration_seconds')::integer IS DISTINCT FROM 45)
        OR detail#>>'{exercise_logs,0,tracking_type}' IS DISTINCT FROM (CASE WHEN i < 6 THEN tracking ELSE 'WEIGHT_REPS' END)
        OR detail#>>'{exercise_logs,0,metric_quality}' IS DISTINCT FROM (CASE WHEN i < 6 THEN 'VERIFIED' ELSE 'LEGACY_UNVERIFIED' END)
        OR (i = 5 AND (detail->>'total_distance_meters')::integer IS DISTINCT FROM 500)
        OR (i = 1 AND (detail->>'additional_volume')::numeric IS DISTINCT FROM 50::numeric)
        OR (i = 2 AND (detail#>>'{exercise_logs,0,sets,0,assistance_weight}')::numeric IS DISTINCT FROM 20::numeric) THEN
        RAISE EXCEPTION 'Pending aggregate metrics, quality, or receipt mismatch';
      END IF;
      BEGIN
        PERFORM public.persist_local_workout_aggregate(workout || '{"template_name":"Conflicting"}'::jsonb, exercises, sets);
        RAISE EXCEPTION 'Conflicting payload accepted';
      EXCEPTION WHEN SQLSTATE 'PT409' THEN NULL;
      END;
      PERFORM set_config('request.jwt.claim.sub', other_id::text, true);
      BEGIN
        PERFORM public.get_workout_detail(workout_id);
        RAISE EXCEPTION 'Cross-owner detail exposed';
      EXCEPTION WHEN SQLSTATE 'PT404' THEN NULL;
      END;
      BEGIN
        PERFORM public.persist_local_workout_aggregate(workout || jsonb_build_object('user_id',other_id), exercises, sets);
        RAISE EXCEPTION 'Cross-owner receipt reused';
      EXCEPTION WHEN insufficient_privilege THEN NULL;
      END;
      IF jsonb_array_length(public.get_workout_sync_page(NULL, 50)->'items') IS DISTINCT FROM 0 THEN
        RAISE EXCEPTION 'Cross-owner sync page leaked aggregates';
      END IF;
      PERFORM set_config('request.jwt.claim.sub', owner_id::text, true);
      INSERT INTO phase2_regression_results VALUES ('R03_import_' || tracking, true, NULL, NULL);
    EXCEPTION WHEN OTHERS THEN
      INSERT INTO phase2_regression_results VALUES ('R03_import_' || tracking, false, SQLSTATE, SQLERRM);
    END;
  END LOOP;

  BEGIN
    PERFORM set_config('request.jwt.claim.sub', other_id::text, true);
    BEGIN
      PERFORM public.get_template_detail(template_id);
      RAISE EXCEPTION 'Cross-owner routine exposed';
    EXCEPTION WHEN SQLSTATE 'PT404' THEN NULL;
    END;
    BEGIN
      PERFORM public.save_template_revision(gen_random_uuid(),template_id,1,'Hijacked',
        '[{"exercise_id":"ex_chest_1","order_index":0,"sets":1,"reps":10,"rest_seconds":60}]','[]',false,other_id);
      RAISE EXCEPTION 'Cross-owner routine mutated';
    EXCEPTION WHEN SQLSTATE 'PT404' THEN NULL;
    END;
    result := public.get_active_session(current_setting('imfit.test.v1_session')::uuid);
    IF result->'found' IS DISTINCT FROM 'false'::jsonb OR result->'session' IS DISTINCT FROM 'null'::jsonb THEN
      RAISE EXCEPTION 'Cross-owner session exposed';
    END IF;
    BEGIN
      PERFORM public.update_active_session(gen_random_uuid(),current_setting('imfit.test.v1_session')::uuid,1,'{"type":"pause"}');
      RAISE EXCEPTION 'Cross-owner session mutated';
    EXCEPTION WHEN SQLSTATE 'PT404' THEN NULL;
    END;
    INSERT INTO phase2_regression_results VALUES ('R04_cross_owner_routine_session', true, NULL, NULL);
  EXCEPTION WHEN OTHERS THEN
    INSERT INTO phase2_regression_results VALUES ('R04_cross_owner_routine_session', false, SQLSTATE, SQLERRM);
  END;
  PERFORM set_config('request.jwt.claim.sub', owner_id::text, true);

  BEGIN
    workout_id := gen_random_uuid(); exercise_log_id := gen_random_uuid(); set_id := gen_random_uuid();
    workout := jsonb_build_object('id',workout_id,'user_id',owner_id,'template_id',template_id,
      'template_name','Invalid catalog mode','date',start_at::date,'start_time',start_at,'end_time',start_at+interval '1 minute',
      'total_sets',1,'total_reps',0,'total_volume',0);
    exercises := jsonb_build_array(jsonb_build_object('id',exercise_log_id,'workout_log_id',workout_id,
      'exercise_id','ex_chest_1','exercise_name','Bench press','muscle_category','Chest','order_index',0,'tracking_type','DURATION'));
    sets := jsonb_build_array(jsonb_build_object('id',set_id,'workout_log_id',workout_id,'exercise_log_id',exercise_log_id,
      'exercise_id','ex_chest_1','set_number',1,'is_completed',true,'duration_seconds',45));
    BEGIN
      PERFORM public.persist_local_workout_aggregate(workout,exercises,sets);
      RAISE EXCEPTION 'Disallowed DURATION for bench press accepted by import';
    EXCEPTION WHEN invalid_parameter_value OR SQLSTATE 'PT400' THEN NULL;
    END;
    -- Missing provenance, not a typed WEIGHT_REPS claim, is the v1 contract.
    -- Neither unknown/null modes nor a typed legacy-looking Plank may bypass it.
    FOR i IN 0..2 LOOP
      exercises := jsonb_build_array((exercises->0) || CASE i
        WHEN 0 THEN '{"tracking_type":"UNKNOWN"}'::jsonb
        WHEN 1 THEN '{"tracking_type":null}'::jsonb
        ELSE '{"exercise_id":"ex_core_1","tracking_type":"WEIGHT_REPS"}'::jsonb END);
      sets := jsonb_build_array((sets->0) || CASE WHEN i = 2
        THEN '{"exercise_id":"ex_core_1","weight":40,"reps":10}'::jsonb
        ELSE '{}'::jsonb END);
      BEGIN
        PERFORM public.persist_local_workout_aggregate(workout,exercises,sets);
        RAISE EXCEPTION 'Invalid explicit tracking type accepted: %', i;
      EXCEPTION WHEN invalid_parameter_value OR SQLSTATE 'PT400' THEN NULL;
      END;
    END LOOP;
    INSERT INTO phase2_regression_results VALUES ('R05_import_catalog_mode_validation', true, NULL, NULL);
  EXCEPTION WHEN OTHERS THEN
    INSERT INTO phase2_regression_results VALUES ('R05_import_catalog_mode_validation', false, SQLSTATE, SQLERRM);
  END;

  BEGIN
    IF has_table_privilege(current_user,'public.workout_logs','SELECT')
      OR has_table_privilege(current_user,'public.workout_logs','INSERT')
      OR has_schema_privilege(current_user,'imfit_private','USAGE')
      OR has_function_privilege('anon','public.persist_local_workout_aggregate(jsonb,jsonb,jsonb)','EXECUTE')
      OR has_function_privilege('anon','public.get_workout_detail(uuid)','EXECUTE') THEN
      RAISE EXCEPTION 'Raw/private/anonymous data API permissions are too broad';
    END IF;
    INSERT INTO phase2_regression_results VALUES ('R06_data_api_permissions', true, NULL, NULL);
  EXCEPTION WHEN OTHERS THEN
    INSERT INTO phase2_regression_results VALUES ('R06_data_api_permissions', false, SQLSTATE, SQLERRM);
  END;

  BEGIN
    -- Current Android transport omits tracking_type/modality for decoded legacy
    -- children. Mixed payloads must not promote those children or the workout.
    workout_id := gen_random_uuid(); exercise_log_id := gen_random_uuid(); set_id := gen_random_uuid();
    workout := jsonb_build_object('id',workout_id,'user_id',owner_id,'template_id',template_id,
      'template_name','Mixed legacy and typed transport','date',start_at::date,
      'start_time',start_at,'end_time',start_at+interval '1 minute',
      'total_sets',2,'total_reps',10,'total_volume',400);
    exercises := jsonb_build_array(
      jsonb_build_object('id',exercise_log_id,'workout_log_id',workout_id,
        'exercise_id','ex_core_1','exercise_name','Historical Plank','muscle_category','Core','order_index',0,
        'total_sets',1,'total_reps',10,'total_volume',400),
      jsonb_build_object('id',gen_random_uuid(),'workout_log_id',workout_id,
        'exercise_id','ex_cardio_1','exercise_name','Frozen duration mode','muscle_category','Cardio','order_index',1,
        'tracking_type','DURATION','total_sets',1,'total_reps',0,'total_volume',0));
    sets := jsonb_build_array(
      jsonb_build_object('id',set_id,'workout_log_id',workout_id,'exercise_log_id',exercise_log_id,
        'exercise_id','ex_core_1','set_number',1,'is_completed',true,'weight',40,'reps',10),
      jsonb_build_object('id',gen_random_uuid(),'workout_log_id',workout_id,
        'exercise_log_id',exercises#>>'{1,id}','exercise_id','ex_cardio_1','set_number',1,'is_completed',true,
        'duration_seconds',45));
    result := public.persist_local_workout_aggregate(workout,exercises,sets);
    replay := public.persist_local_workout_aggregate(workout,
      jsonb_build_array(exercises->1,exercises->0),jsonb_build_array(sets->1,sets->0));
    detail := public.get_workout_detail(workout_id);
    IF replay IS DISTINCT FROM result
      OR detail->>'data_quality' IS DISTINCT FROM 'LEGACY_UNVERIFIED'
      OR (detail->>'total_volume')::numeric IS DISTINCT FROM 400::numeric
      OR (detail->>'total_reps')::integer IS DISTINCT FROM 10
      OR (detail->>'total_duration_seconds')::integer IS DISTINCT FROM 45
      OR detail#>>'{exercise_logs,0,tracking_type}' IS DISTINCT FROM 'WEIGHT_REPS'
      OR detail#>>'{exercise_logs,0,metric_quality}' IS DISTINCT FROM 'LEGACY_UNVERIFIED'
      OR detail#>>'{exercise_logs,1,tracking_type}' IS DISTINCT FROM 'DURATION'
      OR detail#>>'{exercise_logs,1,metric_quality}' IS DISTINCT FROM 'VERIFIED' THEN
      RAISE EXCEPTION 'Mixed transport changed historical metrics, frozen mode, quality, or replay';
    END IF;
    INSERT INTO phase2_regression_results VALUES ('R07_mixed_legacy_typed_transport', true, NULL, NULL);
  EXCEPTION WHEN OTHERS THEN
    INSERT INTO phase2_regression_results VALUES ('R07_mixed_legacy_typed_transport', false, SQLSTATE, SQLERRM);
  END;
END;
$regressions$;
RESET ROLE;
SELECT case_id, passed, error_state, reason FROM phase2_regression_results ORDER BY case_id;
DO $result$
DECLARE failures integer;
BEGIN
  SELECT count(*) INTO failures FROM phase2_regression_results WHERE NOT passed;
  IF failures > 0 THEN RAISE EXCEPTION '% behavioral regression cases failed', failures; END IF;
  RAISE NOTICE 'All 14 behavioral regression cases passed (original 13 plus mixed legacy transport)';
END;
$result$;
ROLLBACK;

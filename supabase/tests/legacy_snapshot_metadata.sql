-- G02 behavior contract. Run only against a disposable migrated database as postgres.
-- Fixtures and deliberate catalog changes are rolled back; no live database writes.
\set ON_ERROR_STOP on
BEGIN;
SET LOCAL TIME ZONE 'UTC';
CREATE TEMP TABLE legacy_snapshot_cases (
  name text PRIMARY KEY,
  owner_id uuid NOT NULL,
  template_id uuid NOT NULL,
  workout_id uuid NOT NULL,
  session_id uuid NOT NULL,
  request_id uuid NOT NULL,
  workout jsonb NOT NULL,
  exercises jsonb NOT NULL,
  sets jsonb NOT NULL,
  expected jsonb NOT NULL,
  receipt jsonb,
  stored_before jsonb
) ON COMMIT DROP;
GRANT SELECT, UPDATE ON legacy_snapshot_cases TO authenticated;
CREATE TEMP TABLE legacy_snapshot_rejections (workout_id uuid PRIMARY KEY) ON COMMIT DROP;
GRANT SELECT, INSERT ON legacy_snapshot_rejections TO authenticated;
DO $fixtures$
DECLARE
  case_name text;
  owner_id uuid;
  template_id uuid;
  workout_id uuid;
  session_id uuid;
  exercise_log_id uuid;
  set_id uuid;
  request_id uuid;
  workout jsonb;
  exercises jsonb;
  sets jsonb;
  v_expected jsonb;
  session_exercise jsonb;
  start_at timestamptz := date_trunc('day', now()) - interval '1 day' + interval '10 hours';
  is_typed boolean;
BEGIN
  FOREACH case_name IN ARRAY ARRAY[
    'history_null', 'upload_v1', 'upload_v1_null_modality', 'upload_typed',
    'upload_typed_unknown_modality', 'upload_replay_old',
    'session_v1', 'session_v2_missing_tracking', 'session_typed', 'session_null_modality',
    'session_replay_old', 'session_invalid_tracking_null', 'session_invalid_tracking_unknown',
    'session_invalid_tracking_number', 'session_invalid_modality_unknown', 'session_invalid_modality_number'
  ] LOOP
    owner_id := gen_random_uuid(); template_id := gen_random_uuid();
    workout_id := gen_random_uuid(); session_id := gen_random_uuid();
    exercise_log_id := gen_random_uuid(); set_id := gen_random_uuid(); request_id := gen_random_uuid();
    INSERT INTO auth.users(id, email, raw_user_meta_data)
    VALUES (owner_id, case_name || '@snapshot-test.invalid', '{"name":"Snapshot Contract"}');
    INSERT INTO public.workout_templates(id, user_id, name)
    VALUES (template_id, owner_id, 'Frozen snapshot routine');
    is_typed := case_name IN ('upload_typed', 'upload_typed_unknown_modality', 'upload_replay_old', 'session_typed');
    workout := jsonb_build_object('id',workout_id,'user_id',owner_id,'template_id',template_id,
      'template_name','Frozen snapshot routine','date',start_at::date,'start_time',start_at,
      'end_time',start_at+interval '1 minute','total_sets',1,'total_reps',10,
      'total_volume',CASE WHEN is_typed THEN 0 ELSE 200 END);
    exercises := jsonb_build_array(jsonb_build_object('id',exercise_log_id,'workout_log_id',workout_id,
      'exercise_id',CASE WHEN is_typed THEN 'ex_back_2' ELSE 'ex_core_1' END,
      'exercise_name','Frozen exercise name','muscle_category','Frozen muscle category','order_index',0,
      'total_sets',1,'total_reps',10,'total_volume',CASE WHEN is_typed THEN 0 ELSE 200 END));
    sets := jsonb_build_array(jsonb_build_object('id',set_id,'exercise_log_id',exercise_log_id,
      'workout_log_id',workout_id,'exercise_id',CASE WHEN is_typed THEN 'ex_back_2' ELSE 'ex_core_1' END,
      'set_number',1,'weight',CASE WHEN is_typed THEN 0 ELSE 20 END,'reps',10,'is_completed',true));
    IF is_typed THEN
      exercises := jsonb_build_array((exercises->0) || '{"tracking_type":"BODYWEIGHT_REPS","modality":"CALISTHENICS"}');
      sets := jsonb_build_array((sets->0) || '{"additional_weight":"2.50"}');
    END IF;
    IF case_name = 'upload_v1_null_modality' THEN
      exercises := jsonb_build_array((exercises->0) || '{"modality":null}');
    ELSIF case_name = 'upload_typed_unknown_modality' THEN
      exercises := jsonb_build_array((exercises->0) - 'modality');
    ELSIF case_name = 'upload_replay_old' THEN
      -- The old importer ignored explicit modality. New validation must not run on replay.
      exercises := jsonb_build_array((exercises->0) || '{"modality":"PREVIOUSLY_IGNORED"}');
    ELSIF case_name = 'session_null_modality' THEN
      exercises := jsonb_build_array((exercises->0) || '{"modality":null}');
    ELSIF case_name IN ('session_invalid_tracking_null', 'session_replay_old') THEN
      exercises := jsonb_build_array((exercises->0) || '{"tracking_type":null}');
    ELSIF case_name = 'session_invalid_tracking_unknown' THEN
      exercises := jsonb_build_array((exercises->0) || '{"tracking_type":"UNKNOWN"}');
    ELSIF case_name = 'session_invalid_tracking_number' THEN
      exercises := jsonb_build_array((exercises->0) || '{"tracking_type":42}');
    ELSIF case_name = 'session_invalid_modality_unknown' THEN
      exercises := jsonb_build_array((exercises->0) || '{"modality":"UNKNOWN"}');
    ELSIF case_name = 'session_invalid_modality_number' THEN
      exercises := jsonb_build_array((exercises->0) || '{"modality":42}');
    END IF;
    v_expected := jsonb_build_object('tracking_type',CASE WHEN is_typed THEN 'BODYWEIGHT_REPS' ELSE 'WEIGHT_REPS' END,
      'modality',CASE WHEN case_name IN ('upload_typed','session_typed') THEN 'CALISTHENICS' ELSE NULL END,
      'total_volume',CASE WHEN is_typed THEN 0 ELSE 200 END,
      'additional_volume',CASE WHEN is_typed THEN 25 ELSE 0 END,
      'data_quality',CASE WHEN is_typed THEN 'VERIFIED' ELSE 'LEGACY_UNVERIFIED' END);
    INSERT INTO legacy_snapshot_cases(name,owner_id,template_id,workout_id,session_id,request_id,
      workout,exercises,sets,expected)
    VALUES (case_name,owner_id,template_id,workout_id,session_id,request_id,workout,exercises,sets,v_expected);
    IF case_name LIKE 'session_%' THEN
      session_exercise := (exercises->0) || jsonb_build_object('name','Frozen exercise name','rest_seconds',60,
        'sets',sets,'input_defaults',jsonb_build_object('sets',1,'reps',10));
      INSERT INTO public.active_sessions(id,user_id,template_id,template_name,start_time,session_data,
        expires_at,version,schema_version,status,review_required,template_revision)
      VALUES (session_id,owner_id,template_id,'Frozen snapshot routine',start_at,
        jsonb_build_object('exercises',jsonb_build_array(session_exercise)),now()+interval '1 day',1,
        CASE WHEN case_name IN ('session_typed','session_v2_missing_tracking') THEN 2 ELSE 1 END,
        'ACTIVE',false,1);
    END IF;
    IF case_name IN ('history_null','upload_replay_old') THEN
      INSERT INTO public.workout_logs(id,user_id,template_id,template_name,date,start_time,end_time,total_sets,total_reps,total_volume)
      VALUES (workout_id,owner_id,template_id,'Frozen snapshot routine',start_at::date,start_at,start_at+interval '1 minute',1,10,200);
      INSERT INTO public.exercise_logs(id,workout_log_id,exercise_id,exercise_name,muscle_category,
        order_index,total_sets,total_reps,total_volume,input_defaults)
      VALUES (exercise_log_id,workout_id,'ex_core_1','Frozen exercise name','Frozen muscle category',0,1,10,200,'{"sets":1,"reps":10}');
      INSERT INTO public.workout_sets(id,exercise_log_id,workout_log_id,exercise_id,set_number,weight,reps,is_completed)
      VALUES (set_id,exercise_log_id,workout_id,'ex_core_1',1,20,10,true);
      UPDATE legacy_snapshot_cases c SET stored_before = to_jsonb(e)
      FROM public.exercise_logs e WHERE c.name = case_name AND e.id = exercise_log_id;
    END IF;
    IF case_name = 'upload_replay_old' THEN
      v_expected := '{"workout_id":"old-response-kept-verbatim","server_updated_at":"2026-01-02T03:04:05.123456Z","nested":{"a":null,"b":[3,2,1]}}';
      INSERT INTO imfit_private.local_workout_receipts(workout_id,user_id,payload,result)
      VALUES (workout_id,owner_id,jsonb_build_object('workout',workout,'exercises',exercises,'sets',sets),v_expected);
      UPDATE legacy_snapshot_cases SET receipt = v_expected WHERE name = case_name;
    ELSIF case_name = 'session_replay_old' THEN
      v_expected := '{"status":"COMPLETED","workout_id":null,"snapshot":{"tracking_type":null},"server_time":"2026-01-02T03:04:05.123456Z"}';
      INSERT INTO imfit_private.operation_receipts(user_id,request_id,operation,resource_type,resource_id,payload,result)
      VALUES (owner_id,request_id,'finish_active_session','SESSION',session_id,'{"expected_version":1}',v_expected);
      UPDATE legacy_snapshot_cases SET receipt = v_expected WHERE name = case_name;
    END IF;
  END LOOP;
  -- Pending payloads and active sessions above were frozen before this catalog update.
  -- This is valid both before T03 and after its category/profile CHECK is installed.
  UPDATE public.exercises SET modality = 'ISOMETRIC', default_tracking_type = 'DURATION' WHERE id IN ('ex_back_2','ex_core_1');
END;
$fixtures$;
SET LOCAL ROLE authenticated;
DO $behavior$
DECLARE
  c record;
  detail jsonb;
  result jsonb;
  replay jsonb;
  bad jsonb;
  workout jsonb;
  exercises jsonb;
  sets jsonb;
  workout_id uuid;
  exercise_log_id uuid;
  set_id uuid;
  i integer;
BEGIN
  FOR c IN SELECT * FROM legacy_snapshot_cases ORDER BY name LOOP
    PERFORM set_config('request.jwt.claim.sub',c.owner_id::text,true);
    PERFORM set_config('request.jwt.claims',jsonb_build_object('sub',c.owner_id,'role','authenticated')::text,true);
    IF c.name IN ('upload_replay_old','session_replay_old') THEN
      result := CASE WHEN c.name = 'upload_replay_old'
        THEN public.persist_local_workout_aggregate(c.workout,c.exercises,c.sets)
        ELSE public.finish_active_session(c.request_id,c.session_id,1) END;
      IF result IS DISTINCT FROM c.receipt OR result::text IS DISTINCT FROM c.receipt::text THEN
        RAISE EXCEPTION 'Old receipt JSON/text changed or new validation ran before replay: %',c.name;
      END IF;
      CONTINUE;
    ELSIF c.name LIKE 'session_invalid_%' THEN
      BEGIN
        PERFORM public.finish_active_session(c.request_id,c.session_id,1);
        RAISE EXCEPTION 'Accepted invalid explicit session metadata: %',c.name;
      EXCEPTION WHEN SQLSTATE 'PT400' THEN NULL;
      END;
      CONTINUE;
    ELSIF c.name LIKE 'upload_%' THEN
      result := public.persist_local_workout_aggregate(c.workout,c.exercises,c.sets);
      replay := public.persist_local_workout_aggregate(c.workout,c.exercises,c.sets);
      IF replay IS DISTINCT FROM result OR replay::text IS DISTINCT FROM result::text THEN
        RAISE EXCEPTION 'Fresh upload receipt is not stable: %',c.name;
      END IF;
    ELSIF c.name LIKE 'session_%' THEN
      -- CAS must still reject stale versions before finalization.
      BEGIN
        PERFORM public.finish_active_session(gen_random_uuid(),c.session_id,2);
        RAISE EXCEPTION 'Session finalization lost CAS validation: %',c.name;
      EXCEPTION WHEN SQLSTATE 'PT409' THEN NULL;
      END;
      result := public.finish_active_session(c.request_id,c.session_id,1);
      replay := public.finish_active_session(c.request_id,c.session_id,1);
      IF replay IS DISTINCT FROM result OR replay::text IS DISTINCT FROM result::text THEN
        RAISE EXCEPTION 'Session completion receipt is not stable: %',c.name;
      END IF;
      UPDATE legacy_snapshot_cases SET workout_id = (result->>'workout_id')::uuid WHERE name = c.name;
      c.workout_id := (result->>'workout_id')::uuid;
    END IF;
    IF c.name <> 'history_null' THEN
      UPDATE legacy_snapshot_cases SET receipt = result WHERE name = c.name;
    END IF;
    detail := public.get_workout_detail(c.workout_id);
    IF detail#>>'{exercise_logs,0,tracking_type}' IS DISTINCT FROM c.expected->>'tracking_type'
      OR detail#>'{exercise_logs,0,modality}' IS DISTINCT FROM c.expected->'modality'
      OR detail#>>'{exercise_logs,0,exercise_name}' IS DISTINCT FROM 'Frozen exercise name'
      OR detail#>>'{exercise_logs,0,muscle_category}' IS DISTINCT FROM 'Frozen muscle category'
      OR (detail->>'total_volume')::numeric IS DISTINCT FROM (c.expected->>'total_volume')::numeric
      OR (detail->>'additional_volume')::numeric IS DISTINCT FROM (c.expected->>'additional_volume')::numeric
      OR detail->>'data_quality' IS DISTINCT FROM c.expected->>'data_quality'
      OR detail#>>'{exercise_logs,0,metric_quality}' IS DISTINCT FROM c.expected->>'data_quality' THEN
      RAISE EXCEPTION 'Frozen metadata, metrics or provenance changed: %',c.name;
    END IF;
  END LOOP;
  SELECT * INTO c FROM legacy_snapshot_cases WHERE name = 'upload_v1';
  PERFORM set_config('request.jwt.claim.sub',c.owner_id::text,true);
  PERFORM set_config('request.jwt.claims',jsonb_build_object('sub',c.owner_id,'role','authenticated')::text,true);
  -- Invalid metadata on a pending first upload is rejected, never replaced with defaults.
  FOR i IN 1..9 LOOP
    workout_id := gen_random_uuid(); exercise_log_id := gen_random_uuid(); set_id := gen_random_uuid();
    workout := c.workout || jsonb_build_object('id',workout_id);
    exercises := jsonb_build_array((c.exercises->0) || jsonb_build_object('id',exercise_log_id,'workout_log_id',workout_id));
    sets := jsonb_build_array((c.sets->0) || jsonb_build_object('id',set_id,'exercise_log_id',exercise_log_id,'workout_log_id',workout_id));
    bad := CASE i
      WHEN 1 THEN '{"tracking_type":null}'::jsonb
      WHEN 2 THEN '{"tracking_type":"UNKNOWN"}'::jsonb
      WHEN 3 THEN '{"tracking_type":42}'::jsonb
      WHEN 4 THEN '{"modality":"UNKNOWN"}'::jsonb
      WHEN 5 THEN '{"modality":42}'::jsonb
      WHEN 6 THEN '{"modality":""}'::jsonb
      WHEN 7 THEN '{"tracking_type":"WEIGHT_REPS"}'::jsonb -- Plank never allowed this typed mode.
      ELSE '{}'::jsonb END;
    exercises := jsonb_build_array((exercises->0) || bad);
    IF i = 8 THEN sets := jsonb_set(sets,'{0,weight}','-1'); END IF;
    IF i = 9 THEN workout := workout || '{"total_volume":201}'; END IF;
    INSERT INTO legacy_snapshot_rejections VALUES (workout_id);
    BEGIN
      PERFORM public.persist_local_workout_aggregate(workout,exercises,sets);
      RAISE EXCEPTION 'Accepted invalid pending upload metadata, mode or metrics: %',i;
    EXCEPTION WHEN invalid_parameter_value THEN NULL;
    END;
  END LOOP;
  BEGIN
    PERFORM public.persist_local_workout_aggregate(c.workout || '{"template_name":"Changed retry"}',c.exercises,c.sets);
    RAISE EXCEPTION 'Changed retry payload reused a receipt';
  EXCEPTION WHEN SQLSTATE 'PT409' THEN NULL;
  END;
  -- Switching authenticated identity must not permit another owner's receipt/history.
  PERFORM set_config('request.jwt.claim.sub',(SELECT owner_id::text FROM legacy_snapshot_cases WHERE name = 'upload_typed'),true);
  PERFORM set_config('request.jwt.claims',jsonb_build_object('sub',auth.uid(),'role','authenticated')::text,true);
  BEGIN
    PERFORM public.persist_local_workout_aggregate(c.workout,c.exercises,c.sets);
    RAISE EXCEPTION 'Cross-owner receipt replay accepted';
  EXCEPTION WHEN insufficient_privilege THEN NULL;
  END;
  BEGIN
    PERFORM public.get_workout_detail(c.workout_id);
    RAISE EXCEPTION 'Cross-owner history read accepted';
  EXCEPTION WHEN SQLSTATE 'PT404' THEN NULL;
  END;
END;
$behavior$;
DO $prefill_compatibility$
DECLARE c record; result jsonb;
BEGIN
  SELECT * INTO c FROM legacy_snapshot_cases WHERE name = 'history_null';
  PERFORM set_config('request.jwt.claim.sub',c.owner_id::text,true);
  PERFORM set_config('request.jwt.claims',jsonb_build_object('sub',c.owner_id,'role','authenticated')::text,true);
  -- These defaults exactly equal the historical row's input_defaults, but today's
  -- catalog selects DURATION. Scalar equality cannot reinterpret v1 WEIGHT_REPS.
  result := public.get_exercise_prefill('ex_core_1','{"sets":1,"reps":10}');
  IF result->>'source' IS DISTINCT FROM 'DEFAULTS'
    OR result->'source_workout_id' IS DISTINCT FROM 'null'::jsonb
    OR result#>>'{sets,0,weight}' IS DISTINCT FROM '0.00' THEN
    RAISE EXCEPTION 'Catalog-derived DURATION reused null-tracking v1 weight/reps history';
  END IF;
  result := public.get_exercise_prefill('ex_core_1','{"sets":2,"reps":8,"tracking_type":"WEIGHT_REPS"}');
  IF result->>'source' IS DISTINCT FROM 'DEFAULTS'
    OR result->'source_workout_id' IS DISTINCT FROM 'null'::jsonb
    OR jsonb_array_length(result->'sets') IS DISTINCT FROM 2
    OR result#>>'{sets,0,weight}' IS DISTINCT FROM '0.00'
    OR (result#>>'{sets,0,reps}')::integer IS DISTINCT FROM 8
    OR result#>>'{sets,1,weight}' IS DISTINCT FROM '0.00'
    OR (result#>>'{sets,1,reps}')::integer IS DISTINCT FROM 8 THEN
    RAISE EXCEPTION 'Mismatched legacy prescription bypassed the original prefill fingerprint guard';
  END IF;
  SELECT * INTO c FROM legacy_snapshot_cases WHERE name = 'upload_typed';
  PERFORM set_config('request.jwt.claim.sub',c.owner_id::text,true);
  PERFORM set_config('request.jwt.claims',jsonb_build_object('sub',c.owner_id,'role','authenticated')::text,true);
  result := public.get_exercise_prefill('ex_back_2','{"sets":1,"reps":8,"tracking_type":"BODYWEIGHT_REPS"}');
  IF result->>'source' IS DISTINCT FROM 'HISTORY'
    OR result->>'source_workout_id' IS DISTINCT FROM c.workout_id::text
    OR (result#>>'{sets,0,reps}')::integer IS DISTINCT FROM 10
    OR result#>>'{sets,0,additional_weight}' IS DISTINCT FROM '2.50' THEN
    RAISE EXCEPTION 'Typed historical scalar prefill changed after legacy compatibility fix';
  END IF;
  result := public.get_exercise_prefill('ex_back_2','{"sets":1,"reps":10,"tracking_type":"DURATION"}');
  IF result->>'source' IS DISTINCT FROM 'DEFAULTS'
    OR result->'source_workout_id' IS DISTINCT FROM 'null'::jsonb THEN
    RAISE EXCEPTION 'Incompatible typed historical format entered scalar prefill';
  END IF;
  RAISE NOTICE 'PASS: prefill null tracking is historical WEIGHT_REPS, catalog-derived DURATION excludes it, and compatible legacy/typed scalar history is preserved';
END;
$prefill_compatibility$;
RESET ROLE;
SAVEPOINT compatible_legacy_prefill;
-- Same frozen v1 defaults are compatible only when the requested format is also
-- historical WEIGHT_REPS. The temporary catalog change is rolled back below.
UPDATE public.exercises SET modality='STRENGTH',default_tracking_type='WEIGHT_REPS'
WHERE id='ex_core_1';
SET LOCAL ROLE authenticated;
DO $matched_legacy_prefill$
DECLARE c RECORD; result JSONB;
BEGIN
  SELECT * INTO c FROM legacy_snapshot_cases WHERE name='history_null';
  PERFORM set_config('request.jwt.claim.sub',c.owner_id::TEXT,true);
  PERFORM set_config('request.jwt.claims',jsonb_build_object('sub',c.owner_id,'role','authenticated')::TEXT,true);
  result := public.get_exercise_prefill('ex_core_1','{"sets":1,"reps":10}');
  IF result->>'source' IS DISTINCT FROM 'HISTORY'
     OR result->>'source_workout_id' IS DISTINCT FROM c.workout_id::TEXT
     OR result#>>'{sets,0,weight}' IS DISTINCT FROM '20.00'
     OR (result#>>'{sets,0,reps}')::INTEGER IS DISTINCT FROM 10 THEN
    RAISE EXCEPTION 'Compatible legacy format/defaults no longer preserve historical prefill';
  END IF;
END;
$matched_legacy_prefill$;
RESET ROLE;
ROLLBACK TO SAVEPOINT compatible_legacy_prefill;
RELEASE SAVEPOINT compatible_legacy_prefill;
-- A second catalog update must not reclassify already imported/finalized snapshots.
UPDATE public.exercises SET modality = 'STRENGTH', default_tracking_type = 'WEIGHT_REPS' WHERE id = 'ex_back_2';
SET LOCAL ROLE authenticated;
DO $after_catalog_change$
DECLARE c record; detail jsonb; result jsonb; page jsonb; item jsonb;
BEGIN
  FOR c IN SELECT * FROM legacy_snapshot_cases WHERE name IN
    ('history_null','upload_v1','upload_v1_null_modality','upload_typed','upload_typed_unknown_modality',
     'session_v1','session_v2_missing_tracking','session_typed','session_null_modality') LOOP
    PERFORM set_config('request.jwt.claim.sub',c.owner_id::text,true);
    PERFORM set_config('request.jwt.claims',jsonb_build_object('sub',c.owner_id,'role','authenticated')::text,true);
    detail := public.get_workout_detail(c.workout_id);
    IF detail#>>'{exercise_logs,0,tracking_type}' IS DISTINCT FROM c.expected->>'tracking_type'
      OR detail#>'{exercise_logs,0,modality}' IS DISTINCT FROM c.expected->'modality' THEN
      RAISE EXCEPTION 'Catalog update changed history metadata: %',c.name;
    END IF;
    IF c.name LIKE 'upload_%' THEN
      result := public.persist_local_workout_aggregate(c.workout,c.exercises,c.sets);
    ELSIF c.name LIKE 'session_%' THEN
      result := public.finish_active_session(c.request_id,c.session_id,1);
    ELSE result := NULL;
    END IF;
    IF c.name <> 'history_null' AND (result IS DISTINCT FROM c.receipt OR result::text IS DISTINCT FROM c.receipt::text) THEN
      RAISE EXCEPTION 'Catalog update changed receipt replay: %',c.name;
    END IF;
    -- Sync is a raw frozen snapshot, not a catalog projection. Nullable legacy
    -- tracking may remain NULL here while detail exposes the v1 WEIGHT_REPS contract.
    page := public.get_workout_sync_page(NULL,25);
    SELECT value INTO item FROM jsonb_array_elements(page->'items') WHERE value#>>'{workout,id}' = c.workout_id::text;
    IF item IS NULL OR item#>'{exercises,0,modality}' IS DISTINCT FROM c.expected->'modality' THEN
      RAISE EXCEPTION 'Sync projection changed frozen modality: %',c.name;
    END IF;
    result := public.get_progress_summary((c.workout->>'date')::date,(c.workout->>'date')::date+1,'UTC');
    IF (result->>'total_volume')::numeric IS DISTINCT FROM (c.expected->>'total_volume')::numeric
      OR (result->>'additional_volume_kg')::numeric IS DISTINCT FROM (c.expected->>'additional_volume')::numeric
      OR (result->>'cardio_duration_seconds')::bigint IS DISTINCT FROM 0
      OR (result->>'hold_duration_seconds')::bigint IS DISTINCT FROM 0 THEN
      RAISE EXCEPTION 'Progress reinterpreted frozen metrics: %',c.name;
    END IF;
    page := public.get_workout_history_page((c.workout->>'date')::date,(c.workout->>'date')::date+1,'UTC',NULL,20);
    IF jsonb_array_length(page->'data') IS DISTINCT FROM 1
      OR (page#>>'{data,0,total_volume}')::numeric IS DISTINCT FROM (c.expected->>'total_volume')::numeric THEN
      RAISE EXCEPTION 'History summary changed frozen metrics: %',c.name;
    END IF;
  END LOOP;
END;
$after_catalog_change$;
RESET ROLE;
DO $unchanged_storage$
BEGIN
  IF EXISTS (SELECT 1 FROM legacy_snapshot_cases c JOIN public.exercise_logs e
      ON e.id = (c.exercises#>>'{0,id}')::uuid
      WHERE c.stored_before IS NOT NULL AND to_jsonb(e) IS DISTINCT FROM c.stored_before) THEN
    RAISE EXCEPTION 'Read/replay rewrote persisted historical exercise rows';
  END IF;
  IF EXISTS (SELECT 1 FROM legacy_snapshot_rejections r JOIN public.workout_logs w ON w.id = r.workout_id)
    OR EXISTS (SELECT 1 FROM legacy_snapshot_rejections r JOIN imfit_private.local_workout_receipts p ON p.workout_id = r.workout_id) THEN
    RAISE EXCEPTION 'Rejected pending uploads left partial history or receipts';
  END IF;
  IF EXISTS (SELECT 1 FROM legacy_snapshot_cases c JOIN public.active_sessions s ON s.id = c.session_id
      WHERE c.name LIKE 'session_invalid_%' AND (s.status <> 'ACTIVE' OR s.version <> 1))
    OR EXISTS (SELECT 1 FROM legacy_snapshot_cases c JOIN imfit_private.operation_receipts r ON r.request_id = c.request_id
      WHERE c.name LIKE 'session_invalid_%') THEN
    RAISE EXCEPTION 'Rejected session finalization changed state or created a receipt';
  END IF;
  IF EXISTS (SELECT 1 FROM legacy_snapshot_cases c JOIN imfit_private.local_workout_receipts r ON r.workout_id = c.workout_id
      WHERE c.name = 'upload_replay_old' AND (r.result IS DISTINCT FROM c.receipt OR r.payload IS DISTINCT FROM
        jsonb_build_object('workout',c.workout,'exercises',c.exercises,'sets',c.sets)))
    OR EXISTS (SELECT 1 FROM legacy_snapshot_cases c JOIN imfit_private.operation_receipts r ON r.request_id = c.request_id
      WHERE c.name = 'session_replay_old' AND (r.result IS DISTINCT FROM c.receipt OR r.payload <> '{"expected_version":1}'::jsonb)) THEN
    RAISE EXCEPTION 'Replay rewrote an existing receipt or its payload';
  END IF;
  RAISE NOTICE 'PASS: frozen history/import/finalization metadata, missing/null semantics, CALISTHENICS after catalog changes, pending validation, ownership/CAS/metrics and JSON/text-identical receipt replay';
END;
$unchanged_storage$;
ROLLBACK;

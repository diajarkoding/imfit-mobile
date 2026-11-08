-- T11 post-deployment smoke. Run as postgres using psql -X --set=ON_ERROR_STOP=1 --file=... .
-- No test-suite execution and no real-user/catalog mutations: all writes belong to
-- one random synthetic owner or two random synthetic catalog IDs, and roll back.
-- Do not wrap this file in --single-transaction: it owns BEGIN/ROLLBACK. On any
-- unhandled error ON_ERROR_STOP makes noninteractive psql exit; connection close
-- rolls back the failed transaction. PASS is emitted only AFTER successful rollback.
\set ON_ERROR_STOP on
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';
SET LOCAL idle_in_transaction_session_timeout = '120s';
SET LOCAL TIME ZONE 'UTC';

CREATE TEMP TABLE architectural_smoke_state (
  owner_id UUID, strength_id TEXT, cardio_id TEXT, routine_id UUID, session_id UUID,
  legacy_v1 UUID, legacy_v2 UUID, upload_id UUID, old_request UUID, old_session UUID,
  scalar_request UUID, scalar_payload JSONB, scalar_receipt JSONB, scalar_rows JSONB,
  old_workout JSONB, old_exercises JSONB, old_sets JSONB, old_result JSONB,
  old_receipt_row JSONB, old_operation_row JSONB, legacy_rows JSONB,
  create_request UUID, initial JSONB, finished JSONB,
  upload_workout JSONB, upload_exercises JSONB, upload_sets JSONB, upload_receipt JSONB,
  uploaded_rows JSONB, upload_receipt_row JSONB
) ON COMMIT DROP;
GRANT SELECT, UPDATE ON architectural_smoke_state TO authenticated;

DO $fixture$
DECLARE
  owner_id UUID := gen_random_uuid(); v1 UUID := gen_random_uuid(); v2 UUID := gen_random_uuid();
  s1 UUID := gen_random_uuid(); s2 UUID := gen_random_uuid(); e1 UUID := gen_random_uuid(); e2 UUID := gen_random_uuid();
  strength_id TEXT := 't11s_' || replace(gen_random_uuid()::TEXT, '-', '');
  cardio_id TEXT := 't11c_' || replace(gen_random_uuid()::TEXT, '-', '');
  cat INTEGER; started TIMESTAMPTZ := date_trunc('day', now()) - INTERVAL '1 day' + INTERVAL '10 hours';
  old_workout JSONB; old_exercises JSONB; old_sets JSONB; old_result JSONB;
  old_request UUID := gen_random_uuid(); old_session UUID := gen_random_uuid();
BEGIN
  -- Reuse only the category FK; no identity sequence is advanced by this smoke.
  SELECT id INTO STRICT cat FROM public.muscle_categories ORDER BY id LIMIT 1;
  INSERT INTO auth.users(id,email,raw_user_meta_data)
    VALUES(owner_id,'t11-' || owner_id::TEXT || '@example.invalid','{"name":"T11 rollback smoke"}');
  IF NOT EXISTS(SELECT 1 FROM public.profiles WHERE id=owner_id) THEN
    RAISE EXCEPTION 'T11: auth trigger failed to create the synthetic profile';
  END IF;
  INSERT INTO public.exercises(id,muscle_category_id,name,description,modality,default_tracking_type,tracking_profile,supported_metrics)
    VALUES(strength_id,cat,'T11 strength ' || owner_id::TEXT,'Rollback-only fixture','STRENGTH','WEIGHT_REPS',NULL,'{}'),
      (cardio_id,cat,'T11 cardio ' || owner_id::TEXT,'Rollback-only fixture','CARDIO','DURATION','equipment_metrics',ARRAY['calories','distance','speed','incline']);
  INSERT INTO public.exercise_tracking_modes(exercise_id,tracking_type)
    VALUES(strength_id,'WEIGHT_REPS'),(cardio_id,'DURATION'),(cardio_id,'DISTANCE_DURATION');

  -- v1 has unknown frozen metadata; v2 retains its explicit historical format.
  -- Deliberately bogus cached set/repetition counts and an unfinished draft prove
  -- that progress derives completed results, not these cached aggregates.
  INSERT INTO public.workout_logs(id,user_id,template_name,date,start_time,end_time,total_volume,total_sets,total_reps,total_distance_meters)
    VALUES(v1,owner_id,'T11 legacy v1',started::DATE,started,started+INTERVAL '1 minute',200,999,999,0),
      (v2,owner_id,'T11 legacy v2',started::DATE,started+INTERVAL '1 hour',started+INTERVAL '61 minutes',0,1,0,500);
  INSERT INTO public.exercise_logs(id,workout_log_id,exercise_id,exercise_name,muscle_category,tracking_type,modality,input_defaults,
    total_volume,total_sets,total_reps,total_duration_seconds,total_distance_meters)
    VALUES(e1,v1,strength_id,'Frozen v1 name','Frozen v1 category',NULL,NULL,'{"sets":1,"reps":10}',200,999,999,0,0),
      (e2,v2,cardio_id,'Frozen v2 name','Frozen v2 category','DISTANCE_DURATION','CARDIO',
        '{"sets":1,"reps":10,"tracking_type":"DISTANCE_DURATION"}',0,1,0,60,500);
  INSERT INTO public.workout_sets(id,exercise_log_id,workout_log_id,exercise_id,set_number,weight,reps,duration_seconds,distance_meters,is_completed)
    VALUES(s1,e1,v1,strength_id,1,20,10,NULL,NULL,TRUE),
      (gen_random_uuid(),e1,v1,strength_id,2,999,999,NULL,NULL,FALSE),
      (s2,e2,v2,cardio_id,1,0,0,60,500,TRUE);

  -- A historical receipt can contain data today's writer would reject. The
  -- immutable result must win BEFORE new metadata/version validation executes.
  old_workout := jsonb_build_object('id',v1,'user_id',owner_id,'template_name','T11 old receipt','measurement_version',99);
  old_exercises := jsonb_build_array(jsonb_build_object('id',e1,'tracking_type',NULL,'modality','PREVIOUSLY_IGNORED'));
  old_sets := jsonb_build_array(jsonb_build_object('id',s1,'weight',20,'reps',10));
  old_result := jsonb_build_object('workout_id',v1,'server_updated_at','2026-01-02T03:04:05.123456Z','nested',
    jsonb_build_object('a',NULL,'b',jsonb_build_array(3,2,1)));
  INSERT INTO imfit_private.local_workout_receipts(workout_id,user_id,payload,result)
    VALUES(v1,owner_id,jsonb_build_object('workout',old_workout,'exercises',old_exercises,'sets',old_sets),old_result);
  INSERT INTO imfit_private.operation_receipts(user_id,request_id,operation,resource_type,resource_id,payload,result)
    VALUES(owner_id,old_request,'finish_active_session','SESSION',old_session,'{"expected_version":1}',
      '{"status":"COMPLETED","workout_id":null,"snapshot":{"tracking_type":null},"server_time":"2026-01-02T03:04:05.123456Z"}');
  INSERT INTO architectural_smoke_state(owner_id,strength_id,cardio_id,routine_id,session_id,legacy_v1,legacy_v2,upload_id,
    old_request,old_session,old_workout,old_exercises,old_sets,old_result,old_receipt_row,old_operation_row,legacy_rows)
    SELECT owner_id,strength_id,cardio_id,gen_random_uuid(),gen_random_uuid(),v1,v2,gen_random_uuid(),old_request,old_session,
      old_workout,old_exercises,old_sets,old_result,
      (SELECT to_jsonb(r) FROM imfit_private.local_workout_receipts r WHERE workout_id=v1),
      (SELECT to_jsonb(r) FROM imfit_private.operation_receipts r WHERE user_id=owner_id AND request_id=old_request),
      jsonb_build_object('workouts',(SELECT jsonb_agg(to_jsonb(w) ORDER BY id) FROM public.workout_logs w WHERE user_id=owner_id),
        'exercises',(SELECT jsonb_agg(to_jsonb(e) ORDER BY id) FROM public.exercise_logs e WHERE workout_log_id IN(v1,v2)),
        'sets',(SELECT jsonb_agg(to_jsonb(s) ORDER BY id) FROM public.workout_sets s WHERE workout_log_id IN(v1,v2)));
END;
$fixture$;

SET LOCAL ROLE authenticated;
DO $start$
DECLARE c architectural_smoke_state%ROWTYPE; saved JSONB; result JSONB; req UUID := gen_random_uuid(); scalar_req UUID := gen_random_uuid();
  scalar JSONB; legacy_receipt JSONB;
  strength_targets JSONB := '[{"row_id":"strength-row","set_number":1,"weight":37.5,"reps":8,"duration_seconds":null,"rest_seconds":0}]';
  cardio_targets JSONB := '[{"row_id":"cardio-row","set_number":1,"weight":null,"reps":null,"duration_seconds":90,"rest_seconds":120}]';
BEGIN
  SELECT * INTO STRICT c FROM architectural_smoke_state;
  PERFORM set_config('request.jwt.claim.sub',c.owner_id::TEXT,TRUE);
  PERFORM set_config('request.jwt.claims',jsonb_build_object('sub',c.owner_id,'role','authenticated')::TEXT,TRUE);
  scalar := jsonb_build_array(jsonb_build_object('exercise_id',c.strength_id,'order_index',0,'sets',1,'reps',10,'rest_seconds',0,'tracking_type','WEIGHT_REPS'));
  legacy_receipt := public.save_template_revision(scalar_req,c.routine_id,NULL,'T11 rollback routine',scalar,'[]',FALSE,c.owner_id);
  saved := public.save_template_revision(gen_random_uuid(),c.routine_id,(legacy_receipt->>'revision')::BIGINT,'T11 rollback routine',
    jsonb_build_array(jsonb_build_object('exercise_id',c.strength_id,'order_index',0,'set_targets',strength_targets),
      jsonb_build_object('exercise_id',c.cardio_id,'order_index',1,'set_targets',cardio_targets)),'[]',FALSE,c.owner_id,3);
  result := public.get_template_detail(c.routine_id);
  IF result->'measurement_version' IS DISTINCT FROM '3'::JSONB
    OR result#>'{exercises,0,set_targets}' IS DISTINCT FROM strength_targets
    OR result#>'{exercises,1,set_targets}' IS DISTINCT FROM cardio_targets THEN
    RAISE EXCEPTION 'T11: routine target IDs/decimals/rest did not round-trip';
  END IF;
  result := public.create_active_session(req,c.session_id,c.routine_id,(saved->>'revision')::BIGINT);
  IF result#>'{snapshot,schema_version}' IS DISTINCT FROM '3'::JSONB
    OR result#>'{snapshot,exercises,0,input_defaults,set_targets}' IS DISTINCT FROM strength_targets
    OR result#>'{snapshot,exercises,1,input_defaults,set_targets}' IS DISTINCT FROM cardio_targets
    OR result#>'{snapshot,exercises,1,sets,0,weight}' IS DISTINCT FROM 'null'::JSONB
    OR result#>>'{snapshot,exercises,0,sets,0,target_row_id}' IS DISTINCT FROM 'strength-row' THEN
    RAISE EXCEPTION 'T11: session creation lost frozen targets, result associations or nullable metrics';
  END IF;
  IF public.save_template_revision(scalar_req,c.routine_id,NULL,'T11 rollback routine',scalar,'[]',FALSE,c.owner_id) IS DISTINCT FROM legacy_receipt THEN
    RAISE EXCEPTION 'T11: old scalar receipt changed after routine conversion';
  END IF;
  UPDATE architectural_smoke_state SET initial=result,create_request=req,scalar_request=scalar_req,scalar_payload=scalar,scalar_receipt=legacy_receipt;
END;
$start$;
RESET ROLE;

-- Snapshot the old scalar receipt rows including retention/commit metadata.
UPDATE architectural_smoke_state c SET scalar_rows=jsonb_build_object(
  'template',(SELECT to_jsonb(r) FROM public.template_write_receipts r WHERE r.user_id=c.owner_id AND r.request_id=c.scalar_request),
  'operation',(SELECT to_jsonb(r) FROM imfit_private.operation_receipts r WHERE r.user_id=c.owner_id AND r.request_id=c.scalar_request));
-- Catalog drift is confined to our synthetic row. The live catalog is untouched.
UPDATE public.exercises e SET name='T11 drifted ' || c.owner_id::TEXT,tracking_profile='duration_only',supported_metrics='{}'
  FROM architectural_smoke_state c WHERE e.id=c.cardio_id;

SET LOCAL ROLE authenticated;
DO $lifecycle$
DECLARE c architectural_smoke_state%ROWTYPE; result JSONB; replay JSONB; detail JSONB; prefill JSONB; v BIGINT;
  strength UUID; cardio UUID; strength_set UUID; cardio_set UUID; req UUID := gen_random_uuid();
BEGIN
  SELECT * INTO STRICT c FROM architectural_smoke_state;
  PERFORM set_config('request.jwt.claim.sub',c.owner_id::TEXT,TRUE);
  PERFORM set_config('request.jwt.claims',jsonb_build_object('sub',c.owner_id,'role','authenticated')::TEXT,TRUE);
  v := (c.initial->>'version')::BIGINT;
  strength := (c.initial#>>'{snapshot,exercises,0,id}')::UUID; cardio := (c.initial#>>'{snapshot,exercises,1,id}')::UUID;
  strength_set := (c.initial#>>'{snapshot,exercises,0,sets,0,id}')::UUID; cardio_set := (c.initial#>>'{snapshot,exercises,1,sets,0,id}')::UUID;
  result := public.update_active_session(gen_random_uuid(),c.session_id,v,
    jsonb_build_object('type','complete_set','exercise_log_id',strength,'set_id',strength_set));
  v := (result->>'version')::BIGINT;
  result := public.update_active_session(req,c.session_id,v,jsonb_build_object('type','edit_set','exercise_log_id',cardio,'set_id',cardio_set,
    'distance_meters',1234.56,'speed_kph',12.34,'calories_kcal',0,'incline_percent',-2.5));
  replay := public.update_active_session(req,c.session_id,v,jsonb_build_object('type','edit_set','exercise_log_id',cardio,'set_id',cardio_set,
    'distance_meters',1234.56,'speed_kph',12.34,'calories_kcal',0,'incline_percent',-2.5));
  IF replay IS DISTINCT FROM result THEN RAISE EXCEPTION 'T11: update receipt changed on replay'; END IF;
  v := (result->>'version')::BIGINT;
  result := public.update_active_session(gen_random_uuid(),c.session_id,v,
    jsonb_build_object('type','edit_set','exercise_log_id',cardio,'set_id',cardio_set,'distance_meters',NULL));
  v := (result->>'version')::BIGINT;
  IF result#>'{snapshot,exercises,1,sets,0,distance_meters}' IS DISTINCT FROM 'null'::JSONB
    OR result#>'{snapshot,exercises,1,sets,0,speed_kph}' IS DISTINCT FROM '12.34'::JSONB
    OR result#>'{snapshot,exercises,1,input_defaults}' IS DISTINCT FROM c.initial#>'{snapshot,exercises,1,input_defaults}' THEN
    RAISE EXCEPTION 'T11: PATCH clear/omitted fields or frozen capabilities changed';
  END IF;
  result := public.update_active_session(gen_random_uuid(),c.session_id,v,
    jsonb_build_object('type','complete_set','exercise_log_id',cardio,'set_id',cardio_set));
  v := (result->>'version')::BIGINT; req := gen_random_uuid();
  result := public.finish_active_session(req,c.session_id,v);
  IF result->>'workout_id' IS DISTINCT FROM c.session_id::TEXT
    OR public.finish_active_session(req,c.session_id,v) IS DISTINCT FROM result
    OR public.create_active_session(c.create_request,c.session_id,c.routine_id,(c.initial#>>'{snapshot,template_revision}')::BIGINT) IS DISTINCT FROM c.initial THEN
    RAISE EXCEPTION 'T11: finish/create receipt or stable workout ID changed';
  END IF;
  UPDATE architectural_smoke_state SET finished=result;
  detail := public.get_workout_detail(c.session_id);
  IF detail->'measurement_version' IS DISTINCT FROM '3'::JSONB OR (detail->>'total_volume')::NUMERIC IS DISTINCT FROM 0::NUMERIC
    OR (detail->>'recorded_load_volume')::NUMERIC IS DISTINCT FROM 300::NUMERIC
    OR detail#>>'{exercise_logs,0,id}' IS DISTINCT FROM strength::TEXT
    OR detail#>>'{exercise_logs,0,sets,0,id}' IS DISTINCT FROM strength_set::TEXT
    OR detail#>'{exercise_logs,0,sets,0,weight}' IS DISTINCT FROM '37.5'::JSONB
    OR detail#>'{exercise_logs,1,sets,0,weight}' IS DISTINCT FROM 'null'::JSONB
    OR detail#>'{exercise_logs,1,sets,0,reps}' IS DISTINCT FROM 'null'::JSONB
    OR detail#>'{exercise_logs,1,sets,0,distance_meters}' IS DISTINCT FROM 'null'::JSONB
    OR detail#>'{exercise_logs,1,sets,0,incline_percent}' IS DISTINCT FROM '-2.5'::JSONB
    OR detail#>'{exercise_logs,1,sets,0,calories_kcal}' IS DISTINCT FROM '0'::JSONB
    OR detail#>>'{exercise_logs,1,exercise_name}' IS DISTINCT FROM c.initial#>>'{snapshot,exercises,1,name}'
    OR detail#>'{exercise_logs,1,input_defaults}' IS DISTINCT FROM c.initial#>'{snapshot,exercises,1,input_defaults}' THEN
    RAISE EXCEPTION 'T11: v3 detail lost IDs, frozen metadata/rest, decimals, null/zero or separated volume';
  END IF;
  prefill := public.get_exercise_prefill(c.strength_id,c.initial#>'{snapshot,exercises,0,input_defaults}');
  IF prefill->>'source' IS DISTINCT FROM 'TARGETS' OR prefill#>'{sets,0,weight}' IS DISTINCT FROM '37.5'::JSONB
    OR prefill#>>'{sets,0,target_row_id}' IS DISTINCT FROM 'strength-row'
    OR prefill->'input_defaults' IS DISTINCT FROM c.initial#>'{snapshot,exercises,0,input_defaults}' THEN
    RAISE EXCEPTION 'T11: targets prefill was replaced by historical performance';
  END IF;
  -- Today's sole workout has a recorded duration but deliberately no distance.
  result := public.get_progress_summary((now() AT TIME ZONE 'UTC')::DATE,(now() AT TIME ZONE 'UTC')::DATE+1,'UTC');
  IF (result->>'workout_count')::INTEGER IS DISTINCT FROM 1 OR result->'cardio_distance_meters' IS DISTINCT FROM 'null'::JSONB
    OR (result->>'cardio_duration_seconds')::INTEGER IS DISTINCT FROM 90 THEN
    RAISE EXCEPTION 'T11: optional unrecorded distance became zero or duration was discarded';
  END IF;
END;
$lifecycle$;

DO $upload_and_read$
DECLARE c architectural_smoke_state%ROWTYPE; ex JSONB; measurements JSONB; workout JSONB; receipt JSONB; result JSONB; item JSONB;
  s JSONB; detail JSONB; expected_id UUID; seen INTEGER := 0;
BEGIN
  SELECT * INTO STRICT c FROM architectural_smoke_state;
  PERFORM set_config('request.jwt.claim.sub',c.owner_id::TEXT,TRUE);
  PERFORM set_config('request.jwt.claims',jsonb_build_object('sub',c.owner_id,'role','authenticated')::TEXT,TRUE);
  -- Fresh upload AFTER catalog drift, using the original frozen snapshot and fresh
  -- result IDs; no internal persistence helper substitutes for the public RPC.
  SELECT jsonb_agg(jsonb_build_object('id',gen_random_uuid(),'workout_log_id',c.upload_id,'exercise_id',x->'exercise_id',
    'exercise_name',x->'name','muscle_category',x->'muscle_category','order_index',x->'order_index','measurement_version',3,
    'tracking_type',x->'tracking_type','modality',x->'modality','input_defaults',x->'input_defaults') ORDER BY (x->>'order_index')::INTEGER)
    INTO ex FROM jsonb_array_elements(c.initial#>'{snapshot,exercises}') x;
  SELECT jsonb_agg((x->'sets'->0)||jsonb_build_object('id',gen_random_uuid(),'workout_log_id',c.upload_id,
    'exercise_log_id',a->'id','exercise_id',x->'exercise_id','is_completed',TRUE)
    ||CASE WHEN x->>'exercise_id'=c.cardio_id THEN '{"distance_meters":1234.56,"speed_kph":12.34,"calories_kcal":0,"incline_percent":-2.5}'::JSONB ELSE '{}'::JSONB END
    ORDER BY (x->>'order_index')::INTEGER) INTO measurements
    FROM jsonb_array_elements(c.initial#>'{snapshot,exercises}') x JOIN jsonb_array_elements(ex) a ON a->>'exercise_id'=x->>'exercise_id';
  workout := jsonb_build_object('id',c.upload_id,'user_id',c.owner_id,'template_id',NULL,'template_name','T11 offline frozen v3',
    'measurement_version',3,'date',(now() AT TIME ZONE 'UTC')::DATE-1,
    'start_time',date_trunc('day',now())-INTERVAL '12 hours','end_time',date_trunc('day',now())-INTERVAL '12 hours'+INTERVAL '1 minute',
    'total_volume',0,'recorded_load_volume',300,'total_sets',2,'total_reps',8);
  receipt := public.persist_local_workout_aggregate(workout,ex,measurements);
  IF receipt->>'workout_id' IS DISTINCT FROM c.upload_id::TEXT OR receipt->'recorded_load_volume' IS DISTINCT FROM '300'::JSONB
    OR public.persist_local_workout_aggregate(workout,ex,measurements) IS DISTINCT FROM receipt THEN
    RAISE EXCEPTION 'T11: upload identity, recorded load or replay changed';
  END IF;
  BEGIN
    PERFORM public.persist_local_workout_aggregate(workout||'{"template_name":"T11 conflicting replay"}',ex,measurements);
    RAISE EXCEPTION 'T11: conflicting upload replay was accepted';
  EXCEPTION WHEN SQLSTATE 'PT409' THEN IF SQLERRM <> 'IDEMPOTENCY_MISMATCH' THEN RAISE; END IF;
  END;
  detail := public.get_workout_detail(c.upload_id);
  IF detail#>'{exercise_logs,1,input_defaults}' IS DISTINCT FROM c.initial#>'{snapshot,exercises,1,input_defaults}'
    OR detail#>'{exercise_logs,1,sets,0,distance_meters}' IS DISTINCT FROM '1234.56'::JSONB
    OR detail#>>'{exercise_logs,1,sets,0,id}' IS DISTINCT FROM measurements#>>'{1,id}'
    OR detail#>>'{exercise_logs,1,sets,0,target_row_id}' IS DISTINCT FROM 'cardio-row' THEN
    RAISE EXCEPTION 'T11: uploaded frozen metadata or decimal result identity changed';
  END IF;
  UPDATE architectural_smoke_state SET upload_workout=workout,upload_exercises=ex,upload_sets=measurements,upload_receipt=receipt;

  detail := public.get_workout_detail(c.legacy_v1);
  IF detail#>>'{exercise_logs,0,tracking_type}' IS DISTINCT FROM 'WEIGHT_REPS'
    OR detail#>'{exercise_logs,0,modality}' IS DISTINCT FROM 'null'::JSONB
    OR detail#>>'{exercise_logs,0,exercise_name}' IS DISTINCT FROM 'Frozen v1 name'
    OR jsonb_array_length(detail#>'{exercise_logs,0,sets}') <> 1
    OR jsonb_typeof(detail#>'{exercise_logs,0,sets,0,weight}') IS DISTINCT FROM 'string'
    OR detail#>'{exercise_logs,0,measurement_version}' IS DISTINCT FROM 'null'::JSONB THEN
    RAISE EXCEPTION 'T11: legacy v1 metadata, weight wire type or completed-only detail changed';
  END IF;
  result := public.get_exercise_prefill(c.strength_id,'{"sets":1,"reps":10}');
  IF result->>'source' IS DISTINCT FROM 'HISTORY' OR result->>'source_workout_id' IS DISTINCT FROM c.legacy_v1::TEXT
    OR (result#>>'{sets,0,weight}')::NUMERIC IS DISTINCT FROM 20::NUMERIC
    OR result#>'{sets,0,reps}' IS DISTINCT FROM '10'::JSONB THEN
    RAISE EXCEPTION 'T11: legacy v1 history prefill lost its frozen interpretation or mixed in v3 performance';
  END IF;
  detail := public.get_workout_detail(c.legacy_v2);
  result := public.get_exercise_prefill(c.cardio_id,'{"sets":1,"reps":10,"tracking_type":"DISTANCE_DURATION"}');
  IF detail#>>'{exercise_logs,0,tracking_type}' IS DISTINCT FROM 'DISTANCE_DURATION'
    OR detail#>>'{exercise_logs,0,exercise_name}' IS DISTINCT FROM 'Frozen v2 name'
    OR (detail->>'total_distance_meters')::INTEGER IS DISTINCT FROM 500
    OR (detail#>>'{exercise_logs,0,sets,0,distance_meters}')::INTEGER IS DISTINCT FROM 500
    OR result->>'source' IS DISTINCT FROM 'HISTORY'
    OR (result#>>'{sets,0,distance_meters}')::INTEGER IS DISTINCT FROM 500 THEN
    RAISE EXCEPTION 'T11: legacy v2 frozen format/history or integer wire distance changed';
  END IF;
  result := public.get_progress_summary((now() AT TIME ZONE 'UTC')::DATE-1,(now() AT TIME ZONE 'UTC')::DATE+1,'UTC');
  IF (result->>'workout_count')::INTEGER IS DISTINCT FROM 4 OR (result->>'total_sets')::INTEGER IS DISTINCT FROM 6
    OR (result->>'total_reps')::INTEGER IS DISTINCT FROM 26 OR (result->>'total_volume')::NUMERIC IS DISTINCT FROM 200::NUMERIC
    OR (result->>'recorded_load_volume')::NUMERIC IS DISTINCT FROM 600::NUMERIC
    OR (result->>'cardio_duration_seconds')::INTEGER IS DISTINCT FROM 240
    OR (result->>'cardio_distance_meters')::NUMERIC IS DISTINCT FROM 1734.56::NUMERIC THEN
    RAISE EXCEPTION 'T11: progress mixed legacy and recorded-load volume, counted drafts/caches or lost decimals: %',result;
  END IF;
  result := public.get_workout_sync_page(NULL,25);
  IF result->>'owner_id' IS DISTINCT FROM c.owner_id::TEXT OR jsonb_array_length(result->'items') <> 4
    OR result->'next_cursor' IS DISTINCT FROM 'null'::JSONB THEN RAISE EXCEPTION 'T11: sync ownership/count/cursor changed'; END IF;
  FOR item IN SELECT value FROM jsonb_array_elements(result->'items') LOOP
    expected_id := (item#>>'{workout,id}')::UUID;
    IF expected_id=c.legacy_v1 THEN
      IF jsonb_array_length(item->'sets') <> 2 OR item#>'{workout,measurement_version}' IS DISTINCT FROM 'null'::JSONB THEN
        RAISE EXCEPTION 'T11: legacy raw sync lost unfinished rows or relabelled history'; END IF;
    ELSIF expected_id=c.legacy_v2 THEN
      IF (item#>>'{workout,total_distance_meters}')::INTEGER IS DISTINCT FROM 500
        OR (item#>>'{exercises,0,total_distance_meters}')::INTEGER IS DISTINCT FROM 500
        OR (item#>>'{sets,0,distance_meters}')::INTEGER IS DISTINCT FROM 500 THEN
        RAISE EXCEPTION 'T11: legacy integer sync wire contract changed'; END IF;
    ELSIF expected_id IN(c.session_id,c.upload_id) THEN
      IF item#>'{workout,measurement_version}' IS DISTINCT FROM '3'::JSONB
        OR item#>'{exercises,1,input_defaults}' IS DISTINCT FROM c.initial#>'{snapshot,exercises,1,input_defaults}' THEN
        RAISE EXCEPTION 'T11: sync lost versioned snapshot'; END IF;
      SELECT value INTO STRICT s FROM jsonb_array_elements(item->'sets') WHERE value->>'exercise_id'=c.cardio_id;
      IF s->'weight' IS DISTINCT FROM 'null'::JSONB OR s->'reps' IS DISTINCT FROM 'null'::JSONB
        OR s->'speed_kph' IS DISTINCT FROM '12.34'::JSONB OR s->'incline_percent' IS DISTINCT FROM '-2.5'::JSONB
        OR s->>'target_row_id' IS DISTINCT FROM 'cardio-row'
        OR s->'distance_meters' IS DISTINCT FROM (CASE WHEN expected_id=c.upload_id THEN '1234.56'::JSONB ELSE 'null'::JSONB END) THEN
        RAISE EXCEPTION 'T11: sync lost nullable/decimal metrics or result association'; END IF;
    ELSE RAISE EXCEPTION 'T11: sync exposed an unexpected workout';
    END IF;
    seen := seen+1;
  END LOOP;
  IF seen <> 4 THEN RAISE EXCEPTION 'T11: missing sync fixture'; END IF;
  IF public.persist_local_workout_aggregate(c.old_workout,c.old_exercises,c.old_sets) IS DISTINCT FROM c.old_result
    OR public.finish_active_session(c.old_request,c.old_session,1) IS DISTINCT FROM c.old_operation_row->'result'
    OR public.save_template_revision(c.scalar_request,c.routine_id,NULL,'T11 rollback routine',c.scalar_payload,'[]',FALSE,c.owner_id) IS DISTINCT FROM c.scalar_receipt THEN
    RAISE EXCEPTION 'T11: an old upload/session/scalar receipt was re-executed or changed';
  END IF;
  -- A random non-owner must not observe this fixture through the public readers.
  PERFORM set_config('request.jwt.claim.sub',gen_random_uuid()::TEXT,TRUE);
  PERFORM set_config('request.jwt.claims',jsonb_build_object('sub',current_setting('request.jwt.claim.sub'),'role','authenticated')::TEXT,TRUE);
  BEGIN
    PERFORM public.get_workout_detail(c.session_id);
    RAISE EXCEPTION 'T11: cross-owner workout detail was exposed';
  EXCEPTION WHEN SQLSTATE 'PT404' THEN NULL;
  END;
  IF jsonb_array_length(public.get_workout_sync_page(NULL,25)->'items') <> 0 THEN
    RAISE EXCEPTION 'T11: cross-owner sync was exposed';
  END IF;
  result := public.get_progress_summary((now() AT TIME ZONE 'UTC')::DATE-1,(now() AT TIME ZONE 'UTC')::DATE+1,'UTC');
  IF (result->>'workout_count')::INTEGER IS DISTINCT FROM 0 THEN
    RAISE EXCEPTION 'T11: cross-owner progress was exposed';
  END IF;
END;
$upload_and_read$;
RESET ROLE;

DO $immutable_rows$
DECLARE c architectural_smoke_state%ROWTYPE; after_rows JSONB;
BEGIN
  SELECT * INTO STRICT c FROM architectural_smoke_state;
  SELECT jsonb_build_object('workouts',(SELECT jsonb_agg(to_jsonb(w) ORDER BY id) FROM public.workout_logs w WHERE id IN(c.legacy_v1,c.legacy_v2)),
    'exercises',(SELECT jsonb_agg(to_jsonb(e) ORDER BY id) FROM public.exercise_logs e WHERE workout_log_id IN(c.legacy_v1,c.legacy_v2)),
    'sets',(SELECT jsonb_agg(to_jsonb(s) ORDER BY id) FROM public.workout_sets s WHERE workout_log_id IN(c.legacy_v1,c.legacy_v2))) INTO after_rows;
  IF after_rows IS DISTINCT FROM c.legacy_rows
    OR (SELECT to_jsonb(r) FROM imfit_private.local_workout_receipts r WHERE workout_id=c.legacy_v1) IS DISTINCT FROM c.old_receipt_row
    OR (SELECT to_jsonb(r) FROM imfit_private.operation_receipts r WHERE user_id=c.owner_id AND request_id=c.old_request) IS DISTINCT FROM c.old_operation_row
    OR jsonb_build_object('template',(SELECT to_jsonb(r) FROM public.template_write_receipts r WHERE user_id=c.owner_id AND request_id=c.scalar_request),
      'operation',(SELECT to_jsonb(r) FROM imfit_private.operation_receipts r WHERE user_id=c.owner_id AND request_id=c.scalar_request)) IS DISTINCT FROM c.scalar_rows THEN
    RAISE EXCEPTION 'T11: legacy rows or immutable receipt payload/result/identity/retention metadata mutated';
  END IF;
  IF (SELECT count(*) FROM public.workout_logs WHERE user_id=c.owner_id) <> 4
    OR (SELECT count(*) FROM imfit_private.local_workout_receipts WHERE user_id=c.owner_id) <> 2 THEN
    RAISE EXCEPTION 'T11: a rejected replay created unexpected history or receipts';
  END IF;
  UPDATE architectural_smoke_state SET uploaded_rows=(SELECT jsonb_build_object('workout',to_jsonb(w),
    'exercises',(SELECT jsonb_agg(to_jsonb(e) ORDER BY id) FROM public.exercise_logs e WHERE workout_log_id=c.upload_id),
    'sets',(SELECT jsonb_agg(to_jsonb(s) ORDER BY id) FROM public.workout_sets s WHERE workout_log_id=c.upload_id))
    FROM public.workout_logs w WHERE id=c.upload_id),
    upload_receipt_row=(SELECT to_jsonb(r) FROM imfit_private.local_workout_receipts r WHERE workout_id=c.upload_id);
END;
$immutable_rows$;
-- Replay again after snapshots above and check ALL persisted columns, not just the
-- public response, so a retry cannot silently rewrite data or receipt timestamps.
SET LOCAL ROLE authenticated;
DO $final_replay$
DECLARE c architectural_smoke_state%ROWTYPE;
BEGIN
  SELECT * INTO STRICT c FROM architectural_smoke_state;
  PERFORM set_config('request.jwt.claim.sub',c.owner_id::TEXT,TRUE);
  PERFORM set_config('request.jwt.claims',jsonb_build_object('sub',c.owner_id,'role','authenticated')::TEXT,TRUE);
  IF public.persist_local_workout_aggregate(c.upload_workout,c.upload_exercises,c.upload_sets) IS DISTINCT FROM c.upload_receipt THEN
    RAISE EXCEPTION 'T11: final upload replay changed'; END IF;
END;
$final_replay$;
RESET ROLE;
DO $final_assert$
DECLARE c architectural_smoke_state%ROWTYPE; after_rows JSONB;
BEGIN
  SELECT * INTO STRICT c FROM architectural_smoke_state;
  SELECT jsonb_build_object('workout',to_jsonb(w),
    'exercises',(SELECT jsonb_agg(to_jsonb(e) ORDER BY id) FROM public.exercise_logs e WHERE workout_log_id=c.upload_id),
    'sets',(SELECT jsonb_agg(to_jsonb(s) ORDER BY id) FROM public.workout_sets s WHERE workout_log_id=c.upload_id))
    INTO after_rows FROM public.workout_logs w WHERE id=c.upload_id;
  IF after_rows IS DISTINCT FROM c.uploaded_rows
    OR (SELECT to_jsonb(r) FROM imfit_private.local_workout_receipts r WHERE workout_id=c.upload_id) IS DISTINCT FROM c.upload_receipt_row THEN
    RAISE EXCEPTION 'T11: v3 upload replay mutated persisted rows or receipt metadata'; END IF;
END;
$final_assert$;
ROLLBACK;
SELECT 'PASS: T11 authenticated v1/v2/v3 readers, targets/session lifecycle, frozen metadata/rest, nullable decimals, separated volume, upload/replay/conflict and old receipt preservation; all fixture writes rolled back' AS architectural_reader_smoke;

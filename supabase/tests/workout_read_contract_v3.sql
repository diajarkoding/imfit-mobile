-- T10: targets win over history; progress counts actual completed versioned results.
BEGIN;
CREATE TEMP TABLE read_v3_case(owner_id UUID,workout_id UUID,defaults JSONB);
GRANT SELECT ON read_v3_case TO authenticated;
DO $fixture$
DECLARE owner_id UUID:=gen_random_uuid(); legacy UUID:=gen_random_uuid(); legacy_log UUID:=gen_random_uuid();
  wid UUID:=gen_random_uuid(); strength JSONB; cardio JSONB; ex JSONB; results JSONB; payload JSONB; draft JSONB;
BEGIN
  INSERT INTO auth.users(id,email,raw_user_meta_data) VALUES(owner_id,'read-v3@example.invalid','{"name":"Read v3"}');
  INSERT INTO public.workout_logs(id,user_id,template_name,date,start_time,end_time,total_volume,total_sets,total_reps)
    VALUES(legacy,owner_id,'Legacy measurements','2026-11-01','2026-11-01 10:00:00+00','2026-11-01 10:01:00+00',9999,999,999);
  INSERT INTO public.exercise_logs(id,workout_log_id,exercise_id,exercise_name,muscle_category,tracking_type,total_volume,total_sets,total_reps)
    VALUES(legacy_log,legacy,'ex_chest_1','Historical name','Historical category','WEIGHT_REPS',9999,999,999);
  INSERT INTO public.workout_sets(id,exercise_log_id,workout_log_id,exercise_id,set_number,weight,reps,is_completed)
    VALUES(gen_random_uuid(),legacy_log,legacy,'ex_chest_1',1,40,10,TRUE),(gen_random_uuid(),legacy_log,legacy,'ex_chest_1',2,999,999,FALSE);
  strength:=imfit_private.session_exercise_v3(gen_random_uuid(),'ex_chest_1',0,
    '[{"row_id":"s1","set_number":1,"weight":20,"reps":10,"duration_seconds":null,"rest_seconds":0},{"row_id":"s2","set_number":2,"weight":30,"reps":5,"duration_seconds":null,"rest_seconds":90}]');
  cardio:=imfit_private.session_exercise_v3(gen_random_uuid(),'ex_cardio_1',1,
    '[{"row_id":"c1","set_number":1,"weight":null,"reps":null,"duration_seconds":60,"rest_seconds":0}]');
  SELECT jsonb_agg(jsonb_build_object('id',e->'id','workout_log_id',wid,'exercise_id',e->'exercise_id','exercise_name',e->'name',
    'muscle_category',e->'muscle_category','order_index',e->'order_index','measurement_version',3,'tracking_type',e->'tracking_type',
    'modality',e->'modality','input_defaults',e->'input_defaults') ORDER BY (e->>'order_index')::INTEGER)
    INTO ex FROM jsonb_array_elements(jsonb_build_array(strength,cardio)) e;
  SELECT jsonb_agg((e->'sets'->0)||jsonb_build_object('exercise_log_id',e->'id','workout_log_id',wid,'exercise_id',e->'exercise_id','is_completed',TRUE)
    || CASE WHEN e->>'exercise_id'='ex_chest_1' THEN '{"weight":10,"reps":2}'::JSONB
      ELSE '{"distance_meters":1234.56,"calories_kcal":0,"speed_kph":12.34,"incline_percent":-2.5}'::JSONB END)
    INTO results FROM jsonb_array_elements(jsonb_build_array(strength,cardio)) e;
  payload:=jsonb_build_object('id',wid,'user_id',owner_id,'template_id',NULL,'template_name','Versioned measurements','measurement_version',3,
    'date','2026-11-01','start_time','2026-11-01 11:00:00+00','end_time','2026-11-01 11:02:00+00');
  PERFORM imfit_private.insert_workout_v3(owner_id,payload,ex,results);
  draft:=strength->'sets'->1;
  INSERT INTO public.workout_sets(id,exercise_log_id,workout_log_id,exercise_id,set_number,measurement_version,target_row_id,weight,reps,is_completed)
    VALUES((draft->>'id')::UUID,(strength->>'id')::UUID,wid,'ex_chest_1',2,3,'s2',1000,1000,FALSE);
  INSERT INTO read_v3_case VALUES(owner_id,wid,strength->'input_defaults');
  -- A second day has duration without a recorded distance, not an invented zero.
  wid:=gen_random_uuid();
  cardio:=imfit_private.session_exercise_v3(gen_random_uuid(),'ex_cardio_10',0,
    '[{"row_id":"j1","set_number":1,"weight":null,"reps":null,"duration_seconds":30,"rest_seconds":60}]');
  ex:=jsonb_build_array(jsonb_build_object('id',cardio->'id','workout_log_id',wid,'exercise_id',cardio->'exercise_id','exercise_name',cardio->'name',
    'muscle_category',cardio->'muscle_category','order_index',0,'measurement_version',3,'tracking_type',cardio->'tracking_type',
    'modality',cardio->'modality','input_defaults',cardio->'input_defaults'));
  results:=jsonb_build_array((cardio->'sets'->0)||jsonb_build_object('workout_log_id',wid,'exercise_log_id',cardio->'id','exercise_id',cardio->'exercise_id','is_completed',TRUE));
  payload:=payload||jsonb_build_object('id',wid,'date','2026-11-02','start_time','2026-11-02 11:00:00+00','end_time','2026-11-02 11:01:00+00');
  PERFORM imfit_private.insert_workout_v3(owner_id,payload,ex,results);
END;
$fixture$;
SET LOCAL ROLE authenticated;
DO $read$
DECLARE c read_v3_case%ROWTYPE; result JSONB; detail JSONB;
BEGIN
  SELECT * INTO c FROM read_v3_case;
  PERFORM set_config('request.jwt.claim.sub',c.owner_id::TEXT,true);
  result:=public.get_exercise_prefill('ex_chest_1',c.defaults);
  IF result->>'source' IS DISTINCT FROM 'TARGETS' OR result->'input_defaults' IS DISTINCT FROM c.defaults
     OR result#>'{sets,0,weight}' IS DISTINCT FROM '20'::JSONB OR result#>'{sets,1,weight}' IS DISTINCT FROM '30'::JSONB
     OR result#>'{sets,0,reps}' IS DISTINCT FROM '10'::JSONB OR result#>'{sets,1,reps}' IS DISTINCT FROM '5'::JSONB
     OR result#>>'{sets,0,target_row_id}' IS DISTINCT FROM 's1' THEN
    RAISE EXCEPTION 'Target prefill was flattened or overwritten by historical performance';
  END IF;
  BEGIN
    PERFORM public.get_exercise_prefill('ex_chest_1',jsonb_set(c.defaults,'{schema_version}','4'));
    RAISE EXCEPTION 'Unsupported explicit snapshot version fell back to legacy';
  EXCEPTION WHEN SQLSTATE 'PT400' THEN NULL;
  END;
  detail:=public.get_workout_detail(c.workout_id);
  IF (detail->>'recorded_load_volume')::NUMERIC IS DISTINCT FROM 20::NUMERIC
     OR detail#>'{exercise_logs,0,input_defaults}' IS DISTINCT FROM c.defaults
     OR jsonb_typeof(detail#>'{exercise_logs,0,sets,0,weight}') IS DISTINCT FROM 'number'
     OR jsonb_array_length(detail#>'{exercise_logs,0,sets}')<>2
     OR detail#>'{exercise_logs,0,sets,1,is_completed}' IS DISTINCT FROM 'false'::JSONB
     OR detail#>'{exercise_logs,1,sets,0,weight}' IS DISTINCT FROM 'null'::JSONB
     OR detail#>'{exercise_logs,1,sets,0,distance_meters}' IS DISTINCT FROM '1234.56'::JSONB
     OR detail#>'{exercise_logs,1,sets,0,incline_percent}' IS DISTINCT FROM '-2.5'::JSONB
     OR detail#>>'{exercise_logs,1,sets,0,target_row_id}' IS DISTINCT FROM 'c1' THEN
    RAISE EXCEPTION 'Detail discarded result flags, nullable metrics or complete target snapshot';
  END IF;
  result:=public.get_progress_summary('2026-11-01','2026-11-02','UTC');
  IF (result->>'workout_count')::INTEGER IS DISTINCT FROM 2 OR (result->>'total_sets')::INTEGER IS DISTINCT FROM 3
     OR (result->>'total_reps')::INTEGER IS DISTINCT FROM 12 OR (result->>'total_volume')::NUMERIC IS DISTINCT FROM 400::NUMERIC
     OR (result->>'recorded_load_volume')::NUMERIC IS DISTINCT FROM 20::NUMERIC
     OR (result->>'cardio_duration_seconds')::INTEGER IS DISTINCT FROM 60
     OR (result->>'cardio_distance_meters')::NUMERIC IS DISTINCT FROM 1234.56::NUMERIC
     OR (result->>'workout_duration_seconds')::INTEGER IS DISTINCT FROM 180 THEN
    RAISE EXCEPTION 'Progress mixed versions, counted drafts, trusted cached totals or multiplied workout duration: %',result;
  END IF;
  result:=public.get_progress_summary('2026-11-02','2026-11-03','UTC');
  IF result->'cardio_distance_meters' IS DISTINCT FROM 'null'::JSONB
     OR (result->>'cardio_duration_seconds')::INTEGER IS DISTINCT FROM 30 THEN
    RAISE EXCEPTION 'Unrecorded optional distance was manufactured or required';
  END IF;
  PERFORM set_config('request.jwt.claim.sub',gen_random_uuid()::TEXT,true);
  BEGIN
    PERFORM public.get_workout_detail(c.workout_id);
    RAISE EXCEPTION 'Cross-owner detail was visible';
  EXCEPTION WHEN SQLSTATE 'PT404' THEN NULL;
  END;
  result:=public.get_progress_summary('2026-11-01','2026-11-03','UTC');
  IF (result->>'workout_count')::INTEGER<>0 THEN RAISE EXCEPTION 'Cross-owner progress was visible'; END IF;
  RAISE NOTICE 'PASS: per-row target prefill, full versioned detail, completed-only separated volumes and optional DURATION distance';
END;
$read$;
RESET ROLE;
DO $legacy_wire$
DECLARE owner_id UUID:=gen_random_uuid(); wid UUID:=gen_random_uuid(); log_id UUID:=gen_random_uuid(); page JSONB; detail JSONB; prefill JSONB;
BEGIN
  INSERT INTO auth.users(id,email,raw_user_meta_data) VALUES(owner_id,'legacy-wire@example.invalid','{"name":"Legacy wire"}');
  INSERT INTO public.workout_logs(id,user_id,template_name,date,start_time,end_time,total_volume,total_sets,total_reps,total_distance_meters)
    VALUES(wid,owner_id,'Legacy integer distance','2026-11-01','2026-11-01 12:00:00+00','2026-11-01 12:01:00+00',0,1,0,500);
  INSERT INTO public.exercise_logs(id,workout_log_id,exercise_id,exercise_name,muscle_category,tracking_type,modality,total_volume,total_sets,total_reps,total_distance_meters)
    VALUES(log_id,wid,'ex_cardio_1','Historical treadmill','Cardio','DISTANCE_DURATION','CARDIO',0,1,0,500);
  INSERT INTO public.workout_sets(id,exercise_log_id,workout_log_id,exercise_id,set_number,weight,reps,duration_seconds,distance_meters,is_completed)
    VALUES(gen_random_uuid(),log_id,wid,'ex_cardio_1',1,0,0,60,500,TRUE),
      (gen_random_uuid(),log_id,wid,'ex_cardio_1',2,0,0,60,100,FALSE);
  PERFORM set_config('request.jwt.claim.sub',owner_id::TEXT,true);
  page:=public.get_workout_sync_page(NULL,25);
  detail:=public.get_workout_detail(wid);
  prefill:=public.get_exercise_prefill('ex_cardio_1','{"sets":1,"reps":10,"tracking_type":"DISTANCE_DURATION"}');
  IF jsonb_array_length(detail#>'{exercise_logs,0,sets}')<>1
     OR jsonb_array_length(page#>'{items,0,sets}')<>2 THEN
    RAISE EXCEPTION 'Legacy detail completion filter or lossless raw sync contract changed';
  END IF;
  -- Actual legacy Int consumers must continue parsing these JSON values. Numeric
  -- equality alone would miss the breaking "500.00" wire representation.
  IF (page#>>'{items,0,workout,total_distance_meters}')::INTEGER<>500
     OR (page#>>'{items,0,exercises,0,total_distance_meters}')::INTEGER<>500
     OR (page#>>'{items,0,sets,0,distance_meters}')::INTEGER<>500
     OR (detail->>'total_distance_meters')::INTEGER<>500
     OR (detail#>>'{exercise_logs,0,sets,0,distance_meters}')::INTEGER<>500
     OR (prefill#>>'{sets,0,distance_meters}')::INTEGER<>500 THEN
    RAISE EXCEPTION 'Physical decimal expansion broke a legacy integer reader';
  END IF;
END;
$legacy_wire$;
ROLLBACK;

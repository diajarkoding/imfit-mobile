-- T09 authenticated aggregate import, frozen metadata and atomic idempotency.
BEGIN;
CREATE TEMP TABLE aggregate_v3_case(owner_id UUID,workout JSONB,exercises JSONB,sets JSONB,receipt JSONB,stored JSONB);
GRANT SELECT,UPDATE ON aggregate_v3_case TO authenticated;
DO $fixture$
DECLARE owner_id UUID:=gen_random_uuid(); wid UUID:=gen_random_uuid(); strength JSONB; cardio JSONB; exercises JSONB; sets JSONB;
BEGIN
  INSERT INTO auth.users(id,email,raw_user_meta_data) VALUES(owner_id,'aggregate-v3@example.invalid','{"name":"Aggregate v3"}');
  strength:=imfit_private.session_exercise_v3(gen_random_uuid(),'ex_chest_1',0,
    '[{"row_id":"strength-row","set_number":1,"weight":37.5,"reps":8,"duration_seconds":null,"rest_seconds":0}]');
  cardio:=imfit_private.session_exercise_v3(gen_random_uuid(),'ex_cardio_1',1,
    '[{"row_id":"cardio-row","set_number":1,"weight":null,"reps":null,"duration_seconds":90,"rest_seconds":60}]');
  SELECT jsonb_agg(jsonb_build_object('id',e->'id','workout_log_id',wid,'exercise_id',e->'exercise_id',
    'exercise_name',e->'name','muscle_category',e->'muscle_category','order_index',e->'order_index',
    'measurement_version',3,'tracking_type',e->'tracking_type','modality',e->'modality','input_defaults',e->'input_defaults'))
    INTO exercises FROM jsonb_array_elements(jsonb_build_array(strength,cardio)) e;
  SELECT jsonb_agg(s||jsonb_build_object('workout_log_id',wid,'exercise_log_id',e->'id','exercise_id',e->'exercise_id','is_completed',TRUE)
    || CASE WHEN e->>'exercise_id'='ex_cardio_1' THEN '{"distance_meters":1234.56,"speed_kph":12.34,"incline_percent":-2.5,"calories_kcal":0}'::JSONB ELSE '{}'::JSONB END)
    INTO sets FROM jsonb_array_elements(jsonb_build_array(strength,cardio)) e CROSS JOIN LATERAL jsonb_array_elements(e->'sets') s;
  INSERT INTO aggregate_v3_case(owner_id,workout,exercises,sets) VALUES(owner_id,
    jsonb_build_object('id',wid,'user_id',owner_id,'template_id',NULL,'template_name','Offline v3 aggregate','date',((now()-INTERVAL '2 minutes') AT TIME ZONE 'UTC')::DATE,
      'start_time',now()-INTERVAL '2 minutes','end_time',now(),'measurement_version',3,'total_volume',0,'total_reps',8,'total_sets',2,'recorded_load_volume',300),exercises,sets);
END;
$fixture$;
SET LOCAL ROLE authenticated;
DO $upload$
DECLARE c aggregate_v3_case%ROWTYPE; result JSONB; invalid_sets JSONB; invalid_exercises JSONB; bad_workout JSONB; bad JSONB;
BEGIN
  SELECT * INTO c FROM aggregate_v3_case;
  PERFORM set_config('request.jwt.claim.sub',c.owner_id::TEXT,true);
  result:=public.persist_local_workout_aggregate(c.workout,c.exercises,c.sets);
  IF result->>'workout_id' IS DISTINCT FROM c.workout->>'id'
     OR result->'recorded_load_volume' IS DISTINCT FROM '300'::JSONB
     OR public.persist_local_workout_aggregate(c.workout,c.exercises,c.sets) IS DISTINCT FROM result THEN
    RAISE EXCEPTION 'Aggregate identity, versioned load or immutable replay changed';
  END IF;
  UPDATE aggregate_v3_case SET receipt=result;
  BEGIN
    PERFORM public.persist_local_workout_aggregate(jsonb_set(c.workout,'{template_name}','"Different payload"'),c.exercises,c.sets);
    RAISE EXCEPTION 'Different aggregate reused a committed identity';
  EXCEPTION WHEN SQLSTATE 'PT409' THEN
    IF SQLERRM<>'IDEMPOTENCY_MISMATCH' THEN RAISE; END IF;
  END;
  -- Rejected envelopes each use a fresh parent identity. Every case must roll back.
  FOR bad IN SELECT value FROM jsonb_array_elements('[
    {"target_row_id":"missing-target"},
    {"measurement_version":4},
    {"speed_kph":12.345},
    {"speed_kph":"NaN"},
    {"lap_reps":1},
    {"weight":0},
    {"duration_seconds":0},
    {"distance_meters":-1}
  ]') LOOP
    bad_workout:=jsonb_set(c.workout,'{id}',to_jsonb(gen_random_uuid()));
    SELECT jsonb_agg(e||jsonb_build_object('workout_log_id',bad_workout->'id')) INTO invalid_exercises FROM jsonb_array_elements(c.exercises) e;
    SELECT jsonb_agg(s||jsonb_build_object('workout_log_id',bad_workout->'id')||CASE WHEN s->>'exercise_id'='ex_cardio_1' THEN bad ELSE '{}'::JSONB END)
      INTO invalid_sets FROM jsonb_array_elements(c.sets) s;
    BEGIN
      PERFORM public.persist_local_workout_aggregate(bad_workout,invalid_exercises,invalid_sets);
      RAISE EXCEPTION 'Malformed v3 result accepted: %',bad;
    EXCEPTION WHEN SQLSTATE 'PT400' THEN NULL;
    END;
  END LOOP;
END;
$upload$;
RESET ROLE;
DO $stored$
DECLARE c aggregate_v3_case%ROWTYPE; wid UUID;
BEGIN
  SELECT * INTO c FROM aggregate_v3_case; wid:=(c.workout->>'id')::UUID;
  IF NOT EXISTS(SELECT 1 FROM public.workout_logs WHERE id=wid AND measurement_version=3 AND total_volume=0
      AND recorded_load_volume=300 AND total_distance_meters=1234.56 AND total_reps=8 AND total_sets=2)
     OR NOT EXISTS(SELECT 1 FROM public.exercise_logs WHERE workout_log_id=wid AND exercise_id='ex_cardio_1'
       AND input_defaults=c.exercises#>'{1,input_defaults}' AND measurement_version=3)
     OR NOT EXISTS(SELECT 1 FROM public.workout_sets WHERE workout_log_id=wid AND exercise_id='ex_cardio_1'
       AND weight IS NULL AND reps IS NULL AND distance_meters=1234.56 AND calories_kcal=0 AND incline_percent=-2.5)
     OR (SELECT count(*) FROM public.workout_logs WHERE user_id=c.owner_id)<>1
     OR (SELECT count(*) FROM imfit_private.local_workout_receipts WHERE user_id=c.owner_id)<>1 THEN
    RAISE EXCEPTION 'Upload lost complete snapshot/decimal/null or partially committed rejected payloads';
  END IF;
  UPDATE aggregate_v3_case SET stored=(SELECT jsonb_build_object('workout',to_jsonb(w),'exercises',
    (SELECT jsonb_agg(to_jsonb(e) ORDER BY id) FROM public.exercise_logs e WHERE workout_log_id=wid),'sets',
    (SELECT jsonb_agg(to_jsonb(s) ORDER BY id) FROM public.workout_sets s WHERE workout_log_id=wid)) FROM public.workout_logs w WHERE id=wid);
END;
$stored$;
UPDATE public.exercises SET tracking_profile='duration_only',supported_metrics='{}' WHERE id='ex_cardio_1';
SET LOCAL ROLE authenticated;
DO $replay_after_catalog$
DECLARE c aggregate_v3_case%ROWTYPE;
BEGIN
  SELECT * INTO c FROM aggregate_v3_case;
  PERFORM set_config('request.jwt.claim.sub',c.owner_id::TEXT,true);
  IF public.persist_local_workout_aggregate(c.workout,c.exercises,c.sets) IS DISTINCT FROM c.receipt THEN
    RAISE EXCEPTION 'Catalog drift invalidated immutable aggregate receipt';
  END IF;
END;
$replay_after_catalog$;
RESET ROLE;
DO $unchanged$
DECLARE c aggregate_v3_case%ROWTYPE; after_state JSONB; wid UUID;
BEGIN
  SELECT * INTO c FROM aggregate_v3_case; wid:=(c.workout->>'id')::UUID;
  SELECT jsonb_build_object('workout',to_jsonb(w),'exercises',
    (SELECT jsonb_agg(to_jsonb(e) ORDER BY id) FROM public.exercise_logs e WHERE workout_log_id=wid),'sets',
    (SELECT jsonb_agg(to_jsonb(s) ORDER BY id) FROM public.workout_sets s WHERE workout_log_id=wid))
    INTO after_state FROM public.workout_logs w WHERE id=wid;
  IF after_state IS DISTINCT FROM c.stored THEN RAISE EXCEPTION 'Retry changed committed aggregate data'; END IF;
  RAISE NOTICE 'PASS: v3 aggregate target snapshot, optional decimal metrics, nullable fields, atomic rejection and immutable receipt';
END;
$unchanged$;
SET LOCAL ROLE authenticated;
DO $distance_boundary$
DECLARE c aggregate_v3_case%ROWTYPE; wid UUID:=gen_random_uuid(); eid UUID:=gen_random_uuid();
  snap JSONB; ex JSONB; measurements JSONB; original_result JSONB; workout JSONB;
BEGIN
  SELECT * INTO c FROM aggregate_v3_case;
  PERFORM set_config('request.jwt.claim.sub',c.owner_id::TEXT,true);
  -- This is a NEW upload after catalog drift, not merely receipt replay. The
  -- frozen equipment capability remains authoritative over today's duration-only.
  SELECT value->'input_defaults' INTO snap FROM jsonb_array_elements(c.exercises) WHERE value->>'exercise_id'='ex_cardio_1';
  SELECT value INTO original_result FROM jsonb_array_elements(c.sets) WHERE value->>'exercise_id'='ex_cardio_1';
  snap:=jsonb_set(snap,'{set_targets}','[
    {"row_id":"far-1","set_number":1,"weight":null,"reps":null,"duration_seconds":1,"rest_seconds":0},
    {"row_id":"far-2","set_number":2,"weight":null,"reps":null,"duration_seconds":1,"rest_seconds":0}
  ]');
  ex:=jsonb_build_array(jsonb_build_object('id',eid,'workout_log_id',wid,'exercise_id','ex_cardio_1',
    'exercise_name','Frozen equipment','muscle_category','Cardio','order_index',0,'measurement_version',3,
    'tracking_type','DURATION','modality','CARDIO','input_defaults',snap));
  SELECT jsonb_agg(original_result||jsonb_build_object('id',gen_random_uuid(),'exercise_log_id',eid,'workout_log_id',wid,
    'set_number',i,'target_row_id','far-'||i,'duration_seconds',1,'distance_meters',9999999999.99) ORDER BY i)
    INTO measurements FROM generate_series(1,2) i;
  workout:=c.workout||jsonb_build_object('id',wid,'recorded_load_volume',0,'total_reps',0,
    'total_distance_meters',19999999999.98,'total_duration_seconds',2);
  PERFORM public.persist_local_workout_aggregate(workout,ex,measurements);
  PERFORM set_config('imfit.aggregate.boundary',wid::TEXT,true);
END;
$distance_boundary$;
RESET ROLE;
DO $distance_sum$
BEGIN
  IF (SELECT total_distance_meters FROM public.workout_logs WHERE id=current_setting('imfit.aggregate.boundary')::UUID)
      IS DISTINCT FROM 19999999999.98::NUMERIC THEN
    RAISE EXCEPTION 'Two valid per-set distances overflowed or lost aggregate precision';
  END IF;
END;
$distance_sum$;
ROLLBACK;

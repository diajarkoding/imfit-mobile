-- T37: actual rest outcome + session durations round-trip, validation, legacy guard and immutability.
BEGIN;
CREATE TEMP TABLE rest_case(owner_id UUID,workout JSONB,exercises JSONB,sets JSONB,receipt JSONB);
GRANT SELECT,UPDATE ON rest_case TO authenticated;
DO $fixture$
DECLARE owner_id UUID:=gen_random_uuid(); wid UUID:=gen_random_uuid(); ex JSONB; exercises JSONB; sets JSONB;
BEGIN
  INSERT INTO auth.users(id,email,raw_user_meta_data) VALUES(owner_id,'rest-outcome@example.invalid','{"name":"Rest outcome"}');
  ex:=imfit_private.session_exercise_v3(gen_random_uuid(),'ex_cardio_1',0,
    '[{"row_id":"r1","set_number":1,"weight":null,"reps":null,"duration_seconds":90,"rest_seconds":90},
      {"row_id":"r2","set_number":2,"weight":null,"reps":null,"duration_seconds":60,"rest_seconds":60}]');
  exercises:=jsonb_build_array(jsonb_build_object('id',ex->'id','workout_log_id',wid,'exercise_id',ex->'exercise_id',
    'exercise_name',ex->'name','muscle_category',ex->'muscle_category','order_index',0,'measurement_version',3,
    'tracking_type',ex->'tracking_type','modality',ex->'modality','input_defaults',ex->'input_defaults'));
  SELECT jsonb_agg(s||jsonb_build_object('workout_log_id',wid,'exercise_log_id',ex->'id','exercise_id',ex->'exercise_id','is_completed',TRUE,
      'duration_seconds',80)
    || CASE WHEN (s->>'set_number')='1' THEN '{"rest_elapsed_ms":35000,"rest_end_reason":"SKIPPED"}'::JSONB
            ELSE '{"rest_elapsed_ms":60000,"rest_end_reason":"EXPIRED"}'::JSONB END ORDER BY (s->>'set_number')::INT)
    INTO sets FROM jsonb_array_elements(ex->'sets') s;
  INSERT INTO rest_case VALUES(owner_id,
    jsonb_build_object('id',wid,'user_id',owner_id,'template_id',NULL,'template_name','Rest outcome','date',((now()-INTERVAL '5 minutes') AT TIME ZONE 'UTC')::DATE,
      'start_time',now()-INTERVAL '5 minutes','end_time',now(),'measurement_version',3,'total_volume',0,'total_reps',0,'total_sets',2,
      'paused_elapsed_ms',30000,'active_elapsed_ms',115000),exercises,sets,NULL);
END;
$fixture$;
SET LOCAL ROLE authenticated;
DO $upload$
DECLARE c rest_case%ROWTYPE; result JSONB; bad JSONB; bw JSONB; be JSONB; bs JSONB;
BEGIN
  SELECT * INTO c FROM rest_case;
  PERFORM set_config('request.jwt.claim.sub',c.owner_id::TEXT,true);
  result:=public.persist_local_workout_aggregate(c.workout,c.exercises,c.sets);
  IF result->>'workout_id' IS DISTINCT FROM c.workout->>'id'
     OR public.persist_local_workout_aggregate(c.workout,c.exercises,c.sets) IS DISTINCT FROM result THEN
    RAISE EXCEPTION 'Rest outcome aggregate identity or immutable replay changed';
  END IF;
  UPDATE rest_case SET receipt=result;
  -- Each rejected shape uses a fresh workout identity; all must be PT400 and leave nothing behind.
  FOR bad IN SELECT value FROM jsonb_array_elements('[
    {"set":{"rest_elapsed_ms":null,"rest_end_reason":"SKIPPED"}},
    {"set":{"rest_elapsed_ms":1000,"rest_end_reason":null}},
    {"set":{"rest_elapsed_ms":1000,"rest_end_reason":"CANCELLED"}},
    {"set":{"rest_elapsed_ms":1000,"rest_end_reason":"skipped"}},
    {"set":{"rest_elapsed_ms":-1,"rest_end_reason":"SKIPPED"}},
    {"set":{"rest_elapsed_ms":1.5,"rest_end_reason":"SKIPPED"}},
    {"set":{"rest_elapsed_ms":"1000","rest_end_reason":"SKIPPED"}},
    {"set":{"rest_elapsed_ms":86400001,"rest_end_reason":"EXPIRED"}},
    {"set":{"rest_outcome":{"elapsed_ms":1,"end_reason":"SKIPPED"}}},
    {"workout":{"active_elapsed_ms":null}},
    {"workout":{"paused_elapsed_ms":null}},
    {"workout":{"paused_elapsed_ms":-1}},
    {"workout":{"paused_elapsed_ms":200000,"active_elapsed_ms":200000}},
    {"workout":{"paused_elapsed_ms":30000,"active_elapsed_ms":"115000"}}
  ]') LOOP
    bw:=c.workout||jsonb_build_object('id',gen_random_uuid())||coalesce(bad->'workout','{}'::JSONB);
    SELECT jsonb_agg(e||jsonb_build_object('workout_log_id',bw->'id')) INTO be FROM jsonb_array_elements(c.exercises) e;
    SELECT jsonb_agg(s||jsonb_build_object('id',gen_random_uuid(),'workout_log_id',bw->'id')||coalesce(bad->'set','{}'::JSONB)) INTO bs FROM jsonb_array_elements(c.sets) s;
    BEGIN
      PERFORM public.persist_local_workout_aggregate(bw,be,bs);
      RAISE EXCEPTION 'Invalid rest outcome / session duration accepted: %',bad;
    EXCEPTION WHEN SQLSTATE 'PT400' THEN NULL;
    END;
  END LOOP;
  -- Legacy (unversioned) payloads may never carry the new keys, and are not silently stripped.
  FOREACH bad IN ARRAY ARRAY['{"workout":{"paused_elapsed_ms":1,"active_elapsed_ms":2}}','{"set":{"rest_elapsed_ms":1,"rest_end_reason":"EXPIRED"}}']::JSONB[] LOOP
    bw:=(c.workout-'measurement_version')||jsonb_build_object('id',gen_random_uuid())||coalesce(bad->'workout','{}'::JSONB);
    BEGIN
      PERFORM public.persist_local_workout_aggregate(bw,'[]'::JSONB,jsonb_build_array(jsonb_build_object('id',gen_random_uuid())||coalesce(bad->'set','{}'::JSONB)));
      RAISE EXCEPTION 'Legacy writer accepted v3-only rest/session fields';
    EXCEPTION WHEN SQLSTATE 'PT400' OR SQLSTATE '22023' THEN NULL;
    END;
  END LOOP;
END;
$upload$;
RESET ROLE;
DO $stored$
DECLARE c rest_case%ROWTYPE; wid UUID; detail JSONB; sets_out JSONB; page JSONB; snap_before JSONB;
BEGIN
  SELECT * INTO c FROM rest_case; wid:=(c.workout->>'id')::UUID;
  IF (SELECT count(*) FROM public.workout_logs WHERE user_id=c.owner_id)<>1
     OR NOT EXISTS(SELECT 1 FROM public.workout_logs WHERE id=wid AND paused_elapsed_ms=30000 AND active_elapsed_ms=115000)
     OR NOT EXISTS(SELECT 1 FROM public.workout_sets WHERE workout_log_id=wid AND target_row_id='r1' AND duration_seconds=80
        AND rest_elapsed_ms=35000 AND rest_end_reason='SKIPPED')
     OR NOT EXISTS(SELECT 1 FROM public.workout_sets WHERE workout_log_id=wid AND target_row_id='r2'
        AND rest_elapsed_ms=60000 AND rest_end_reason='EXPIRED') THEN
    RAISE EXCEPTION 'Rest outcome/session durations were not stored exactly (or rejected payloads left rows behind)';
  END IF;
  -- Targets are untouched: the snapshot still says 90/60 even though actual rest was 35/60.
  IF NOT EXISTS(SELECT 1 FROM public.exercise_logs WHERE workout_log_id=wid
       AND input_defaults#>>'{set_targets,0,rest_seconds}'='90' AND input_defaults#>>'{set_targets,1,rest_seconds}'='60') THEN
    RAISE EXCEPTION 'Target rest_seconds changed';
  END IF;
  PERFORM set_config('request.jwt.claim.sub',c.owner_id::TEXT,true);
  SET LOCAL ROLE authenticated;
  detail:=public.get_workout_detail(wid);
  page:=public.get_workout_sync_page(NULL,25);
  RESET ROLE;
  IF detail->>'active_elapsed_ms' IS DISTINCT FROM '115000' OR detail->>'paused_elapsed_ms' IS DISTINCT FROM '30000' THEN
    RAISE EXCEPTION 'Detail read-back lost session durations: %',detail;
  END IF;
  SELECT jsonb_agg(s ORDER BY (s->>'set_number')::INT) INTO sets_out FROM jsonb_array_elements(detail#>'{exercise_logs,0,sets}') s;
  IF sets_out#>>'{0,rest_elapsed_ms}' IS DISTINCT FROM '35000' OR sets_out#>>'{0,rest_end_reason}' IS DISTINCT FROM 'SKIPPED'
     OR sets_out#>>'{1,rest_elapsed_ms}' IS DISTINCT FROM '60000' OR sets_out#>>'{1,rest_end_reason}' IS DISTINCT FROM 'EXPIRED' THEN
    RAISE EXCEPTION 'Detail read-back lost rest outcomes: %',sets_out;
  END IF;
  IF page#>>'{items,0,workout,active_elapsed_ms}' IS DISTINCT FROM '115000'
     OR (SELECT count(*) FROM jsonb_array_elements(page#>'{items,0,sets}') s
         WHERE s->>'rest_end_reason' IN ('SKIPPED','EXPIRED') AND s->>'rest_elapsed_ms' IS NOT NULL)<>2 THEN
    RAISE EXCEPTION 'Sync page (Room hydration) lost rest outcome / session durations';
  END IF;
  -- Table-level guards independent of the RPC.
  BEGIN UPDATE public.workout_sets SET rest_end_reason='NOPE' WHERE workout_log_id=wid AND target_row_id='r1';
    RAISE EXCEPTION 'Constraint accepted unknown end reason'; EXCEPTION WHEN check_violation THEN NULL; END;
  BEGIN UPDATE public.workout_sets SET rest_elapsed_ms=-5 WHERE workout_log_id=wid AND target_row_id='r1';
    RAISE EXCEPTION 'Constraint accepted negative rest'; EXCEPTION WHEN check_violation THEN NULL; END;
  BEGIN UPDATE public.workout_sets SET rest_end_reason=NULL WHERE workout_log_id=wid AND target_row_id='r1';
    RAISE EXCEPTION 'Constraint accepted half an outcome'; EXCEPTION WHEN check_violation THEN NULL; END;
  BEGIN UPDATE public.workout_logs SET active_elapsed_ms=NULL WHERE id=wid;
    RAISE EXCEPTION 'Constraint accepted half session durations'; EXCEPTION WHEN check_violation THEN NULL; END;
END;
$stored$;
SET LOCAL ROLE authenticated;
DO $replay$
DECLARE c rest_case%ROWTYPE;
BEGIN
  SELECT * INTO c FROM rest_case;
  PERFORM set_config('request.jwt.claim.sub',c.owner_id::TEXT,true);
  IF public.persist_local_workout_aggregate(c.workout,c.exercises,c.sets) IS DISTINCT FROM c.receipt THEN
    RAISE EXCEPTION 'Replay changed receipt';
  END IF;
  BEGIN
    PERFORM public.persist_local_workout_aggregate(c.workout,c.exercises,
      (SELECT jsonb_agg(CASE WHEN s->>'target_row_id'='r1' THEN s||'{"rest_elapsed_ms":36000}' ELSE s END) FROM jsonb_array_elements(c.sets) s));
    RAISE EXCEPTION 'Different rest outcome reused a committed identity';
  EXCEPTION WHEN SQLSTATE 'PT409' THEN IF SQLERRM<>'IDEMPOTENCY_MISMATCH' THEN RAISE; END IF; END;
  RAISE NOTICE 'PASS: rest outcome + session durations store, validate, round-trip (detail + sync page), stay separate from target rest, refuse legacy writers, replay immutably';
END;
$replay$;
ROLLBACK;

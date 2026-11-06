-- T08 real authenticated lifecycle; all catalog changes and fixtures roll back.
BEGIN;
DO $fixture$
DECLARE owner_id UUID:=gen_random_uuid(); other_id UUID:=gen_random_uuid();
BEGIN
  INSERT INTO auth.users(id,email,raw_user_meta_data) VALUES(owner_id,'active-v3@example.invalid','{"name":"Active v3"}');
  INSERT INTO auth.users(id,email,raw_user_meta_data) VALUES(other_id,'active-v3-other@example.invalid','{"name":"Other owner"}');
  PERFORM set_config('imfit.active.other',other_id::TEXT,true);
  PERFORM set_config('imfit.active.owner',owner_id::TEXT,true);
  PERFORM set_config('imfit.active.session',gen_random_uuid()::TEXT,true);
  PERFORM set_config('imfit.active.template',gen_random_uuid()::TEXT,true);
END;
$fixture$;
CREATE TEMP TABLE active_v3_state(original_targets JSONB,initial_receipt JSONB,create_request UUID,version BIGINT,strength UUID,cardio UUID,strength_set UUID,cardio_set UUID);
GRANT SELECT,INSERT,UPDATE ON active_v3_state TO authenticated;
SET LOCAL ROLE authenticated;
DO $create$
DECLARE owner_id UUID:=current_setting('imfit.active.owner')::UUID;
  tid UUID:=current_setting('imfit.active.template')::UUID; sid UUID:=current_setting('imfit.active.session')::UUID;
  req UUID:=gen_random_uuid(); saved JSONB; created JSONB;
  rows JSONB:='[{"row_id":"s1","set_number":1,"weight":0,"reps":12,"duration_seconds":null,"rest_seconds":0},{"row_id":"s2","set_number":2,"weight":20,"reps":8,"duration_seconds":null,"rest_seconds":90}]';
  timed JSONB:='[{"row_id":"c1","set_number":1,"weight":null,"reps":null,"duration_seconds":60,"rest_seconds":0}]';
BEGIN
  PERFORM set_config('request.jwt.claim.sub',owner_id::TEXT,true);
  saved:=public.save_template_revision(gen_random_uuid(),tid,NULL,'V3 lifecycle',jsonb_build_array(
    jsonb_build_object('exercise_id','ex_chest_1','order_index',0,'set_targets',rows),
    jsonb_build_object('exercise_id','ex_cardio_1','order_index',1,'set_targets',timed)),'[]',false,owner_id,3);
  created:=public.create_active_session(req,sid,tid,(saved->>'revision')::BIGINT);
  IF created#>'{snapshot,schema_version}' IS DISTINCT FROM '3'::JSONB
     OR created#>'{snapshot,exercises,0,input_defaults,set_targets}' IS DISTINCT FROM rows
     OR created#>'{snapshot,exercises,0,input_defaults,extra_set_targets}' IS DISTINCT FROM '[]'::JSONB
     OR created#>>'{snapshot,exercises,0,sets,0,target_row_id}' IS DISTINCT FROM 's1'
     OR created#>'{snapshot,exercises,0,sets,0,weight}' IS DISTINCT FROM '0'::JSONB THEN
    RAISE EXCEPTION 'Initial versioned target/result snapshot was flattened or lost';
  END IF;
  INSERT INTO active_v3_state VALUES(rows,created,req,(created->>'version')::BIGINT,
    (created#>>'{snapshot,exercises,0,id}')::UUID,(created#>>'{snapshot,exercises,1,id}')::UUID,
    (created#>>'{snapshot,exercises,0,sets,0,id}')::UUID,(created#>>'{snapshot,exercises,1,sets,0,id}')::UUID);
END;
$create$;
RESET ROLE;
-- Change today's metadata after start. Frozen treadmill still supports distance
-- and incline; a newly added catalog capability must not leak into the snapshot.
UPDATE public.exercises SET supported_metrics=ARRAY['calories','distance','speed','laps','incline'] WHERE id='ex_cardio_1';
SET LOCAL ROLE authenticated;
DO $mutate$
DECLARE st active_v3_state%ROWTYPE; sid UUID:=current_setting('imfit.active.session')::UUID;
  result JSONB; replay JSONB; req UUID; extra1 UUID:=gen_random_uuid(); extra2 UUID:=gen_random_uuid();
  before_version BIGINT; removed UUID;
BEGIN
  SELECT * INTO st FROM active_v3_state;
  PERFORM set_config('request.jwt.claim.sub',current_setting('imfit.active.other'),true);
  BEGIN
    PERFORM public.create_active_session(gen_random_uuid(),gen_random_uuid(),current_setting('imfit.active.template')::UUID,
      (st.initial_receipt#>>'{snapshot,template_revision}')::BIGINT);
    RAISE EXCEPTION 'Other owner started a v3 routine';
  EXCEPTION WHEN SQLSTATE 'PT404' THEN NULL;
  END;
  BEGIN
    PERFORM public.update_active_session(gen_random_uuid(),sid,st.version,jsonb_build_object('type','complete_set','exercise_log_id',st.strength,'set_id',st.strength_set));
    RAISE EXCEPTION 'Other owner mutated a v3 session';
  EXCEPTION WHEN SQLSTATE 'PT404' THEN NULL;
  END;
  BEGIN
    PERFORM public.finish_active_session(gen_random_uuid(),sid,st.version);
    RAISE EXCEPTION 'Other owner finalized a v3 session';
  EXCEPTION WHEN SQLSTATE 'PT404' THEN NULL;
  END;
  PERFORM set_config('request.jwt.claim.sub',current_setting('imfit.active.owner'),true);
  result:=public.update_active_session(gen_random_uuid(),sid,st.version,jsonb_build_object('type','complete_set','exercise_log_id',st.strength,'set_id',st.strength_set));
  st.version:=(result->>'version')::BIGINT;
  before_version:=st.version;
  result:=public.update_active_session(gen_random_uuid(),sid,st.version,jsonb_build_object('type','complete_set','exercise_log_id',st.strength,'set_id',st.strength_set));
  IF (result->>'version')::BIGINT<>before_version THEN RAISE EXCEPTION 'Double complete mutated the session'; END IF;
  BEGIN
    PERFORM public.update_active_session(gen_random_uuid(),sid,st.version,jsonb_build_object('type','edit_set','exercise_log_id',st.strength,'set_id',st.strength_set,'weight',50));
    RAISE EXCEPTION 'Completed result was editable without undo';
  EXCEPTION WHEN SQLSTATE 'PT409' THEN NULL;
  END;
  result:=public.update_active_session(gen_random_uuid(),sid,st.version,jsonb_build_object('type','uncomplete_set','exercise_log_id',st.strength,'set_id',st.strength_set));
  st.version:=(result->>'version')::BIGINT;
  IF result#>'{snapshot,exercises,0,sets,0,weight}' IS DISTINCT FROM '0'::JSONB THEN RAISE EXCEPTION 'Undo discarded the result'; END IF;
  result:=public.update_active_session(gen_random_uuid(),sid,st.version,jsonb_build_object('type','complete_set','exercise_log_id',st.strength,'set_id',st.strength_set));
  st.version:=(result->>'version')::BIGINT;
  removed:=(result#>>'{snapshot,exercises,0,sets,1,id}')::UUID;
  result:=public.update_active_session(gen_random_uuid(),sid,st.version,jsonb_build_object('type','remove_set','exercise_log_id',st.strength,'set_id',removed));
  st.version:=(result->>'version')::BIGINT;
  result:=public.update_active_session(gen_random_uuid(),sid,st.version,jsonb_build_object('type','add_set','exercise_log_id',st.strength,'set_id',extra1,'target_row_id','extra-one'));
  st.version:=(result->>'version')::BIGINT;
  result:=public.update_active_session(gen_random_uuid(),sid,st.version,jsonb_build_object('type','add_set','exercise_log_id',st.strength,'set_id',extra2,'target_row_id','extra-two'));
  st.version:=(result->>'version')::BIGINT;
  result:=public.update_active_session(gen_random_uuid(),sid,st.version,jsonb_build_object('type','remove_set','exercise_log_id',st.strength,'set_id',extra1));
  st.version:=(result->>'version')::BIGINT;
  IF result#>'{snapshot,exercises,0,input_defaults,set_targets}' IS DISTINCT FROM st.original_targets
     OR result#>>'{snapshot,exercises,0,sets,1,id}' IS DISTINCT FROM extra2::TEXT
     OR result#>>'{snapshot,exercises,0,sets,1,target_row_id}' IS DISTINCT FROM 'extra-two'
     OR result#>'{snapshot,exercises,0,input_defaults,extra_set_targets,0,rest_seconds}' IS DISTINCT FROM '60'::JSONB
     OR result#>'{snapshot,exercises,0,input_defaults,extra_set_targets,0,set_number}' IS DISTINCT FROM '1'::JSONB THEN
    RAISE EXCEPTION 'Add/delete changed initial targets or surviving association';
  END IF;
  result:=public.update_active_session(gen_random_uuid(),sid,st.version,jsonb_build_object('type','edit_set','exercise_log_id',st.strength,'set_id',extra2,'weight',NULL));
  st.version:=(result->>'version')::BIGINT;
  BEGIN
    PERFORM public.update_active_session(gen_random_uuid(),sid,st.version,jsonb_build_object('type','complete_set','exercise_log_id',st.strength,'set_id',extra2));
    RAISE EXCEPTION 'Empty weight became zero or completed without validation';
  EXCEPTION WHEN SQLSTATE 'PT400' THEN NULL;
  END;
  result:=public.update_active_session(gen_random_uuid(),sid,st.version,jsonb_build_object('type','edit_set','exercise_log_id',st.strength,'set_id',extra2,'weight',0));
  st.version:=(result->>'version')::BIGINT;
  result:=public.update_active_session(gen_random_uuid(),sid,st.version,'{"type":"pause"}');
  st.version:=(result->>'version')::BIGINT;
  BEGIN
    PERFORM public.update_active_session(gen_random_uuid(),sid,st.version,jsonb_build_object('type','edit_set','exercise_log_id',st.strength,'set_id',extra2,'weight',10));
    RAISE EXCEPTION 'Paused session accepted a result mutation';
  EXCEPTION WHEN SQLSTATE 'PT409' THEN NULL;
  END;
  result:=public.update_active_session(gen_random_uuid(),sid,st.version,'{"type":"resume"}');
  st.version:=(result->>'version')::BIGINT;
  BEGIN
    PERFORM public.update_active_session(gen_random_uuid(),sid,st.version,jsonb_build_object('type','set_rest_seconds','exercise_log_id',st.strength,'rest_seconds',5));
    RAISE EXCEPTION 'Runtime rest override replaced a v3 target snapshot';
  EXCEPTION WHEN SQLSTATE 'PT400' THEN NULL;
  END;
  req:=gen_random_uuid();
  result:=public.update_active_session(gen_random_uuid(),sid,st.version,jsonb_build_object('type','add_exercise','exercise_log_id',req,'exercise_id','ex_core_1',
    'set_targets','[{"row_id":"iso-added","set_number":1,"weight":null,"reps":null,"duration_seconds":45,"rest_seconds":0}]'::JSONB));
  st.version:=(result->>'version')::BIGINT;
  IF result#>>'{snapshot,exercises,2,sets,0,target_row_id}' IS DISTINCT FROM 'iso-added'
     OR result#>'{snapshot,exercises,2,input_defaults,set_targets,0,rest_seconds}' IS DISTINCT FROM '0'::JSONB THEN
    RAISE EXCEPTION 'Direct-add lost its canonical per-row target';
  END IF;
  result:=public.update_active_session(gen_random_uuid(),sid,st.version,jsonb_build_object('type','select_exercise','exercise_log_id',st.cardio));
  st.version:=(result->>'version')::BIGINT;
  result:=public.update_active_session(gen_random_uuid(),sid,st.version,jsonb_build_object('type','remove_exercise','exercise_log_id',req));
  st.version:=(result->>'version')::BIGINT;
  BEGIN
    PERFORM public.update_active_session(gen_random_uuid(),sid,st.version,jsonb_build_object('type','edit_set','exercise_log_id',st.cardio,'set_id',st.cardio_set,'lap_reps',1));
    RAISE EXCEPTION 'Current catalog capability leaked into a frozen snapshot';
  EXCEPTION WHEN SQLSTATE 'PT400' THEN NULL;
  END;
  req:=gen_random_uuid();
  result:=public.update_active_session(req,sid,st.version,jsonb_build_object('type','edit_set','exercise_log_id',st.cardio,'set_id',st.cardio_set,'distance_meters',1234.56,'speed_kph',10.25,'incline_percent',-2.5,'calories_kcal',0));
  replay:=public.update_active_session(req,sid,st.version,jsonb_build_object('type','edit_set','exercise_log_id',st.cardio,'set_id',st.cardio_set,'distance_meters',1234.56,'speed_kph',10.25,'incline_percent',-2.5,'calories_kcal',0));
  IF replay IS DISTINCT FROM result THEN RAISE EXCEPTION 'Mutation receipt replay changed'; END IF;
  st.version:=(result->>'version')::BIGINT;
  result:=public.update_active_session(gen_random_uuid(),sid,st.version,jsonb_build_object('type','edit_set','exercise_log_id',st.cardio,'set_id',st.cardio_set,'distance_meters',NULL));
  st.version:=(result->>'version')::BIGINT;
  IF result#>'{snapshot,exercises,1,sets,0,distance_meters}' IS DISTINCT FROM 'null'::JSONB
     OR result#>'{snapshot,exercises,1,sets,0,speed_kph}' IS DISTINCT FROM '10.25'::JSONB THEN
    RAISE EXCEPTION 'PATCH omitted/clear semantics changed';
  END IF;
  result:=public.update_active_session(gen_random_uuid(),sid,st.version,jsonb_build_object('type','complete_set','exercise_log_id',st.cardio,'set_id',st.cardio_set));
  st.version:=(result->>'version')::BIGINT;
  -- Optional distance was cleared: positive duration alone is sufficient.
  req:=gen_random_uuid();
  result:=public.finish_active_session(req,sid,st.version);
  replay:=public.finish_active_session(req,sid,st.version);
  IF result IS DISTINCT FROM replay OR result->>'workout_id' IS DISTINCT FROM sid::TEXT THEN
    RAISE EXCEPTION 'V3 finish changed stable workout identity or receipt';
  END IF;
  IF public.create_active_session(st.create_request,sid,current_setting('imfit.active.template')::UUID,
      (st.initial_receipt#>>'{snapshot,template_revision}')::BIGINT) IS DISTINCT FROM st.initial_receipt THEN
    RAISE EXCEPTION 'Original create receipt was re-executed after finalization';
  END IF;
END;
$mutate$;
RESET ROLE;
DO $persisted$
DECLARE sid UUID:=current_setting('imfit.active.session')::UUID; st active_v3_state%ROWTYPE;
BEGIN
  SELECT * INTO st FROM active_v3_state;
  IF NOT EXISTS(SELECT 1 FROM public.workout_logs WHERE id=sid AND measurement_version=3 AND recorded_load_volume=0 AND total_volume=0 AND total_sets=2 AND total_reps=12 AND total_distance_meters IS NULL)
     OR NOT EXISTS(SELECT 1 FROM public.workout_sets WHERE id=st.cardio_set AND workout_log_id=sid AND target_row_id='c1'
       AND weight IS NULL AND reps IS NULL AND distance_meters IS NULL AND speed_kph=10.25 AND calories_kcal=0 AND incline_percent=-2.5)
     OR (SELECT input_defaults->'set_targets' FROM public.exercise_logs WHERE id=st.strength) IS DISTINCT FROM st.original_targets THEN
    RAISE EXCEPTION 'V3 finish lost null/zero/decline, target snapshot, result ID or completed-only aggregates';
  END IF;
  RAISE NOTICE 'PASS: v3 session start/mutate/undo/association/capability/clear/finish and immutable replay';
END;
$persisted$;
ROLLBACK;

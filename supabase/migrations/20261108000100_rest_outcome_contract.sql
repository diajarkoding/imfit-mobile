-- T37: actual rest outcome and session durations as additive columns on existing tables.
-- Rest TARGETS (`rest_seconds` in snapshots) are never touched. Legacy/v1/v2/v3 rows and receipts keep
-- their meaning: new columns are NULL on history and are written only by the v3 aggregate writer.
-- Wire (flat, like the other result fields): set `rest_elapsed_ms` + `rest_end_reason` (EXPIRED|SKIPPED);
-- workout `paused_elapsed_ms` + `active_elapsed_ms`. active = whole-session time without Pause (includes rest).
SET LOCAL lock_timeout='8s';
SET LOCAL statement_timeout='120s';

ALTER TABLE public.workout_sets
  ADD COLUMN rest_elapsed_ms BIGINT NULL,
  ADD COLUMN rest_end_reason TEXT NULL,
  ADD CONSTRAINT workout_sets_rest_outcome_check CHECK (
    (rest_elapsed_ms IS NULL AND rest_end_reason IS NULL)
    OR (rest_elapsed_ms IS NOT NULL AND rest_elapsed_ms BETWEEN 0 AND 86400000
        AND rest_end_reason IS NOT NULL AND rest_end_reason IN ('EXPIRED','SKIPPED')
        AND measurement_version = 3 AND is_completed)
  );
ALTER TABLE public.workout_logs
  ADD COLUMN paused_elapsed_ms BIGINT NULL,
  ADD COLUMN active_elapsed_ms BIGINT NULL,
  ADD CONSTRAINT workout_logs_session_durations_check CHECK (
    (paused_elapsed_ms IS NULL AND active_elapsed_ms IS NULL)
    OR (paused_elapsed_ms IS NOT NULL AND active_elapsed_ms IS NOT NULL
        AND paused_elapsed_ms BETWEEN 0 AND 172800000 AND active_elapsed_ms BETWEEN 0 AND 172800000
        AND measurement_version = 3)
  );
COMMENT ON COLUMN public.workout_sets.rest_elapsed_ms IS
  'Actual active rest (ms) that followed this completed set; separate from the routine target rest_seconds. NULL = no outcome.';
COMMENT ON COLUMN public.workout_sets.rest_end_reason IS 'EXPIRED (deadline reached) or SKIPPED (user pressed Skip).';
COMMENT ON COLUMN public.workout_logs.active_elapsed_ms IS
  'Whole-session time excluding Pause but including rest (ms); not the sum of set durations. v3 only.';
COMMENT ON COLUMN public.workout_logs.paused_elapsed_ms IS 'Total time paused (ms). v3 only.';

CREATE OR REPLACE FUNCTION imfit_private.validate_result_v3(p_snapshot JSONB,p_set JSONB,p_completed BOOLEAN)
RETURNS VOID LANGUAGE plpgsql IMMUTABLE SET search_path='' AS $function$
DECLARE key TEXT; metric TEXT; n NUMERIC; mode TEXT:=p_snapshot->>'tracking_type';
BEGIN
  IF jsonb_typeof(p_set) IS DISTINCT FROM 'object'
     OR (p_set-ARRAY['id','exercise_log_id','workout_log_id','exercise_id','set_number','measurement_version','target_row_id',
       'weight','reps','duration_seconds','distance_meters','calories_kcal','speed_kph','lap_reps','incline_percent',
       'additional_weight','assistance_weight','is_completed','is_warmup','rest_elapsed_ms','rest_end_reason'])<>'{}'::JSONB
     OR p_set->'measurement_version' IS DISTINCT FROM '3'::JSONB
     OR jsonb_typeof(p_set->'target_row_id') IS DISTINCT FROM 'string'
     OR NOT EXISTS(SELECT 1 FROM jsonb_array_elements((p_snapshot->'set_targets')||(p_snapshot->'extra_set_targets')) t
       WHERE t->>'row_id'=p_set->>'target_row_id')
     OR jsonb_typeof(p_set->'is_completed') IS DISTINCT FROM 'boolean'
     OR (p_set ? 'is_warmup' AND jsonb_typeof(p_set->'is_warmup') IS DISTINCT FROM 'boolean') THEN
    PERFORM imfit_private.fail('PT400','VALIDATION');
  END IF;
  PERFORM imfit_private.json_uuid(p_set->'id');
  PERFORM imfit_private.v3_number(p_set->'set_number',1,20,TRUE,FALSE);
  IF mode='WEIGHT_REPS' THEN
    PERFORM imfit_private.v3_number(p_set->'weight',0,1000,FALSE,NOT p_completed);
    PERFORM imfit_private.v3_number(p_set->'reps',CASE WHEN p_completed THEN 1 ELSE 0 END,1000,TRUE,NOT p_completed);
    IF p_set->>'duration_seconds' IS NOT NULL THEN PERFORM imfit_private.fail('PT400','VALIDATION'); END IF;
  ELSE
    PERFORM imfit_private.v3_number(p_set->'duration_seconds',CASE WHEN p_completed THEN 1 ELSE 0 END,86400,TRUE,NOT p_completed);
    IF p_set->>'weight' IS NOT NULL OR p_set->>'reps' IS NOT NULL THEN PERFORM imfit_private.fail('PT400','VALIDATION'); END IF;
  END IF;
  IF p_set->>'additional_weight' IS NOT NULL OR p_set->>'assistance_weight' IS NOT NULL THEN
    PERFORM imfit_private.fail('PT400','VALIDATION');
  END IF;
  FOREACH key IN ARRAY ARRAY['calories_kcal','distance_meters','speed_kph','lap_reps','incline_percent'] LOOP
    metric:=CASE key WHEN 'calories_kcal' THEN 'calories' WHEN 'distance_meters' THEN 'distance'
      WHEN 'speed_kph' THEN 'speed' WHEN 'lap_reps' THEN 'laps' ELSE 'incline' END;
    IF p_set->>key IS NOT NULL THEN
      IF p_snapshot->>'modality'<>'CARDIO' OR NOT(p_snapshot->'supported_metrics' ? metric) THEN
        PERFORM imfit_private.fail('PT400','VALIDATION');
      END IF;
      n:=imfit_private.v3_number(p_set->key,CASE WHEN metric='incline' THEN -999.99 ELSE 0 END,
        CASE WHEN metric='incline' THEN 999.99 WHEN metric='laps' THEN 2147483647 ELSE 9999999999.99 END,metric='laps',TRUE);
    END IF;
  END LOOP;
  -- Actual rest outcome (T37): both fields or neither, only on a completed result, separate from the target rest.
  IF (p_set->>'rest_elapsed_ms' IS NULL) IS DISTINCT FROM (p_set->>'rest_end_reason' IS NULL) THEN
    PERFORM imfit_private.fail('PT400','VALIDATION');
  END IF;
  IF p_set->>'rest_elapsed_ms' IS NOT NULL THEN
    IF NOT p_completed
       OR jsonb_typeof(p_set->'rest_end_reason') IS DISTINCT FROM 'string'
       OR p_set->>'rest_end_reason' NOT IN ('EXPIRED','SKIPPED') THEN
      PERFORM imfit_private.fail('PT400','VALIDATION');
    END IF;
    PERFORM imfit_private.v3_number(p_set->'rest_elapsed_ms',0,86400000,TRUE,FALSE);
  END IF;
END;
$function$;

CREATE OR REPLACE FUNCTION imfit_private.insert_workout_v3(p_owner UUID,p_workout JSONB,p_exercises JSONB,p_sets JSONB)
RETURNS JSONB LANGUAGE plpgsql SET search_path='' AS $function$
DECLARE
  wid UUID; e JSONB; s JSONB; snap JSONB; eid UUID; now_at TIMESTAMPTZ:=statement_timestamp();
  total_load NUMERIC:=0; total_reps INTEGER:=0; total_duration INTEGER:=0; total_distance NUMERIC;
  load NUMERIC; reps INTEGER; duration INTEGER; distance NUMERIC; set_count INTEGER; mode TEXT;
  paused_ms NUMERIC; active_ms NUMERIC;
BEGIN
  IF jsonb_typeof(p_workout) IS DISTINCT FROM 'object'
     OR p_workout->'measurement_version' IS DISTINCT FROM '3'::JSONB
     OR jsonb_typeof(p_exercises) IS DISTINCT FROM 'array'
     OR jsonb_typeof(p_sets) IS DISTINCT FROM 'array' THEN PERFORM imfit_private.fail('PT400','VALIDATION'); END IF;
  wid:=imfit_private.json_uuid(p_workout->'id');
  IF imfit_private.json_uuid(p_workout->'user_id') IS DISTINCT FROM p_owner THEN
    RAISE EXCEPTION 'Workout owner mismatch' USING ERRCODE='42501';
  END IF;
  IF p_workout->>'template_id' IS NOT NULL AND NOT EXISTS(
    SELECT 1 FROM public.workout_templates WHERE id=(p_workout->>'template_id')::UUID AND user_id=p_owner
  ) THEN RAISE EXCEPTION 'Template owner mismatch' USING ERRCODE='42501'; END IF;
  IF p_workout->>'source_session_id' IS NOT NULL AND NOT EXISTS(
    SELECT 1 FROM public.active_sessions WHERE id=(p_workout->>'source_session_id')::UUID AND user_id=p_owner
  ) THEN RAISE EXCEPTION 'Session owner mismatch' USING ERRCODE='42501'; END IF;
  IF nullif(btrim(p_workout->>'template_name'),'') IS NULL
     OR (p_workout->>'start_time')::TIMESTAMPTZ IS NULL OR (p_workout->>'end_time')::TIMESTAMPTZ IS NULL
     OR NOT isfinite((p_workout->>'start_time')::TIMESTAMPTZ) OR NOT isfinite((p_workout->>'end_time')::TIMESTAMPTZ)
     OR (p_workout->>'end_time')::TIMESTAMPTZ<=(p_workout->>'start_time')::TIMESTAMPTZ
     OR (p_workout->>'date')::DATE IS DISTINCT FROM ((p_workout->>'start_time')::TIMESTAMPTZ AT TIME ZONE 'UTC')::DATE
     OR p_workout->>'deleted_at' IS NOT NULL
     OR jsonb_array_length(p_exercises) NOT BETWEEN 1 AND 100
     OR jsonb_array_length(p_sets) NOT BETWEEN 1 AND 2000
     OR octet_length(jsonb_build_array(p_workout,p_exercises,p_sets)::TEXT)>262144 THEN
    PERFORM imfit_private.fail('PT400','VALIDATION');
  END IF;
  IF (SELECT count(DISTINCT x->>'id') FROM jsonb_array_elements(p_exercises) x)<>jsonb_array_length(p_exercises)
     OR (SELECT count(DISTINCT x->>'exercise_id') FROM jsonb_array_elements(p_exercises) x)<>jsonb_array_length(p_exercises)
     OR (SELECT count(DISTINCT x->>'order_index') FROM jsonb_array_elements(p_exercises) x)<>jsonb_array_length(p_exercises)
     OR (SELECT count(DISTINCT x->>'id') FROM jsonb_array_elements(p_sets) x)<>jsonb_array_length(p_sets) THEN
    PERFORM imfit_private.fail('PT400','VALIDATION');
  END IF;
  -- Validate everything before inserting even the parent. No metadata is resolved
  -- from the current catalog; existence alone maintains the existing catalog FK.
  FOR e IN SELECT value FROM jsonb_array_elements(p_exercises) LOOP
    eid:=imfit_private.json_uuid(e->'id'); snap:=e->'input_defaults';
    PERFORM imfit_private.validate_snapshot_v3(snap);
    IF e->'measurement_version' IS DISTINCT FROM '3'::JSONB
       OR imfit_private.json_uuid(e->'workout_log_id') IS DISTINCT FROM wid
       OR nullif(btrim(e->>'exercise_name'),'') IS NULL OR nullif(btrim(e->>'muscle_category'),'') IS NULL
       OR NOT EXISTS(SELECT 1 FROM public.exercises WHERE id=e->>'exercise_id')
       OR e->'tracking_type' IS DISTINCT FROM snap->'tracking_type'
       OR e->'modality' IS DISTINCT FROM snap->'modality' THEN PERFORM imfit_private.fail('PT400','VALIDATION'); END IF;
    PERFORM imfit_private.v3_number(e->'order_index',0,99,TRUE,FALSE);
    FOR s IN SELECT value FROM jsonb_array_elements(p_sets) WHERE value->>'exercise_log_id'=eid::TEXT LOOP
      IF imfit_private.json_uuid(s->'workout_log_id') IS DISTINCT FROM wid
         OR s->>'exercise_id' IS DISTINCT FROM e->>'exercise_id'
         OR s->'is_completed' IS DISTINCT FROM 'true'::JSONB THEN PERFORM imfit_private.fail('PT400','VALIDATION'); END IF;
      PERFORM imfit_private.validate_result_v3(snap,s,TRUE);
    END LOOP;
    SELECT count(*),coalesce(sum(CASE WHEN snap->>'tracking_type'='WEIGHT_REPS'
        THEN (x->>'weight')::NUMERIC*(x->>'reps')::INTEGER ELSE 0 END),0),
      coalesce(sum(CASE WHEN snap->>'tracking_type'='WEIGHT_REPS' THEN (x->>'reps')::INTEGER ELSE 0 END),0),
      coalesce(sum(CASE WHEN snap->>'tracking_type'='DURATION' THEN (x->>'duration_seconds')::INTEGER ELSE 0 END),0),
      sum((x->>'distance_meters')::NUMERIC)
    INTO set_count,load,reps,duration,distance FROM jsonb_array_elements(p_sets) x WHERE x->>'exercise_log_id'=eid::TEXT;
    IF set_count NOT BETWEEN 1 AND 20
       OR (SELECT count(DISTINCT x->>'set_number') FROM jsonb_array_elements(p_sets) x WHERE x->>'exercise_log_id'=eid::TEXT)<>set_count
       OR (SELECT count(DISTINCT x->>'target_row_id') FROM jsonb_array_elements(p_sets) x WHERE x->>'exercise_log_id'=eid::TEXT)<>set_count
       OR (e ? 'total_sets' AND imfit_private.v3_number(e->'total_sets',1,20,TRUE,FALSE)<>set_count)
       OR (e ? 'total_reps' AND imfit_private.v3_number(e->'total_reps',0,20000,TRUE,FALSE)<>reps)
       OR (e ? 'total_volume' AND imfit_private.v3_number(e->'total_volume',0,0,FALSE,FALSE)<>0)
       OR (e ? 'recorded_load_volume' AND imfit_private.v3_number(e->'recorded_load_volume',0,9999999999.99,FALSE,FALSE)<>load)
       OR (e ? 'total_duration_seconds' AND imfit_private.v3_number(e->'total_duration_seconds',0,1728000,TRUE,FALSE)<>duration)
       OR (e ? 'total_distance_meters' AND (imfit_private.v3_number(e->'total_distance_meters',0,99999999999999.99,FALSE,TRUE) IS DISTINCT FROM distance))
       OR (e ? 'additional_volume' AND imfit_private.v3_number(e->'additional_volume',0,0,FALSE,FALSE)<>0) THEN
      PERFORM imfit_private.fail('PT400','VALIDATION');
    END IF;
    total_load:=total_load+load; total_reps:=total_reps+reps; total_duration:=total_duration+duration;
  END LOOP;
  IF EXISTS(SELECT 1 FROM jsonb_array_elements(p_sets) set_item WHERE NOT EXISTS(
    SELECT 1 FROM jsonb_array_elements(p_exercises) exercise_item WHERE exercise_item->>'id'=set_item->>'exercise_log_id'
  )) THEN PERFORM imfit_private.fail('PT400','VALIDATION'); END IF;
  SELECT sum((x->>'distance_meters')::NUMERIC) INTO total_distance FROM jsonb_array_elements(p_sets) x;
  IF (p_workout ? 'total_sets' AND imfit_private.v3_number(p_workout->'total_sets',1,2000,TRUE,FALSE)<>jsonb_array_length(p_sets))
     OR (p_workout ? 'total_reps' AND imfit_private.v3_number(p_workout->'total_reps',0,2000000,TRUE,FALSE)<>total_reps)
     OR (p_workout ? 'total_volume' AND imfit_private.v3_number(p_workout->'total_volume',0,0,FALSE,FALSE)<>0)
     OR (p_workout ? 'recorded_load_volume' AND imfit_private.v3_number(p_workout->'recorded_load_volume',0,9999999999.99,FALSE,FALSE)<>total_load)
     OR (p_workout ? 'total_duration_seconds' AND imfit_private.v3_number(p_workout->'total_duration_seconds',0,172800000,TRUE,FALSE)<>total_duration)
     OR (p_workout ? 'total_distance_meters' AND (imfit_private.v3_number(p_workout->'total_distance_meters',0,99999999999999.99,FALSE,TRUE) IS DISTINCT FROM total_distance))
     OR (p_workout ? 'additional_volume' AND imfit_private.v3_number(p_workout->'additional_volume',0,0,FALSE,FALSE)<>0) THEN
    PERFORM imfit_private.fail('PT400','VALIDATION');
  END IF;
  -- Session durations (T37): both or neither. Active time includes rest and excludes Pause, so it can never
  -- exceed the gross wall span together with paused time (1 s rounding tolerance).
  IF (p_workout->>'paused_elapsed_ms' IS NULL) IS DISTINCT FROM (p_workout->>'active_elapsed_ms' IS NULL) THEN
    PERFORM imfit_private.fail('PT400','VALIDATION');
  END IF;
  IF p_workout->>'active_elapsed_ms' IS NOT NULL THEN
    paused_ms:=imfit_private.v3_number(p_workout->'paused_elapsed_ms',0,172800000,TRUE,FALSE);
    active_ms:=imfit_private.v3_number(p_workout->'active_elapsed_ms',0,172800000,TRUE,FALSE);
    IF paused_ms+active_ms>floor(extract(epoch FROM ((p_workout->>'end_time')::TIMESTAMPTZ-(p_workout->>'start_time')::TIMESTAMPTZ))*1000)+1000 THEN
      PERFORM imfit_private.fail('PT400','VALIDATION');
    END IF;
  END IF;
  INSERT INTO public.workout_logs(id,user_id,template_id,template_name,date,start_time,end_time,
    total_volume,total_sets,total_reps,total_duration_seconds,total_distance_meters,additional_volume,data_quality,
    measurement_version,recorded_load_volume,source_session_id,paused_elapsed_ms,active_elapsed_ms,created_at,updated_at)
  VALUES(wid,p_owner,(p_workout->>'template_id')::UUID,p_workout->>'template_name',(p_workout->>'date')::DATE,
    (p_workout->>'start_time')::TIMESTAMPTZ,(p_workout->>'end_time')::TIMESTAMPTZ,
    0,jsonb_array_length(p_sets),total_reps,total_duration,total_distance,0,'VERIFIED',3,total_load,
    (p_workout->>'source_session_id')::UUID,paused_ms::BIGINT,active_ms::BIGINT,now_at,now_at);
  FOR e IN SELECT value FROM jsonb_array_elements(p_exercises) LOOP
    eid:=(e->>'id')::UUID; snap:=e->'input_defaults'; mode:=snap->>'tracking_type';
    SELECT count(*),coalesce(sum(CASE WHEN mode='WEIGHT_REPS' THEN (x->>'weight')::NUMERIC*(x->>'reps')::INTEGER ELSE 0 END),0),
      coalesce(sum(CASE WHEN mode='WEIGHT_REPS' THEN (x->>'reps')::INTEGER ELSE 0 END),0),
      coalesce(sum(CASE WHEN mode='DURATION' THEN (x->>'duration_seconds')::INTEGER ELSE 0 END),0),sum((x->>'distance_meters')::NUMERIC)
      INTO set_count,load,reps,duration,distance FROM jsonb_array_elements(p_sets) x WHERE x->>'exercise_log_id'=eid::TEXT;
    INSERT INTO public.exercise_logs(id,workout_log_id,exercise_id,exercise_name,muscle_category,order_index,
      total_volume,total_sets,total_reps,total_duration_seconds,total_distance_meters,additional_volume,
      tracking_type,modality,metric_quality,input_defaults,measurement_version,recorded_load_volume,created_at,updated_at)
    VALUES(eid,wid,e->>'exercise_id',e->>'exercise_name',e->>'muscle_category',(e->>'order_index')::INTEGER,
      0,set_count,reps,duration,distance,0,mode::public.tracking_type_enum,(snap->>'modality')::public.exercise_modality_enum,
      'VERIFIED',snap,3,load,now_at,now_at);
  END LOOP;
  FOR s IN SELECT value FROM jsonb_array_elements(p_sets) LOOP
    INSERT INTO public.workout_sets(id,exercise_log_id,workout_log_id,exercise_id,set_number,weight,reps,
      duration_seconds,distance_meters,additional_weight,assistance_weight,is_completed,is_warmup,
      measurement_version,target_row_id,calories_kcal,speed_kph,lap_reps,incline_percent,rest_elapsed_ms,rest_end_reason,created_at,updated_at)
    VALUES((s->>'id')::UUID,(s->>'exercise_log_id')::UUID,wid,s->>'exercise_id',(s->>'set_number')::INTEGER,
      (s->>'weight')::NUMERIC,(s->>'reps')::INTEGER,(s->>'duration_seconds')::INTEGER,(s->>'distance_meters')::NUMERIC,
      NULL,NULL,TRUE,coalesce((s->>'is_warmup')::BOOLEAN,FALSE),3,s->>'target_row_id',
      (s->>'calories_kcal')::NUMERIC,(s->>'speed_kph')::NUMERIC,(s->>'lap_reps')::INTEGER,
      (s->>'incline_percent')::NUMERIC,(s->>'rest_elapsed_ms')::BIGINT,s->>'rest_end_reason',now_at,now_at);
  END LOOP;
  RETURN jsonb_build_object('workout_id',wid,'exercise_count',jsonb_array_length(p_exercises),
    'set_count',jsonb_array_length(p_sets),'measurement_version',3,'recorded_load_volume',total_load,'server_updated_at',now_at);
END;
$function$;

CREATE OR REPLACE FUNCTION public.persist_local_workout_aggregate(
  p_workout jsonb,
  p_exercises jsonb,
  p_sets jsonb
)
 RETURNS jsonb
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare
  v_owner uuid := auth.uid();
  v_id uuid;
  v_payload jsonb;
  v_receipt imfit_private.local_workout_receipts%rowtype;
  v_e jsonb;
  v_s jsonb;
  v_count integer;
  v_reps integer;
  v_volume numeric;
  v_add_vol numeric;
  v_dur integer;
  v_dist integer;
  v_total_reps integer := 0;
  v_total_volume numeric := 0;
  v_total_add_vol numeric := 0;
  v_total_duration integer := 0;
  v_total_distance integer := 0;
  v_now timestamptz := statement_timestamp();
  v_result jsonb;
  v_track text;
  v_modality public.exercise_modality_enum;
  v_quality text;
begin
  if v_owner is null then raise exception 'Authentication required' using errcode = '42501'; end if;
  if jsonb_typeof(p_workout) is distinct from 'object'
    or jsonb_typeof(p_exercises) is distinct from 'array'
    or jsonb_typeof(p_sets) is distinct from 'array'
    or octet_length(jsonb_build_array(p_workout, p_exercises, p_sets)::text) > 262144 then
    raise exception 'Invalid aggregate envelope' using errcode = '22023';
  end if;

  v_id := (p_workout->>'id')::uuid;
  if v_id is null or (p_workout->>'user_id')::uuid is distinct from v_owner then
    raise exception 'Workout owner mismatch' using errcode = '42501';
  end if;

  v_payload := jsonb_build_object(
    'workout', p_workout,
    'exercises', (select coalesce(jsonb_agg(e order by e->>'id'), '[]'::jsonb) from jsonb_array_elements(p_exercises) e),
    'sets', (select coalesce(jsonb_agg(s order by s->>'id'), '[]'::jsonb) from jsonb_array_elements(p_sets) s)
  );

  perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(v_id::text, 73));
  select * into v_receipt from imfit_private.local_workout_receipts where workout_id = v_id;
  if found then
    if v_receipt.user_id <> v_owner then raise exception 'Workout owner mismatch' using errcode = '42501'; end if;
    if v_receipt.payload is distinct from v_payload then
      raise exception 'IDEMPOTENCY_MISMATCH' using errcode = 'PT409';
    end if;
    return v_receipt.result;
  end if;

  if exists (select 1 from public.workout_logs where id = v_id) then
    raise exception 'WORKOUT_ALREADY_EXISTS' using errcode = 'PT409';
  end if;

  if p_workout->'measurement_version' = '3'::jsonb then
    v_result:=imfit_private.insert_workout_v3(v_owner,p_workout,p_exercises,p_sets);
    insert into imfit_private.local_workout_receipts(workout_id,user_id,payload,result)
      values(v_id,v_owner,v_payload,v_result);
    return v_result;
  end if;
  -- Explicit unknown/mixed versions and new metrics may not silently fall through
  -- a legacy writer that would coerce null weight/reps or discard measurements.
  if p_workout ? 'measurement_version' or p_workout ? 'paused_elapsed_ms' or p_workout ? 'active_elapsed_ms'
     or exists(select 1 from jsonb_array_elements(p_exercises) e where e ? 'measurement_version'
       or e#>'{input_defaults,schema_version}'='3'::jsonb)
     or exists(select 1 from jsonb_array_elements(p_sets) s where s ? 'measurement_version'
       or s ? 'rest_elapsed_ms' or s ? 'rest_end_reason'
       or s->>'target_row_id' is not null or s->>'calories_kcal' is not null
       or s->>'speed_kph' is not null or s->>'lap_reps' is not null or s->>'incline_percent' is not null) then
    perform imfit_private.fail('PT400','VALIDATION');
  end if;

  if not exists (select 1 from public.workout_templates where id = (p_workout->>'template_id')::uuid and user_id = v_owner)
    or nullif(btrim(p_workout->>'template_name'), '') is null
    or (p_workout->>'start_time')::timestamptz is null
    or (p_workout->>'end_time')::timestamptz is null
    or (p_workout->>'end_time')::timestamptz <= (p_workout->>'start_time')::timestamptz
    or (p_workout->>'date')::date is distinct from ((p_workout->>'start_time')::timestamptz at time zone 'UTC')::date
    or p_workout->>'deleted_at' is not null
    or jsonb_array_length(p_exercises) not between 1 and 100
    or jsonb_array_length(p_sets) not between 1 and 2000 then
    raise exception 'Invalid completed workout' using errcode = '22023';
  end if;

  if (select count(distinct e->>'id') from jsonb_array_elements(p_exercises) e) <> jsonb_array_length(p_exercises)
    or (select count(distinct e->>'order_index') from jsonb_array_elements(p_exercises) e) <> jsonb_array_length(p_exercises)
    or (select count(distinct s->>'id') from jsonb_array_elements(p_sets) s) <> jsonb_array_length(p_sets) then
    raise exception 'Duplicate snapshot identifiers' using errcode = '22023';
  end if;

  -- Frozen metadata is never inferred from today's catalog defaults or modality.
  -- A missing v1 tracking field means WEIGHT_REPS; an explicit null is invalid.
  -- Missing/null modality represents unknown historical metadata, never STRENGTH.
  for v_e in select value from jsonb_array_elements(p_exercises) loop
    if v_e ? 'tracking_type' and (
      jsonb_typeof(v_e->'tracking_type') is distinct from 'string'
      or v_e->>'tracking_type' not in (
        'WEIGHT_REPS', 'BODYWEIGHT_REPS', 'ASSISTED_REPS',
        'REPS_ONLY', 'DURATION', 'DISTANCE_DURATION'
      )
    ) then
      raise exception 'Invalid snapshot tracking type' using errcode = '22023';
    end if;
    if v_e->>'modality' is not null and (
      jsonb_typeof(v_e->'modality') is distinct from 'string'
      or v_e->>'modality' not in (
        'STRENGTH', 'CALISTHENICS', 'ISOMETRIC', 'CARDIO', 'CONDITIONING'
      )
    ) then
      raise exception 'Invalid snapshot modality' using errcode = '22023';
    end if;
  end loop;

  -- Validate sets
  for v_s in select value from jsonb_array_elements(p_sets) loop
    if jsonb_typeof(v_s) <> 'object' or (v_s->>'id')::uuid is null
      or (v_s->>'workout_log_id')::uuid is distinct from v_id
      or (v_s->>'is_completed')::boolean is distinct from true
      or (v_s->>'set_number')::integer is null or (v_s->>'set_number')::integer < 1
      or not exists (
        select 1 from jsonb_array_elements(p_exercises) e
        where (e->>'id')::uuid = (v_s->>'exercise_log_id')::uuid
          and e->>'exercise_id' = v_s->>'exercise_id'
      ) then
      raise exception 'Invalid completed set snapshot' using errcode = '22023';
    end if;

    select e->>'tracking_type' into v_track
    from jsonb_array_elements(p_exercises) e
    where (e->>'id')::uuid = (v_s->>'exercise_log_id')::uuid;
    if v_track is null then
      v_track := 'WEIGHT_REPS';
    elsif not exists (
      select 1 from public.exercise_tracking_modes m
      where m.exercise_id = v_s->>'exercise_id' and m.tracking_type::text = v_track
    ) then
      raise exception 'Tracking type is not allowed for exercise' using errcode = '22023';
    end if;

    -- Validate set measurement via shared validator
    begin
      perform imfit_private.validate_set_measurement(v_track, v_s, true);
    exception when sqlstate 'PT400' then
      raise exception 'Set measurement invalid for tracking type' using errcode = '22023';
    end;
  end loop;

  -- Validate exercises and calculate totals
  for v_e in select value from jsonb_array_elements(p_exercises) loop
    if jsonb_typeof(v_e) <> 'object' or (v_e->>'id')::uuid is null
      or (v_e->>'workout_log_id')::uuid is distinct from v_id
      or nullif(btrim(v_e->>'exercise_name'), '') is null
      or nullif(btrim(v_e->>'muscle_category'), '') is null
      or (v_e->>'order_index')::integer is null or (v_e->>'order_index')::integer < 0
      or not exists (select 1 from public.exercises where id = v_e->>'exercise_id') then
      raise exception 'Invalid exercise snapshot' using errcode = '22023';
    end if;

    v_modality := (v_e->>'modality')::public.exercise_modality_enum;
    v_track := coalesce(v_e->>'tracking_type', 'WEIGHT_REPS');

    v_count := 0; v_reps := 0; v_volume := 0; v_add_vol := 0; v_dur := 0; v_dist := 0;
    for v_s in select value from jsonb_array_elements(p_sets) where (value->>'exercise_log_id')::uuid = (v_e->>'id')::uuid loop
      v_count := v_count + 1;
      if v_track = 'WEIGHT_REPS' then
        v_volume := v_volume + ((v_s->>'weight')::numeric * (v_s->>'reps')::integer);
      elsif v_track = 'BODYWEIGHT_REPS' then
        v_add_vol := v_add_vol + (coalesce((v_s->>'additional_weight')::numeric, 0) * (v_s->>'reps')::integer);
      end if;
      if v_track in ('WEIGHT_REPS', 'BODYWEIGHT_REPS', 'ASSISTED_REPS', 'REPS_ONLY') then
        v_reps := v_reps + coalesce((v_s->>'reps')::integer, 0);
      end if;
      if v_track in ('DURATION', 'DISTANCE_DURATION') then
        v_dur := v_dur + coalesce((v_s->>'duration_seconds')::integer, 0);
      end if;
      if v_track = 'DISTANCE_DURATION' then
        v_dist := v_dist + coalesce((v_s->>'distance_meters')::integer, 0);
      end if;
    end loop;

    if v_count = 0 or (select count(distinct s->>'set_number') from jsonb_array_elements(p_sets) s
        where s->>'exercise_log_id' = v_e->>'id') <> v_count then
      raise exception 'Exercise totals do not match snapshot' using errcode = '22023';
    end if;

    -- Verify client-reported exercise totals if present
    if v_e ? 'total_sets' and v_count is distinct from (v_e->>'total_sets')::integer then
      raise exception 'Exercise totals do not match snapshot' using errcode = '22023';
    end if;
    if v_e ? 'total_reps' and v_reps is distinct from (v_e->>'total_reps')::integer then
      raise exception 'Exercise totals do not match snapshot' using errcode = '22023';
    end if;
    if v_e ? 'total_volume' and (v_e->>'total_volume')::numeric is not null
       and abs(v_volume - (v_e->>'total_volume')::numeric) > greatest(0.01, abs(v_volume) * 0.00001) then
      raise exception 'Exercise totals do not match snapshot' using errcode = '22023';
    end if;

    v_total_reps := v_total_reps + v_reps;
    v_total_volume := v_total_volume + v_volume;
    v_total_add_vol := v_total_add_vol + v_add_vol;
    v_total_duration := v_total_duration + v_dur;
    v_total_distance := v_total_distance + v_dist;
  end loop;

  -- Verify client-reported workout totals
  if jsonb_array_length(p_sets) is distinct from (p_workout->>'total_sets')::integer
    or v_total_reps is distinct from (p_workout->>'total_reps')::integer
    or (p_workout->>'total_volume')::numeric is null
    or abs(v_total_volume - (p_workout->>'total_volume')::numeric) > greatest(0.01, abs(v_total_volume) * 0.00001) then
    raise exception 'Workout totals do not match snapshot' using errcode = '22023';
  end if;

  -- A mixed aggregate remains unverified if any child is historical; typed
  -- siblings keep their own VERIFIED provenance below.
  v_quality := case when exists (select 1 from jsonb_array_elements(p_exercises) e where not (e ? 'tracking_type'))
    then 'LEGACY_UNVERIFIED' else 'VERIFIED' end;

  insert into public.workout_logs(
    id, user_id, template_id, template_name, date, start_time, end_time,
    total_volume, total_sets, total_reps, total_duration_seconds, total_distance_meters,
    additional_volume, data_quality, created_at, updated_at
  ) values (
    v_id, v_owner, (p_workout->>'template_id')::uuid, p_workout->>'template_name',
    (p_workout->>'date')::date, (p_workout->>'start_time')::timestamptz, (p_workout->>'end_time')::timestamptz,
    v_total_volume, jsonb_array_length(p_sets), v_total_reps, v_total_duration, v_total_distance,
    v_total_add_vol, v_quality, v_now, v_now
  );

  for v_e in select value from jsonb_array_elements(p_exercises) loop
    v_modality := (v_e->>'modality')::public.exercise_modality_enum;
    v_track := coalesce(v_e->>'tracking_type', 'WEIGHT_REPS');

    v_count := 0; v_reps := 0; v_volume := 0; v_add_vol := 0; v_dur := 0; v_dist := 0;
    for v_s in select value from jsonb_array_elements(p_sets) where (value->>'exercise_log_id')::uuid = (v_e->>'id')::uuid loop
      v_count := v_count + 1;
      if v_track = 'WEIGHT_REPS' then
        v_volume := v_volume + ((v_s->>'weight')::numeric * (v_s->>'reps')::integer);
      elsif v_track = 'BODYWEIGHT_REPS' then
        v_add_vol := v_add_vol + (coalesce((v_s->>'additional_weight')::numeric, 0) * (v_s->>'reps')::integer);
      end if;
      if v_track in ('WEIGHT_REPS', 'BODYWEIGHT_REPS', 'ASSISTED_REPS', 'REPS_ONLY') then
        v_reps := v_reps + coalesce((v_s->>'reps')::integer, 0);
      end if;
      if v_track in ('DURATION', 'DISTANCE_DURATION') then
        v_dur := v_dur + coalesce((v_s->>'duration_seconds')::integer, 0);
      end if;
      if v_track = 'DISTANCE_DURATION' then
        v_dist := v_dist + coalesce((v_s->>'distance_meters')::integer, 0);
      end if;
    end loop;

    insert into public.exercise_logs(
      id, workout_log_id, exercise_id, exercise_name, muscle_category,
      order_index, total_volume, total_sets, total_reps, total_duration_seconds,
      total_distance_meters, additional_volume, tracking_type, modality,
      metric_quality, input_defaults, created_at, updated_at
    ) values (
      (v_e->>'id')::uuid, v_id, v_e->>'exercise_id', v_e->>'exercise_name', v_e->>'muscle_category',
      (v_e->>'order_index')::integer, v_volume, v_count, v_reps, v_dur, v_dist, v_add_vol,
      v_track::public.tracking_type_enum, v_modality::public.exercise_modality_enum,
      case when v_e ? 'tracking_type' then 'VERIFIED' else 'LEGACY_UNVERIFIED' end,
      v_e->'input_defaults', v_now, v_now
    );
  end loop;

  for v_s in select value from jsonb_array_elements(p_sets) loop
    insert into public.workout_sets(
      id, exercise_log_id, workout_log_id, exercise_id, set_number,
      weight, reps, duration_seconds, distance_meters, additional_weight,
      assistance_weight, is_completed, created_at, updated_at
    ) values (
      (v_s->>'id')::uuid, (v_s->>'exercise_log_id')::uuid, v_id, v_s->>'exercise_id',
      (v_s->>'set_number')::integer,
      coalesce((v_s->>'weight')::numeric, 0),
      coalesce((v_s->>'reps')::integer, 0),
      (v_s->>'duration_seconds')::integer,
      (v_s->>'distance_meters')::integer,
      (v_s->>'additional_weight')::numeric,
      (v_s->>'assistance_weight')::numeric,
      true, v_now, v_now
    );
  end loop;

  v_result := jsonb_build_object(
    'workout_id', v_id,
    'exercise_count', jsonb_array_length(p_exercises),
    'set_count', jsonb_array_length(p_sets),
    'server_updated_at', v_now
  );
  insert into imfit_private.local_workout_receipts(workout_id, user_id, payload, result)
    values(v_id, v_owner, v_payload, v_result);
  return v_result;

exception when unique_violation or foreign_key_violation or check_violation then
  raise exception 'Invalid snapshot or conflicting identifier' using errcode = 'PT409';
end;
$function$;

CREATE OR REPLACE FUNCTION imfit_private.workout_summary(p_row public.workout_logs)
 RETURNS jsonb
 LANGUAGE sql
 STABLE
 SET search_path TO ''
AS $function$
  select jsonb_build_object(
    'id', p_row.id,
    'template_id', p_row.template_id,
    'template_name', p_row.template_name,
    'start_time', p_row.start_time,
    'end_time', p_row.end_time,
    'duration_seconds', floor(extract(epoch from (p_row.end_time - p_row.start_time)))::bigint,
    'total_volume', p_row.total_volume::text,
    'measurement_version',p_row.measurement_version,'recorded_load_volume',p_row.recorded_load_volume,
    'total_sets', p_row.total_sets,
    'total_reps', p_row.total_reps,
    'total_duration_seconds', coalesce(p_row.total_duration_seconds, 0),
    'total_distance_meters', case when p_row.measurement_version=3 then to_jsonb(p_row.total_distance_meters) else to_jsonb(coalesce(p_row.total_distance_meters,0)::BIGINT) end,
    'additional_volume', coalesce(p_row.additional_volume, 0)::text,
    'data_quality', coalesce(p_row.data_quality, 'LEGACY_UNVERIFIED')
  ) || CASE WHEN p_row.measurement_version=3
    THEN jsonb_build_object('paused_elapsed_ms',p_row.paused_elapsed_ms,'active_elapsed_ms',p_row.active_elapsed_ms)
    ELSE '{}'::jsonb END;
$function$;

CREATE OR REPLACE FUNCTION public.get_workout_detail(p_workout_id uuid)
 RETURNS jsonb
 LANGUAGE plpgsql
 STABLE SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare
  v_owner uuid := imfit_private.require_owner();
  v_result jsonb;
begin
  select imfit_private.workout_summary(w) || jsonb_build_object('exercise_logs', (
    select coalesce(jsonb_agg(jsonb_build_object(
      'id', e.id,
      'exercise_id', e.exercise_id,
      'exercise_name', e.exercise_name,
      'muscle_category', e.muscle_category,
      'order_index', e.order_index,
      'input_defaults', e.input_defaults,
      'total_volume', e.total_volume::text,
      'measurement_version',e.measurement_version,
      'recorded_load_volume',e.recorded_load_volume,
      'total_sets', e.total_sets,
      'total_reps', e.total_reps,
      'tracking_type', case when e.measurement_version=3 then e.tracking_type::text else coalesce(e.tracking_type::text,'WEIGHT_REPS') end,
      'modality', e.modality::text,
      'total_duration_seconds', coalesce(e.total_duration_seconds, 0),
      'total_distance_meters', case when e.measurement_version=3 then to_jsonb(e.total_distance_meters) else to_jsonb(coalesce(e.total_distance_meters,0)::BIGINT) end,
      'additional_volume', coalesce(e.additional_volume, 0)::text,
      'metric_quality', coalesce(e.metric_quality, 'LEGACY_UNVERIFIED'),
      'sets', (
        select coalesce(jsonb_agg(jsonb_build_object(
          'id', s.id,
          'set_number', s.set_number,
          'measurement_version',s.measurement_version,'target_row_id',s.target_row_id,
          'calories_kcal',s.calories_kcal,'speed_kph',s.speed_kph,'lap_reps',s.lap_reps,'incline_percent',s.incline_percent,
          'is_warmup',s.is_warmup,
          'weight', case when s.measurement_version=3 then to_jsonb(s.weight) else to_jsonb(s.weight::text) end,
          'reps', s.reps,
          'duration_seconds', s.duration_seconds,
          'distance_meters', case when s.measurement_version=3 then to_jsonb(s.distance_meters) else to_jsonb(s.distance_meters::INTEGER) end,
          'additional_weight', case when s.additional_weight is not null then s.additional_weight::text end,
          'assistance_weight', case when s.assistance_weight is not null then s.assistance_weight::text end,
          'is_completed', s.is_completed
        ) || CASE WHEN s.measurement_version=3
          THEN jsonb_build_object('rest_elapsed_ms',s.rest_elapsed_ms,'rest_end_reason',s.rest_end_reason)
          ELSE '{}'::jsonb END order by s.set_number, s.id), '[]'::jsonb)
        from public.workout_sets s
        where s.exercise_log_id = e.id and (s.is_completed or s.measurement_version=3)
      )
    ) order by e.order_index, e.id), '[]'::jsonb)
    from public.exercise_logs e
    where e.workout_log_id = w.id
  ))
  into v_result
  from public.workout_logs w
  where w.id = p_workout_id and w.user_id = v_owner and w.deleted_at is null;

  if not found then perform imfit_private.fail('PT404', 'RESOURCE_NOT_FOUND'); end if;
  return v_result;
end;
$function$;
NOTIFY pgrst,'reload schema';

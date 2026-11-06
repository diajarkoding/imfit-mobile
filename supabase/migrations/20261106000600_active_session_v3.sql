-- T08: versioned session snapshots, results and finalization. Legacy rows untouched.
SET LOCAL lock_timeout='8s';
SET LOCAL statement_timeout='120s';
ALTER TABLE public.active_sessions DROP CONSTRAINT active_sessions_schema_version_check;
ALTER TABLE public.active_sessions ADD CONSTRAINT active_sessions_schema_version_check CHECK(schema_version IN (1,2,3));
ALTER TABLE public.workout_logs
  ADD COLUMN measurement_version SMALLINT NULL CHECK(measurement_version IS NULL OR measurement_version=3),
  ADD COLUMN recorded_load_volume NUMERIC(12,2) NULL CHECK(recorded_load_volume>=0 AND recorded_load_volume<>'NaN'::NUMERIC),
  ALTER COLUMN total_distance_meters TYPE NUMERIC(16,2),
  ALTER COLUMN total_distance_meters DROP NOT NULL;
ALTER TABLE public.exercise_logs
  ADD COLUMN measurement_version SMALLINT NULL CHECK(measurement_version IS NULL OR measurement_version=3),
  ADD COLUMN recorded_load_volume NUMERIC(12,2) NULL CHECK(recorded_load_volume>=0 AND recorded_load_volume<>'NaN'::NUMERIC),
  ALTER COLUMN total_distance_meters TYPE NUMERIC(16,2),
  ALTER COLUMN total_distance_meters DROP NOT NULL;

CREATE FUNCTION imfit_private.v3_number(p_value JSONB,p_min NUMERIC,p_max NUMERIC,p_integer BOOLEAN,p_nullable BOOLEAN)
RETURNS NUMERIC LANGUAGE plpgsql IMMUTABLE SET search_path='' AS $function$
DECLARE n NUMERIC;
BEGIN
  IF p_value IS NULL OR p_value='null'::JSONB THEN
    IF p_nullable THEN RETURN NULL; END IF;
    PERFORM imfit_private.fail('PT400','VALIDATION');
  END IF;
  IF jsonb_typeof(p_value) IS DISTINCT FROM 'number' THEN PERFORM imfit_private.fail('PT400','VALIDATION'); END IF;
  n:=(p_value#>>'{}')::NUMERIC;
  IF n NOT BETWEEN p_min AND p_max OR n<>trunc(n,CASE WHEN p_integer THEN 0 ELSE 2 END) THEN
    PERFORM imfit_private.fail('PT400','VALIDATION');
  END IF;
  RETURN n;
END;
$function$;

-- Canonical configuration occurs once per exercise, in input_defaults. Both target
-- lists use their own contiguous set_number sequence; result association uses row_id.
CREATE FUNCTION imfit_private.validate_snapshot_v3(p_snapshot JSONB)
RETURNS VOID LANGUAGE plpgsql IMMUTABLE SET search_path='' AS $function$
DECLARE metrics TEXT[]; profile TEXT; mode TEXT; modality TEXT; extra JSONB;
BEGIN
  IF jsonb_typeof(p_snapshot) IS DISTINCT FROM 'object'
     OR NOT(p_snapshot ?& ARRAY['schema_version','measurement_version','modality','tracking_type','tracking_profile','supported_metrics','set_targets','extra_set_targets'])
     OR (p_snapshot-ARRAY['schema_version','measurement_version','modality','tracking_type','tracking_profile','supported_metrics','set_targets','extra_set_targets'])<>'{}'::JSONB
     OR p_snapshot->'schema_version' IS DISTINCT FROM '3'::JSONB
     OR p_snapshot->'measurement_version' IS DISTINCT FROM '3'::JSONB
     OR jsonb_typeof(p_snapshot->'supported_metrics') IS DISTINCT FROM 'array' THEN
    PERFORM imfit_private.fail('PT400','VALIDATION');
  END IF;
  IF EXISTS(SELECT 1 FROM jsonb_array_elements(p_snapshot->'supported_metrics') m WHERE jsonb_typeof(m)<>'string') THEN
    PERFORM imfit_private.fail('PT400','VALIDATION');
  END IF;
  metrics:=ARRAY(SELECT jsonb_array_elements_text(p_snapshot->'supported_metrics'));
  profile:=p_snapshot->>'tracking_profile'; mode:=p_snapshot->>'tracking_type'; modality:=p_snapshot->>'modality';
  IF NOT imfit_private.exercise_supported_metrics_valid(metrics) OR NOT coalesce(CASE modality
    WHEN 'STRENGTH' THEN mode='WEIGHT_REPS' AND profile IS NULL AND cardinality(metrics)=0
    WHEN 'ISOMETRIC' THEN mode='DURATION' AND profile IS NULL AND cardinality(metrics)=0
    WHEN 'CONDITIONING' THEN mode IN ('WEIGHT_REPS','DURATION') AND profile IS NULL AND cardinality(metrics)=0
    WHEN 'CARDIO' THEN mode='DURATION' AND CASE profile
      WHEN 'duration_only' THEN cardinality(metrics)=0
      WHEN 'distance_activity' THEN metrics<@ARRAY['distance','speed','laps']::TEXT[]
      WHEN 'equipment_metrics' THEN metrics<@ARRAY['calories','distance','speed','laps','incline']::TEXT[]
      ELSE FALSE END
    ELSE FALSE END,FALSE) THEN
    PERFORM imfit_private.fail('PT400','VALIDATION');
  END IF;
  BEGIN
    PERFORM imfit_private.validate_set_targets_v3(p_snapshot->'set_targets',mode);
    extra:=p_snapshot->'extra_set_targets';
    IF jsonb_typeof(extra) IS DISTINCT FROM 'array' THEN PERFORM imfit_private.fail('PT400','VALIDATION'); END IF;
    IF jsonb_array_length(extra)>0 THEN PERFORM imfit_private.validate_set_targets_v3(extra,mode); END IF;
  EXCEPTION WHEN invalid_parameter_value OR numeric_value_out_of_range THEN PERFORM imfit_private.fail('PT400','VALIDATION'); END;
  IF EXISTS(SELECT 1 FROM jsonb_array_elements((p_snapshot->'set_targets')||extra) t
    GROUP BY t->>'row_id' HAVING count(*)>1) THEN PERFORM imfit_private.fail('PT400','VALIDATION'); END IF;
END;
$function$;

CREATE FUNCTION imfit_private.validate_result_v3(p_snapshot JSONB,p_set JSONB,p_completed BOOLEAN)
RETURNS VOID LANGUAGE plpgsql IMMUTABLE SET search_path='' AS $function$
DECLARE key TEXT; metric TEXT; n NUMERIC; mode TEXT:=p_snapshot->>'tracking_type';
BEGIN
  IF jsonb_typeof(p_set) IS DISTINCT FROM 'object'
     OR (p_set-ARRAY['id','exercise_log_id','workout_log_id','exercise_id','set_number','measurement_version','target_row_id',
       'weight','reps','duration_seconds','distance_meters','calories_kcal','speed_kph','lap_reps','incline_percent',
       'additional_weight','assistance_weight','is_completed','is_warmup'])<>'{}'::JSONB
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
END;
$function$;

CREATE FUNCTION imfit_private.session_exercise_v3(p_id UUID,p_exercise TEXT,p_order INTEGER,p_targets JSONB)
RETURNS JSONB LANGUAGE plpgsql SET search_path='' AS $function$
DECLARE ex RECORD; config JSONB; results JSONB;
BEGIN
  SELECT e.*,c.display_name AS category INTO ex FROM public.exercises e
    JOIN public.muscle_categories c ON c.id=e.muscle_category_id WHERE e.id=p_exercise AND e.is_active;
  IF NOT FOUND THEN PERFORM imfit_private.fail('PT400','VALIDATION'); END IF;
  config:=jsonb_build_object('schema_version',3,'measurement_version',3,'modality',ex.modality,
    'tracking_type',ex.default_tracking_type,'tracking_profile',ex.tracking_profile,'supported_metrics',ex.supported_metrics,
    'set_targets',p_targets,'extra_set_targets','[]'::JSONB);
  PERFORM imfit_private.validate_snapshot_v3(config);
  SELECT jsonb_agg(jsonb_build_object('id',gen_random_uuid(),'measurement_version',3,'target_row_id',t->>'row_id',
    'set_number',ord,'weight',t->'weight','reps',t->'reps','duration_seconds',t->'duration_seconds',
    'distance_meters',NULL,'calories_kcal',NULL,'speed_kph',NULL,'lap_reps',NULL,'incline_percent',NULL,
    'is_completed',FALSE,'is_warmup',FALSE) ORDER BY ord) INTO results
    FROM jsonb_array_elements(p_targets) WITH ORDINALITY a(t,ord);
  RETURN jsonb_build_object('id',p_id,'exercise_id',ex.id,'name',ex.name,'muscle_category',ex.category,
    'order_index',p_order,'measurement_version',3,'tracking_type',ex.default_tracking_type,'modality',ex.modality,
    'tracking_profile',ex.tracking_profile,'supported_metrics',ex.supported_metrics,'input_defaults',config,
    'sets',results,'max_set_number',jsonb_array_length(results));
END;
$function$;

REVOKE ALL ON FUNCTION imfit_private.v3_number(JSONB,NUMERIC,NUMERIC,BOOLEAN,BOOLEAN),
  imfit_private.validate_snapshot_v3(JSONB),imfit_private.validate_result_v3(JSONB,JSONB,BOOLEAN),
  imfit_private.session_exercise_v3(UUID,TEXT,INTEGER,JSONB) FROM PUBLIC,anon,authenticated,service_role;

-- Shared by server-session finish (T08) and immutable local aggregate upload (T09).
-- Callers own their respective receipt lock/lookup. This function is insert-only.
CREATE FUNCTION imfit_private.insert_workout_v3(p_owner UUID,p_workout JSONB,p_exercises JSONB,p_sets JSONB)
RETURNS JSONB LANGUAGE plpgsql SET search_path='' AS $function$
DECLARE
  wid UUID; e JSONB; s JSONB; snap JSONB; eid UUID; now_at TIMESTAMPTZ:=statement_timestamp();
  total_load NUMERIC:=0; total_reps INTEGER:=0; total_duration INTEGER:=0; total_distance NUMERIC;
  load NUMERIC; reps INTEGER; duration INTEGER; distance NUMERIC; set_count INTEGER; mode TEXT;
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
  INSERT INTO public.workout_logs(id,user_id,template_id,template_name,date,start_time,end_time,
    total_volume,total_sets,total_reps,total_duration_seconds,total_distance_meters,additional_volume,data_quality,
    measurement_version,recorded_load_volume,source_session_id,created_at,updated_at)
  VALUES(wid,p_owner,(p_workout->>'template_id')::UUID,p_workout->>'template_name',(p_workout->>'date')::DATE,
    (p_workout->>'start_time')::TIMESTAMPTZ,(p_workout->>'end_time')::TIMESTAMPTZ,
    0,jsonb_array_length(p_sets),total_reps,total_duration,total_distance,0,'VERIFIED',3,total_load,
    (p_workout->>'source_session_id')::UUID,now_at,now_at);
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
      measurement_version,target_row_id,calories_kcal,speed_kph,lap_reps,incline_percent,created_at,updated_at)
    VALUES((s->>'id')::UUID,(s->>'exercise_log_id')::UUID,wid,s->>'exercise_id',(s->>'set_number')::INTEGER,
      (s->>'weight')::NUMERIC,(s->>'reps')::INTEGER,(s->>'duration_seconds')::INTEGER,(s->>'distance_meters')::NUMERIC,
      NULL,NULL,TRUE,coalesce((s->>'is_warmup')::BOOLEAN,FALSE),3,s->>'target_row_id',
      (s->>'calories_kcal')::NUMERIC,(s->>'speed_kph')::NUMERIC,(s->>'lap_reps')::INTEGER,
      (s->>'incline_percent')::NUMERIC,now_at,now_at);
  END LOOP;
  RETURN jsonb_build_object('workout_id',wid,'exercise_count',jsonb_array_length(p_exercises),
    'set_count',jsonb_array_length(p_sets),'measurement_version',3,'recorded_load_volume',total_load,'server_updated_at',now_at);
END;
$function$;
REVOKE ALL ON FUNCTION imfit_private.insert_workout_v3(UUID,JSONB,JSONB,JSONB) FROM PUBLIC,anon,authenticated,service_role;

CREATE FUNCTION imfit_private.apply_session_command_v3(p_data JSONB,p_command JSONB)
RETURNS JSONB LANGUAGE plpgsql SET search_path='' AS $function$
DECLARE
  data JSONB:=p_data; exercises JSONB:=p_data->'exercises'; ex JSONB; snap JSONB; sets JSONB; result JSONB;
  targets JSONB; extra JSONB; target JSONB; eid UUID; sid UUID; epos INTEGER; spos INTEGER;
  kind TEXT:=p_command->>'type'; key TEXT; patch_value JSONB; target_id TEXT;
BEGIN
  IF data->'schema_version' IS DISTINCT FROM '3'::JSONB OR jsonb_typeof(exercises) IS DISTINCT FROM 'array'
     OR jsonb_typeof(data->'used_exercise_ids') IS DISTINCT FROM 'array'
     OR jsonb_typeof(data->'used_set_ids') IS DISTINCT FROM 'array' THEN PERFORM imfit_private.fail('PT400','VALIDATION'); END IF;
  eid:=imfit_private.json_uuid(p_command->'exercise_log_id');
  SELECT (ord-1)::INTEGER,e INTO epos,ex FROM jsonb_array_elements(exercises) WITH ORDINALITY a(e,ord) WHERE e->>'id'=eid::TEXT;
  IF kind='add_exercise' THEN
    IF epos IS NOT NULL OR data->'used_exercise_ids' ? eid::TEXT OR jsonb_array_length(exercises)>=100
       OR EXISTS(SELECT 1 FROM jsonb_array_elements(exercises) e WHERE e->>'exercise_id'=p_command->>'exercise_id') THEN
      PERFORM imfit_private.fail('PT400','VALIDATION');
    END IF;
    -- A new v3 exercise must bring an explicit canonical prescription. Do not
    -- fabricate cardio duration or reinterpret scalar legacy defaults.
    ex:=imfit_private.session_exercise_v3(eid,p_command->>'exercise_id',jsonb_array_length(exercises),p_command->'set_targets');
    exercises:=exercises||jsonb_build_array(ex);
    data:=jsonb_set(data,'{used_exercise_ids}',(data->'used_exercise_ids')||to_jsonb(eid::TEXT));
    data:=jsonb_set(data,'{used_set_ids}',(data->'used_set_ids')||(SELECT jsonb_agg(s->'id') FROM jsonb_array_elements(ex->'sets') s));
    IF jsonb_array_length(exercises)=1 THEN data:=jsonb_set(data,'{current_exercise_id}',to_jsonb(eid::TEXT)); END IF;
  ELSE
    IF epos IS NULL THEN PERFORM imfit_private.fail('PT404','RESOURCE_NOT_FOUND'); END IF;
    snap:=ex->'input_defaults'; PERFORM imfit_private.validate_snapshot_v3(snap);
    sets:=ex->'sets'; extra:=snap->'extra_set_targets';
    IF kind='remove_exercise' THEN
      IF EXISTS(SELECT 1 FROM jsonb_array_elements(sets) s WHERE s->'is_completed'='true'::JSONB) THEN
        PERFORM imfit_private.fail('PT409','STATE_CONFLICT');
      END IF;
      exercises:=exercises-epos;
      SELECT coalesce(jsonb_agg(jsonb_set(e,'{order_index}',to_jsonb(ord-1)) ORDER BY ord),'[]'::JSONB)
        INTO exercises FROM jsonb_array_elements(exercises) WITH ORDINALITY a(e,ord);
      IF data->>'current_exercise_id'=eid::TEXT THEN data:=jsonb_set(data,'{current_exercise_id}',coalesce(exercises#>'{0,id}','null'::JSONB)); END IF;
    ELSIF kind='select_exercise' THEN
      data:=jsonb_set(data,'{current_exercise_id}',to_jsonb(eid::TEXT));
    ELSIF kind='set_rest_seconds' THEN
      PERFORM imfit_private.fail('PT400','READ_ONLY_TARGET_REST');
    ELSE
      sid:=imfit_private.json_uuid(p_command->'set_id');
      SELECT (ord-1)::INTEGER,s INTO spos,result FROM jsonb_array_elements(sets) WITH ORDINALITY a(s,ord) WHERE s->>'id'=sid::TEXT;
      IF kind='add_set' THEN
        IF spos IS NOT NULL OR data->'used_set_ids' ? sid::TEXT OR jsonb_array_length(sets)>=20 THEN
          PERFORM imfit_private.fail('PT400','VALIDATION');
        END IF;
        target_id:=coalesce(p_command->>'target_row_id',sid::TEXT);
        IF p_command ? 'target_row_id' AND jsonb_typeof(p_command->'target_row_id') IS DISTINCT FROM 'string' THEN
          PERFORM imfit_private.fail('PT400','VALIDATION');
        END IF;
        IF nullif(btrim(target_id),'') IS NULL OR EXISTS(
          SELECT 1 FROM jsonb_array_elements((snap->'set_targets')||extra) t WHERE t->>'row_id'=target_id
        ) THEN PERFORM imfit_private.fail('PT400','VALIDATION'); END IF;
        target:=(snap->'set_targets'->0)||jsonb_build_object('row_id',target_id,'set_number',jsonb_array_length(extra)+1,'rest_seconds',60);
        extra:=extra||jsonb_build_array(target);
        snap:=jsonb_set(snap,'{extra_set_targets}',extra);
        result:=jsonb_build_object('id',sid,'measurement_version',3,'target_row_id',target_id,'set_number',jsonb_array_length(sets)+1,
          'weight',target->'weight','reps',target->'reps','duration_seconds',target->'duration_seconds',
          'distance_meters',NULL,'calories_kcal',NULL,'speed_kph',NULL,'lap_reps',NULL,'incline_percent',NULL,
          'is_completed',FALSE,'is_warmup',FALSE);
        sets:=sets||jsonb_build_array(result);
        data:=jsonb_set(data,'{used_set_ids}',(data->'used_set_ids')||to_jsonb(sid::TEXT));
      ELSE
        IF spos IS NULL THEN PERFORM imfit_private.fail('PT404','RESOURCE_NOT_FOUND'); END IF;
        IF kind IN ('edit_set','remove_set') AND result->'is_completed'='true'::JSONB THEN
          PERFORM imfit_private.fail('PT409','STATE_CONFLICT');
        END IF;
        IF kind='edit_set' THEN
          IF p_command ?| ARRAY['additional_weight','assistance_weight'] THEN PERFORM imfit_private.fail('PT400','VALIDATION'); END IF;
          FOREACH key IN ARRAY ARRAY['weight','reps','duration_seconds','distance_meters','calories_kcal','speed_kph','lap_reps','incline_percent'] LOOP
            IF p_command ? key THEN
              patch_value:=p_command->key;
              -- Preserve the existing weight command normalization/receipt shape;
              -- v3 stored measurements themselves are JSON numbers, never strings.
              IF key='weight' AND patch_value<>'null'::JSONB THEN
                BEGIN patch_value:=to_jsonb((p_command->>'weight')::NUMERIC);
                EXCEPTION WHEN invalid_text_representation OR numeric_value_out_of_range THEN PERFORM imfit_private.fail('PT400','VALIDATION'); END;
              END IF;
              result:=jsonb_set(result,ARRAY[key],patch_value);
            END IF;
          END LOOP;
          PERFORM imfit_private.validate_result_v3(snap,result,FALSE);
        ELSIF kind='complete_set' THEN
          PERFORM imfit_private.validate_result_v3(snap,result,TRUE);
          result:=jsonb_set(result,'{is_completed}','true');
        ELSIF kind='uncomplete_set' THEN
          result:=jsonb_set(result,'{is_completed}','false');
        ELSIF kind='remove_set' THEN
          IF jsonb_array_length(sets)<=1 THEN PERFORM imfit_private.fail('PT409','STATE_CONFLICT'); END IF;
        ELSE PERFORM imfit_private.fail('PT400','VALIDATION');
        END IF;
        IF kind='remove_set' THEN
          sets:=sets-spos;
          SELECT coalesce(jsonb_agg(t ORDER BY ord),'[]'::JSONB) INTO extra
            FROM jsonb_array_elements(extra) WITH ORDINALITY a(t,ord)
            WHERE EXISTS(SELECT 1 FROM jsonb_array_elements(sets) s WHERE s->>'target_row_id'=t->>'row_id');
          SELECT coalesce(jsonb_agg(jsonb_set(t,'{set_number}',to_jsonb(ord)) ORDER BY ord),'[]'::JSONB) INTO extra
            FROM jsonb_array_elements(extra) WITH ORDINALITY a(t,ord);
          snap:=jsonb_set(snap,'{extra_set_targets}',extra);
        ELSE sets:=jsonb_set(sets,ARRAY[spos::TEXT],result);
        END IF;
      END IF;
      SELECT jsonb_agg(jsonb_set(s,'{set_number}',to_jsonb(ord)) ORDER BY ord) INTO sets
        FROM jsonb_array_elements(sets) WITH ORDINALITY a(s,ord);
      ex:=ex||jsonb_build_object('sets',sets,'input_defaults',snap,'max_set_number',jsonb_array_length(sets));
      exercises:=jsonb_set(exercises,ARRAY[epos::TEXT],ex);
    END IF;
  END IF;
  FOR ex IN SELECT value FROM jsonb_array_elements(exercises) LOOP
    snap:=ex->'input_defaults'; PERFORM imfit_private.validate_snapshot_v3(snap); sets:=ex->'sets';
    IF ex->'measurement_version' IS DISTINCT FROM '3'::JSONB
       OR ex->'tracking_type' IS DISTINCT FROM snap->'tracking_type' OR ex->'modality' IS DISTINCT FROM snap->'modality'
       OR ex->'tracking_profile' IS DISTINCT FROM snap->'tracking_profile' OR ex->'supported_metrics' IS DISTINCT FROM snap->'supported_metrics'
       OR jsonb_array_length(sets) NOT BETWEEN 1 AND 20
       OR (SELECT count(DISTINCT s->>'target_row_id') FROM jsonb_array_elements(sets) s)<>jsonb_array_length(sets) THEN
      PERFORM imfit_private.fail('PT400','VALIDATION');
    END IF;
    FOR result IN SELECT value FROM jsonb_array_elements(sets) LOOP
      PERFORM imfit_private.validate_result_v3(snap,result,(result->>'is_completed')::BOOLEAN);
    END LOOP;
  END LOOP;
  IF (SELECT count(*) FROM jsonb_array_elements(exercises) e CROSS JOIN LATERAL jsonb_array_elements(e->'sets') s)
     <> (SELECT count(DISTINCT s->>'id') FROM jsonb_array_elements(exercises) e CROSS JOIN LATERAL jsonb_array_elements(e->'sets') s) THEN
    PERFORM imfit_private.fail('PT400','VALIDATION');
  END IF;
  RETURN jsonb_set(data,'{exercises}',exercises);
END;
$function$;
REVOKE ALL ON FUNCTION imfit_private.apply_session_command_v3(JSONB,JSONB) FROM PUBLIC,anon,authenticated,service_role;

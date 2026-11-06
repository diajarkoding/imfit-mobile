-- T10: versioned targets/read-back and completed-only, non-mixed metric series.
SET LOCAL lock_timeout='8s';
SET LOCAL statement_timeout='120s';

CREATE OR REPLACE FUNCTION imfit_private.prefill(p_owner uuid, p_exercise text, p_defaults jsonb)
 RETURNS jsonb
 LANGUAGE plpgsql
 STABLE
 SET search_path TO ''
AS $function$
declare
  v_sets integer;
  v_reps integer;
  v_log public.exercise_logs%rowtype;
  v_rows jsonb;
  v_source text := 'DEFAULTS';
  v_workout uuid;
  v_track public.tracking_type_enum;
  v_allowed_keys text[];
begin
  if p_defaults ? 'schema_version' or p_defaults ? 'measurement_version' then
    if p_exercise is null or not exists(select 1 from public.exercises where id=p_exercise and is_active) then
      perform imfit_private.fail('PT400','VALIDATION');
    end if;
    perform imfit_private.validate_snapshot_v3(p_defaults);
    select jsonb_agg(jsonb_build_object('measurement_version',3,'target_row_id',t->'row_id',
      'set_number',t->'set_number','weight',t->'weight','reps',t->'reps','duration_seconds',t->'duration_seconds',
      'distance_meters',NULL,'calories_kcal',NULL,'speed_kph',NULL,'lap_reps',NULL,'incline_percent',NULL)
      order by ord) into v_rows from jsonb_array_elements(p_defaults->'set_targets') with ordinality a(t,ord);
    return jsonb_build_object('source','TARGETS','source_workout_id',NULL,'input_defaults',p_defaults,'sets',v_rows);
  end if;
  if p_exercise is null or not exists(select 1 from public.exercises where id = p_exercise and is_active)
     or jsonb_typeof(p_defaults) is distinct from 'object' then
    perform imfit_private.fail('PT400', 'VALIDATION');
  end if;

  v_allowed_keys := array['sets', 'reps', 'tracking_type', 'target_duration_seconds', 'target_distance_meters'];
  if (p_defaults - v_allowed_keys) <> '{}'::jsonb then
    perform imfit_private.fail('PT400', 'VALIDATION');
  end if;

  v_sets := imfit_private.json_int(coalesce(p_defaults->'sets', '3'::jsonb), 1, 20);
  v_reps := imfit_private.json_int(coalesce(p_defaults->'reps', '10'::jsonb), 1, 100);

  v_track := case when p_defaults ? 'tracking_type' and p_defaults->>'tracking_type' is not null
    then (p_defaults->>'tracking_type')::public.tracking_type_enum
    else (select default_tracking_type from public.exercises where id = p_exercise) end;

  select w.id into v_workout from public.workout_logs w
  where w.user_id = p_owner and w.deleted_at is null and exists (
    select 1 from public.exercise_logs e join public.workout_sets s on s.exercise_log_id = e.id
    where e.workout_log_id = w.id and e.exercise_id = p_exercise and s.is_completed
      and coalesce(e.tracking_type, 'WEIGHT_REPS'::public.tracking_type_enum) = v_track
      and e.measurement_version is null
  )
  order by w.end_time desc, w.id desc limit 1;

  if v_workout is not null then
    if (select count(*) from public.exercise_logs where workout_log_id = v_workout and exercise_id = p_exercise) = 1 then
      select * into v_log from public.exercise_logs where workout_log_id = v_workout and exercise_id = p_exercise;
      -- Equal scalar defaults cannot make incompatible historical formats match.
      if coalesce(v_log.tracking_type, 'WEIGHT_REPS'::public.tracking_type_enum) = v_track
         and (v_log.input_defaults = p_defaults
              or (v_log.tracking_type is not null and v_log.tracking_type = v_track)) then
        v_source := 'HISTORY';
      end if;
    end if;
  end if;

  select jsonb_agg(jsonb_build_object(
    'set_number', n,
    'weight', case when s.id is null then '0.00' else to_char(coalesce(s.weight, 0), 'FM9990.00') end,
    'reps', coalesce(s.reps, v_reps),
    'duration_seconds', s.duration_seconds,
    'distance_meters', s.distance_meters::INTEGER,
    'additional_weight', case when s.additional_weight is not null then to_char(s.additional_weight, 'FM9990.00') end,
    'assistance_weight', case when s.assistance_weight is not null then to_char(s.assistance_weight, 'FM9990.00') end
  ) order by n) into v_rows
  from generate_series(1, v_sets) n
  left join public.workout_sets s on v_source = 'HISTORY' and s.exercise_log_id = v_log.id
    and s.set_number = n and s.is_completed;

  return jsonb_build_object(
    'source', v_source,
    'source_workout_id', case when v_source = 'HISTORY' then v_workout end,
    'input_defaults', p_defaults,
    'sets', v_rows
  );
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
  );
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
        ) order by s.set_number, s.id), '[]'::jsonb)
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

CREATE OR REPLACE FUNCTION public.get_progress_summary(p_start_date DATE,p_end_date DATE,p_timezone TEXT)
RETURNS JSONB LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $function$
DECLARE owner_id UUID:=imfit_private.require_owner(); result JSONB;
BEGIN
  PERFORM imfit_private.validate_window(p_start_date,p_end_date,p_timezone);
  WITH selected AS (
    SELECT w.* FROM public.workout_logs w WHERE w.user_id=owner_id AND w.deleted_at IS NULL
      AND w.start_time >= (p_start_date::TIMESTAMP AT TIME ZONE p_timezone)
      AND w.start_time < (p_end_date::TIMESTAMP AT TIME ZONE p_timezone)
  ), completed AS (
    SELECT w.id AS workout_id,e.modality,case when s.measurement_version=3 then e.tracking_type::TEXT else coalesce(e.tracking_type::TEXT,'WEIGHT_REPS') end AS mode,
      s.measurement_version,s.weight,s.reps,s.duration_seconds,s.distance_meters,s.additional_weight
    FROM selected w JOIN public.exercise_logs e ON e.workout_log_id=w.id
      JOIN public.workout_sets s ON s.exercise_log_id=e.id WHERE s.is_completed
  ), totals AS (
    SELECT count(*) AS sets,
      coalesce(sum(CASE WHEN mode IN ('WEIGHT_REPS','BODYWEIGHT_REPS','ASSISTED_REPS','REPS_ONLY') THEN reps ELSE 0 END),0) AS reps,
      coalesce(sum(CASE WHEN measurement_version IS NULL AND mode='WEIGHT_REPS' THEN weight*reps ELSE 0 END),0) AS legacy_volume,
      coalesce(sum(CASE WHEN measurement_version IS NULL AND mode='BODYWEIGHT_REPS' THEN additional_weight*reps ELSE 0 END),0) AS additional_volume,
      coalesce(sum(CASE WHEN measurement_version=3 AND mode='WEIGHT_REPS' THEN weight*reps ELSE 0 END),0) AS recorded_load,
      coalesce(sum(CASE WHEN modality='ISOMETRIC' AND mode IN ('DURATION','DISTANCE_DURATION') THEN duration_seconds ELSE 0 END),0) AS hold_seconds,
      coalesce(sum(CASE WHEN modality IN ('CARDIO','CONDITIONING') AND mode IN ('DURATION','DISTANCE_DURATION') THEN duration_seconds ELSE 0 END),0) AS cardio_seconds,
      sum(CASE WHEN (measurement_version=3 AND modality='CARDIO' AND mode='DURATION')
        OR (measurement_version IS NULL AND mode='DISTANCE_DURATION') THEN distance_meters END) AS cardio_distance,
      count(*) FILTER(WHERE measurement_version=3 AND modality='CARDIO') AS v3_cardio_count
    FROM completed
  ), performed AS (
    SELECT count(*) AS workouts,coalesce(sum(floor(extract(EPOCH FROM (end_time-start_time)))),0)::BIGINT AS seconds
    FROM selected w WHERE EXISTS(SELECT 1 FROM completed c WHERE c.workout_id=w.id)
  )
  SELECT jsonb_build_object('start_date',p_start_date,'end_date',p_end_date,'timezone',p_timezone,
    'workout_count',p.workouts,'total_sets',t.sets,'total_reps',t.reps,
    'total_volume',t.legacy_volume::TEXT,'external_volume_kg',t.legacy_volume::TEXT,
    'additional_volume_kg',t.additional_volume::TEXT,'recorded_load_volume',t.recorded_load,
    'duration_seconds',p.seconds,'workout_duration_seconds',p.seconds,
    'hold_duration_seconds',t.hold_seconds,'cardio_duration_seconds',t.cardio_seconds,
    'cardio_distance_meters',CASE WHEN t.v3_cardio_count>0 THEN to_jsonb(t.cardio_distance) ELSE to_jsonb(coalesce(t.cardio_distance,0)::BIGINT) END)
  INTO result FROM totals t CROSS JOIN performed p;
  RETURN result;
END;
$function$;

NOTIFY pgrst,'reload schema';

-- The raw Room hydration endpoint is also a reader: preserve integer JSON tokens
-- for legacy Int DTOs after physical numeric widening, while v3 stays decimal.
CREATE OR REPLACE FUNCTION public.get_workout_sync_page(p_after_id UUID DEFAULT NULL,p_limit INTEGER DEFAULT 25)
RETURNS JSONB LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path='' AS $function$
DECLARE v_owner UUID:=auth.uid(); v_rows JSONB; v_more BOOLEAN; v_cursor UUID;
BEGIN
  IF v_owner IS NULL THEN RAISE EXCEPTION 'Authentication required' USING ERRCODE='42501'; END IF;
  IF p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 50 THEN RAISE EXCEPTION 'Invalid page limit' USING ERRCODE='22023'; END IF;
  WITH selected AS MATERIALIZED (
    SELECT w.* FROM public.workout_logs w WHERE w.user_id=v_owner AND (p_after_id IS NULL OR w.id>p_after_id)
    ORDER BY w.id LIMIT p_limit+1
  ), page AS (SELECT * FROM selected ORDER BY id LIMIT p_limit)
  SELECT coalesce(jsonb_agg(jsonb_build_object(
    'workout',to_jsonb(w)||CASE WHEN w.measurement_version IS NULL THEN jsonb_build_object('total_distance_meters',w.total_distance_meters::BIGINT) ELSE '{}'::JSONB END,
    'exercises',(SELECT coalesce(jsonb_agg(to_jsonb(e)||CASE WHEN e.measurement_version IS NULL
      THEN jsonb_build_object('total_distance_meters',e.total_distance_meters::BIGINT) ELSE '{}'::JSONB END ORDER BY e.order_index,e.id),'[]'::JSONB)
      FROM public.exercise_logs e WHERE e.workout_log_id=w.id),
    'sets',(SELECT coalesce(jsonb_agg(to_jsonb(s)||jsonb_build_object('workout_log_id',w.id,'exercise_id',e.exercise_id)
      ||CASE WHEN s.measurement_version IS NULL THEN jsonb_build_object('distance_meters',s.distance_meters::INTEGER) ELSE '{}'::JSONB END
      ORDER BY s.exercise_log_id,s.set_number,s.id),'[]'::JSONB)
      FROM public.workout_sets s JOIN public.exercise_logs e ON e.id=s.exercise_log_id WHERE e.workout_log_id=w.id)
    ) ORDER BY w.id),'[]'::JSONB),(SELECT count(*)>p_limit FROM selected),(SELECT id FROM page ORDER BY id DESC LIMIT 1)
    INTO v_rows,v_more,v_cursor FROM page w;
  RETURN jsonb_build_object('owner_id',v_owner,'items',v_rows,'next_cursor',CASE WHEN v_more THEN v_cursor ELSE NULL END);
END;
$function$;

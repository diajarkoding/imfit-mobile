-- T09: immutable aggregate envelopes; lookup receipt before version/snapshot validation.
SET LOCAL lock_timeout='8s';
SET LOCAL statement_timeout='120s';

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
  if p_workout ? 'measurement_version'
     or exists(select 1 from jsonb_array_elements(p_exercises) e where e ? 'measurement_version'
       or e#>'{input_defaults,schema_version}'='3'::jsonb)
     or exists(select 1 from jsonb_array_elements(p_sets) s where s ? 'measurement_version'
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

NOTIFY pgrst,'reload schema';

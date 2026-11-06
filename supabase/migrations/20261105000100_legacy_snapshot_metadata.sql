-- G02: preserve frozen legacy snapshot metadata through catalog cutover.
-- No historical rows or receipts are rewritten. Receipt lookup deliberately stays
-- before new metadata validation: retries return the original stored JSON result.
-- Existing function identities, grants, ownership checks, CAS and measurements
-- remain in place. Legacy configurations are handled separately by routine RPCs.
SET LOCAL lock_timeout = '8s';
SET LOCAL statement_timeout = '120s';

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


CREATE OR REPLACE FUNCTION imfit_private.terminal_session(
  p_request uuid,
  p_session uuid,
  p_version bigint,
  p_finish boolean
)
 RETURNS jsonb
 LANGUAGE plpgsql
 SET search_path TO ''
AS $function$
declare
  v_owner uuid := imfit_private.require_owner();
  v_payload jsonb;
  v_result jsonb;
  v_operation text := case when p_finish then 'finish_active_session' else 'cancel_active_session' end;
  v_status text := case when p_finish then 'COMPLETED' else 'CANCELLED' end;
  v_row public.active_sessions%rowtype;
  v_now timestamptz := clock_timestamp();
  v_workout uuid;
  v_e jsonb;
  v_s jsonb;
  v_volume numeric := 0;
  v_add_vol numeric := 0;
  v_sets integer := 0;
  v_reps integer := 0;
  v_duration integer := 0;
  v_distance integer := 0;
  v_e_volume numeric;
  v_e_add_vol numeric;
  v_e_sets integer;
  v_e_reps integer;
  v_e_duration integer;
  v_e_distance integer;
  v_track text;
  v_modality text;
  v_quality text;
begin
  v_payload := jsonb_build_object('expected_version', p_version);
  v_result := imfit_private.request_replay(v_owner, p_request, v_operation, p_session, v_payload);
  if v_result is not null then return v_result; end if;
  if p_version is null or p_version < 1 then perform imfit_private.fail('PT400', 'VALIDATION'); end if;

  select * into v_row from public.active_sessions where id = p_session and user_id = v_owner for update;
  if not found then perform imfit_private.fail('PT404', 'RESOURCE_NOT_FOUND'); end if;
  if v_row.version is null or v_row.schema_version is null then perform imfit_private.fail('PT409', 'LEGACY_REVIEW_REQUIRED'); end if;

  if v_row.status <> v_status then
    if v_row.status in ('COMPLETED', 'CANCELLED') then
      perform imfit_private.fail('PT409', 'SESSION_TERMINAL', jsonb_build_object(
        'terminal_status', v_row.status, 'workout_id', v_row.completed_workout_id));
    end if;
    if v_row.status = 'EXPIRED' or v_row.expires_at <= v_now then perform imfit_private.fail('PT409', 'SESSION_REVIEW_REQUIRED'); end if;
    if v_row.version <> p_version then perform imfit_private.fail('PT409', 'VERSION_CONFLICT', jsonb_build_object('current_version', v_row.version)); end if;

    if p_finish then
      -- Verify there is at least one completed set
      select count(*)::integer into v_sets
      from jsonb_array_elements(v_row.session_data->'exercises') e,
           lateral jsonb_array_elements(e->'sets') s
      where (s->>'is_completed')::boolean;

      if v_sets = 0 then perform imfit_private.fail('PT400', 'VALIDATION'); end if;

      -- Validate each completed set against its tracking type
      for v_e in select value from jsonb_array_elements(v_row.session_data->'exercises') loop
        -- Validate explicit metadata before the shared validator's legacy fallback.
        -- Neither allowed catalog modes nor today's modality describe this session.
        if v_e ? 'tracking_type' and (
          jsonb_typeof(v_e->'tracking_type') is distinct from 'string'
          or v_e->>'tracking_type' not in (
            'WEIGHT_REPS', 'BODYWEIGHT_REPS', 'ASSISTED_REPS',
            'REPS_ONLY', 'DURATION', 'DISTANCE_DURATION'
          )
        ) then
          perform imfit_private.fail('PT400', 'VALIDATION');
        end if;
        if v_e->>'modality' is not null and (
          jsonb_typeof(v_e->'modality') is distinct from 'string'
          or v_e->>'modality' not in (
            'STRENGTH', 'CALISTHENICS', 'ISOMETRIC', 'CARDIO', 'CONDITIONING'
          )
        ) then
          perform imfit_private.fail('PT400', 'VALIDATION');
        end if;
        v_track := coalesce(v_e->>'tracking_type', 'WEIGHT_REPS');
        for v_s in select value from jsonb_array_elements(v_e->'sets') where (value->>'is_completed')::boolean loop
          perform imfit_private.validate_set_measurement(v_track, v_s, true);
        end loop;
      end loop;

      v_workout := md5(p_session::text || ':workout')::uuid;
      v_quality := case when v_row.schema_version >= 2 and not exists (
        select 1 from jsonb_array_elements(v_row.session_data->'exercises') e
        where not (e ? 'tracking_type')
      ) then 'VERIFIED' else 'LEGACY_UNVERIFIED' end;

      -- Pre-calculate workout-level aggregates across all completed sets
      v_volume := 0; v_add_vol := 0; v_reps := 0; v_duration := 0; v_distance := 0;
      for v_e in select value from jsonb_array_elements(v_row.session_data->'exercises') loop
        v_track := coalesce(v_e->>'tracking_type', 'WEIGHT_REPS');
        for v_s in select value from jsonb_array_elements(v_e->'sets') where (value->>'is_completed')::boolean loop
          if v_track = 'WEIGHT_REPS' then
            v_volume := v_volume + ((v_s->>'weight')::numeric * (v_s->>'reps')::integer);
          elsif v_track = 'BODYWEIGHT_REPS' then
            v_add_vol := v_add_vol + (coalesce((v_s->>'additional_weight')::numeric, 0) * (v_s->>'reps')::integer);
          end if;
          if v_track in ('WEIGHT_REPS', 'BODYWEIGHT_REPS', 'ASSISTED_REPS', 'REPS_ONLY') then
            v_reps := v_reps + coalesce((v_s->>'reps')::integer, 0);
          end if;
          if v_track in ('DURATION', 'DISTANCE_DURATION') then
            v_duration := v_duration + coalesce((v_s->>'duration_seconds')::integer, 0);
          end if;
          if v_track = 'DISTANCE_DURATION' then
            v_distance := v_distance + coalesce((v_s->>'distance_meters')::integer, 0);
          end if;
        end loop;
      end loop;

      insert into public.workout_logs(
        id, user_id, template_id, template_name, date, start_time, end_time,
        total_volume, total_sets, total_reps, total_duration_seconds, total_distance_meters,
        additional_volume, data_quality, source_session_id
      ) values (
        v_workout, v_owner, v_row.template_id, v_row.template_name, (v_row.start_time at time zone 'UTC')::date,
        v_row.start_time, greatest(v_now, v_row.start_time + interval '1 microsecond'),
        v_volume, v_sets, v_reps, v_duration, v_distance, v_add_vol, v_quality, p_session
      );

      for v_e in select value from jsonb_array_elements(v_row.session_data->'exercises') loop
        if not exists (select 1 from jsonb_array_elements(v_e->'sets') s where (s->>'is_completed')::boolean) then
          continue;
        end if;

        v_track := coalesce(v_e->>'tracking_type', 'WEIGHT_REPS');
        v_modality := v_e->>'modality';

        v_e_volume := 0; v_e_add_vol := 0; v_e_sets := 0; v_e_reps := 0; v_e_duration := 0; v_e_distance := 0;
        for v_s in select value from jsonb_array_elements(v_e->'sets') where (value->>'is_completed')::boolean loop
          v_e_sets := v_e_sets + 1;
          if v_track = 'WEIGHT_REPS' then
            v_e_volume := v_e_volume + ((v_s->>'weight')::numeric * (v_s->>'reps')::integer);
          elsif v_track = 'BODYWEIGHT_REPS' then
            v_e_add_vol := v_e_add_vol + (coalesce((v_s->>'additional_weight')::numeric, 0) * (v_s->>'reps')::integer);
          end if;
          if v_track in ('WEIGHT_REPS', 'BODYWEIGHT_REPS', 'ASSISTED_REPS', 'REPS_ONLY') then
            v_e_reps := v_e_reps + coalesce((v_s->>'reps')::integer, 0);
          end if;
          if v_track in ('DURATION', 'DISTANCE_DURATION') then
            v_e_duration := v_e_duration + coalesce((v_s->>'duration_seconds')::integer, 0);
          end if;
          if v_track = 'DISTANCE_DURATION' then
            v_e_distance := v_e_distance + coalesce((v_s->>'distance_meters')::integer, 0);
          end if;
        end loop;

        insert into public.exercise_logs(
          id, workout_log_id, exercise_id, exercise_name, muscle_category,
          order_index, total_volume, total_sets, total_reps, total_duration_seconds,
          total_distance_meters, additional_volume, tracking_type, modality,
          metric_quality, input_defaults
        ) values (
          (v_e->>'id')::uuid, v_workout, v_e->>'exercise_id', v_e->>'name', v_e->>'muscle_category',
          (v_e->>'order_index')::integer, v_e_volume, v_e_sets, v_e_reps, v_e_duration,
          v_e_distance, v_e_add_vol, v_track::public.tracking_type_enum, v_modality::public.exercise_modality_enum,
          case when v_row.schema_version >= 2 and v_e ? 'tracking_type'
            then 'VERIFIED' else 'LEGACY_UNVERIFIED' end, v_e->'input_defaults'
        );

        for v_s in select value from jsonb_array_elements(v_e->'sets') where (value->>'is_completed')::boolean loop
          insert into public.workout_sets(
            id, exercise_log_id, workout_log_id, exercise_id, set_number,
            weight, reps, duration_seconds, distance_meters, additional_weight,
            assistance_weight, is_completed
          ) values (
            (v_s->>'id')::uuid, (v_e->>'id')::uuid, v_workout, v_e->>'exercise_id',
            (v_s->>'set_number')::integer,
            coalesce((v_s->>'weight')::numeric, 0),
            coalesce((v_s->>'reps')::integer, 0),
            (v_s->>'duration_seconds')::integer,
            (v_s->>'distance_meters')::integer,
            (v_s->>'additional_weight')::numeric,
            (v_s->>'assistance_weight')::numeric,
            true
          );
        end loop;
      end loop;
    end if;

    update public.active_sessions
    set status = v_status, version = version + 1, review_required = false,
        completed_workout_id = v_workout
    where id = p_session returning * into v_row;
  end if;

  v_result := imfit_private.session_receipt(v_row, p_request, v_operation);
  perform imfit_private.record_operation(v_owner, p_request, v_operation, 'SESSION', p_session, v_payload, v_result);
  return v_result;
end;
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
      'total_sets', e.total_sets,
      'total_reps', e.total_reps,
      'tracking_type', coalesce(e.tracking_type::text, 'WEIGHT_REPS'),
      'modality', e.modality::text,
      'total_duration_seconds', coalesce(e.total_duration_seconds, 0),
      'total_distance_meters', coalesce(e.total_distance_meters, 0),
      'additional_volume', coalesce(e.additional_volume, 0)::text,
      'metric_quality', coalesce(e.metric_quality, 'LEGACY_UNVERIFIED'),
      'sets', (
        select coalesce(jsonb_agg(jsonb_build_object(
          'id', s.id,
          'set_number', s.set_number,
          'weight', s.weight::text,
          'reps', s.reps,
          'duration_seconds', s.duration_seconds,
          'distance_meters', s.distance_meters,
          'additional_weight', case when s.additional_weight is not null then s.additional_weight::text end,
          'assistance_weight', case when s.assistance_weight is not null then s.assistance_weight::text end,
          'is_completed', s.is_completed
        ) order by s.set_number, s.id), '[]'::jsonb)
        from public.workout_sets s
        where s.exercise_log_id = e.id and s.is_completed
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

COMMENT ON FUNCTION public.persist_local_workout_aggregate(jsonb, jsonb, jsonb) IS
  'Import frozen legacy metadata without catalog defaults; preserve allowed-mode and measurement validation, v1 WEIGHT_REPS semantics, NULL unknown modality and receipt replay.';
COMMENT ON FUNCTION imfit_private.terminal_session(uuid, uuid, bigint, boolean) IS
  'Finalize using frozen session metadata and legacy metrics, never current catalog defaults; replay precedes metadata validation.';
COMMENT ON FUNCTION public.get_workout_detail(uuid) IS
  'Read stored history metadata only: NULL historical tracking means v1 WEIGHT_REPS; NULL modality remains unknown.';

-- New configuration still selects its format from the catalog when omitted.
-- Historical NULL tracking, however, is v1 WEIGHT_REPS, not a wildcard match.
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
    'distance_meters', s.distance_meters,
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

COMMENT ON FUNCTION imfit_private.prefill(uuid, text, jsonb) IS
  'Scalar prefill preserves catalog-derived configuration defaults but treats historical NULL tracking as v1 WEIGHT_REPS in candidate and HISTORY compatibility checks.';
